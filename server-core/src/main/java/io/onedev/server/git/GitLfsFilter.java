package io.onedev.server.git;

import static io.onedev.server.util.CollectionUtils.newHashMap;
import static javax.servlet.http.HttpServletResponse.SC_CONFLICT;
import static javax.servlet.http.HttpServletResponse.SC_CREATED;
import static javax.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
import static javax.servlet.http.HttpServletResponse.SC_NOT_ACCEPTABLE;
import static javax.servlet.http.HttpServletResponse.SC_NOT_IMPLEMENTED;
import static javax.servlet.http.HttpServletResponse.SC_NOT_FOUND;
import static javax.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.mime.MimeTypes;
import org.glassfish.jersey.client.ClientProperties;
import org.hibernate.criterion.Restrictions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingInputStream;

import static io.onedev.commons.bootstrap.Bootstrap.BUFFER_SIZE;
import io.onedev.k8shelper.KubernetesHelper;
import io.onedev.server.cluster.ClusterManager;
import io.onedev.server.entitymanager.GitLfsLockManager;
import io.onedev.server.entitymanager.ProjectManager;
import io.onedev.server.entitymanager.SettingManager;
import io.onedev.server.model.GitLfsLock;
import io.onedev.server.model.Project;
import io.onedev.server.model.User;
import io.onedev.server.persistence.SessionManager;
import io.onedev.server.persistence.dao.EntityCriteria;
import io.onedev.server.security.CodePullAuthorizationSource;
import io.onedev.server.security.SecurityUtils;
import io.onedev.server.util.facade.ProjectFacade;

@Singleton
public class GitLfsFilter implements Filter {

	private static final String CONTENT_TYPE = "application/vnd.git-lfs+json";
	
	private static final String DATETIME_FORMAT = "yyyy-MM-dd'T'HH:mm:ssXXX";
	
	public static final int MAX_PAGE_SIZE = 100;
	
	private static final Logger logger = LoggerFactory.getLogger(GitLfsFilter.class);
	
	private final ProjectManager projectManager;
	
	private final ObjectMapper objectMapper;
	
	private final SessionManager sessionManager;
	
	private final SettingManager settingManager;
	
	private final GitLfsLockManager lockManager;
	
	private final ClusterManager clusterManager;
	
	private final Set<CodePullAuthorizationSource> codePullAuthorizationSources;
	
	@Inject
	public GitLfsFilter(ProjectManager projectManager, ObjectMapper objectMapper, SessionManager sessionManager, 
			SettingManager settingManager, GitLfsLockManager lockManager, ClusterManager clusterManager,
			Set<CodePullAuthorizationSource> codePullAuthorizationSources) {
		this.projectManager = projectManager;
		this.objectMapper = objectMapper;
		this.sessionManager = sessionManager;
		this.settingManager = settingManager;
		this.lockManager = lockManager;
		this.clusterManager = clusterManager;
		this.codePullAuthorizationSources = codePullAuthorizationSources;
	}
	
	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
	}
	
	private long getMaxLFSFileSize() {
		return 1L * settingManager.getPerformanceSetting().getMaxGitLFSFileSize()*1024*1024;
	}
	
	private boolean canReadCode(HttpServletRequest request, Project project) {
		if (!SecurityUtils.canReadCode(project)) {
			for (CodePullAuthorizationSource source: codePullAuthorizationSources) {
				if (source.canPullCode(request, project)) 
					return true;
			}
			return false;
		} else {
			return true;
		}
	}

	private String getObjectUrl(HttpServletRequest request, String projectPath, String objectId) {
		return String.format("%s/%s.git/lfs/objects/%s?lfs-objects=true", 
				StringUtils.stripEnd(settingManager.getSystemSetting().getServerUrl(), "/\\"), 
				projectPath, objectId);
	}

	private String getProjectPath(String pathInfo) {
		return StringUtils.substringBeforeLast(pathInfo, ".git/");
	}
	
	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
			throws IOException, ServletException {
		HttpServletRequest httpRequest = (HttpServletRequest) request;
		HttpServletResponse httpResponse = (HttpServletResponse) response;
		String uri = httpRequest.getRequestURI();
		String pathInfo = uri.substring(httpRequest.getContextPath().length());
		pathInfo = StringUtils.stripStart(pathInfo, "/");
		boolean clusterAccess = User.SYSTEM_ID.equals(SecurityUtils.getUserId());
		
		if ("true".equals(httpRequest.getParameter("lfs-objects"))) {
			String projectPath = getProjectPath(pathInfo);
			String objectId = StringUtils.substringAfterLast(pathInfo, "/");
			
			if (httpRequest.getMethod().equals("GET")) {
				LfsObject lfsObject = null;
				if (clusterAccess) {
					lfsObject = new LfsObject(projectManager.findFacadeByPath(projectPath).getId(), objectId);
				} else {
					sessionManager.openSession();
					try {
						Project project = projectManager.findByPath(projectPath);
						if (canReadCode(httpRequest, project))  
							lfsObject = new LfsObject(project.getId(), objectId);
						else 
							sendAuthorizationError(httpResponse);
					} finally {
						sessionManager.closeSession();
					}
				}

				if (lfsObject != null) {
					httpResponse.setContentType(MimeTypes.OCTET_STREAM);
					UUID storageServerUUID = null;
					if (!clusterAccess) {
						storageServerUUID = projectManager.getStorageServerUUID(lfsObject.getProjectId(), true);
						if (storageServerUUID.equals(clusterManager.getLocalServerUUID()))
							storageServerUUID = null;
					}
					if (storageServerUUID == null) {
						try (
								InputStream is = new BufferedInputStream(lfsObject.getInputStream(), BUFFER_SIZE);
								OutputStream os = new BufferedOutputStream(httpResponse.getOutputStream(), BUFFER_SIZE);) {
							IOUtils.copy(is, os);
						}
					} else {
						Client client = ClientBuilder.newClient();
						try {
							String serverUrl = clusterManager.getServerUrl(storageServerUUID);
							WebTarget target = client.target(serverUrl)
									.path("api/cluster/lfs")
									.queryParam("projectId", lfsObject.getProjectId())
									.queryParam("objectId", lfsObject.getObjectId());
							Invocation.Builder builder =  target.request();
							builder.header(HttpHeaders.AUTHORIZATION, 
									KubernetesHelper.BEARER + " " + clusterManager.getCredentialValue());
							try (Response lfsResponse = builder.get()){
								KubernetesHelper.checkStatus(lfsResponse);
								try (
										InputStream is = new BufferedInputStream(
												lfsResponse.readEntity(InputStream.class), BUFFER_SIZE);
										OutputStream os = new BufferedOutputStream(
												httpResponse.getOutputStream(), BUFFER_SIZE);) {
									IOUtils.copy(is, os);
								}
							}
						} finally {
							client.close();
						}
					}
				}
			} else {
				LfsObject lfsObject = null;
				if (clusterAccess) {
					lfsObject = new LfsObject(projectManager.findFacadeByPath(projectPath).getId(), objectId);
				} else {
					sessionManager.openSession();
					try {
						Project project = projectManager.findByPath(getProjectPath(pathInfo));
						if (SecurityUtils.canWriteCode(project))  
							lfsObject = new LfsObject(project.getId(), objectId);
						else 
							sendAuthorizationError(httpResponse);
					} finally {
						sessionManager.closeSession();
					}
				}

				if (lfsObject != null) {
					UUID storageServerUUID = null;
					if (!clusterAccess) {
						storageServerUUID = projectManager.getStorageServerUUID(lfsObject.getProjectId(), true);
						if (storageServerUUID.equals(clusterManager.getLocalServerUUID())) 
							storageServerUUID = null;
					}
					
					var hash = new AtomicReference<String>(null);
					if (storageServerUUID == null) {
						try (
								HashingInputStream is = new HashingInputStream(
										Hashing.sha256(), 
										new BufferedInputStream(httpRequest.getInputStream(), BUFFER_SIZE));
								OutputStream os = new BufferedOutputStream(
										lfsObject.getOutputStream(), BUFFER_SIZE);) {
							IOUtils.copy(is, os);
							hash.set(Hex.encodeHexString(is.hash().asBytes()));
						}
					} else {
						Client client = ClientBuilder.newClient();
						client.property(ClientProperties.REQUEST_ENTITY_PROCESSING, "CHUNKED");
						try {
							String serverUrl = clusterManager.getServerUrl(storageServerUUID);
							WebTarget target = client.target(serverUrl)
									.path("api/cluster/lfs")
									.queryParam("projectId", lfsObject.getProjectId())
									.queryParam("objectId", lfsObject.getObjectId());
							Invocation.Builder builder =  target.request();
							builder.header(HttpHeaders.AUTHORIZATION, 
									KubernetesHelper.BEARER + " " + clusterManager.getCredentialValue());
							
							StreamingOutput os = new StreamingOutput() {

								@Override
								public void write(OutputStream output) throws IOException {
									try (
											HashingInputStream is = new HashingInputStream(
													Hashing.sha256(), 
													new BufferedInputStream(httpRequest.getInputStream(), BUFFER_SIZE));
											OutputStream os = new BufferedOutputStream(output, BUFFER_SIZE);) {
										IOUtils.copy(is, os);
										hash.set(Hex.encodeHexString(is.hash().asBytes()));
									}
								}				   
							   
							};
							
							try (Response lfsResponse = builder.post(Entity.entity(os, MediaType.APPLICATION_OCTET_STREAM))) {
								KubernetesHelper.checkStatus(lfsResponse);
							}
						} finally {
							client.close();
						}
					}
					if (!objectId.equals(hash.get())) {
						lfsObject.delete();
						throw new RuntimeException("Invalid uploaded content: hash not equals to object id");
					}
				}
			}				
		} else if (httpRequest.getContentType() != null 
					&& httpRequest.getContentType().startsWith(CONTENT_TYPE)
				|| httpRequest.getHeader("Accept") != null 
					&& httpRequest.getHeader("Accept").startsWith(CONTENT_TYPE)) {
			String projectPath = getProjectPath(pathInfo);
			
			if (clusterAccess) {
				ProjectFacade project = projectManager.findFacadeByPath(projectPath);
				if (project == null) {
					sendBatchError(httpResponse, SC_NOT_FOUND, 
							"Project not found: " + projectPath);
				} else {
					httpResponse.setContentType(CONTENT_TYPE);
					if (pathInfo.endsWith("/batch")) {
						processBatch(httpRequest, httpResponse, project, new BooleanSupplier() {
							
							@Override
							public boolean getAsBoolean() {
								return true;
							}
							
						}, new BooleanSupplier() {
							
							@Override
							public boolean getAsBoolean() {
								return true;
							}
							
						});
					} else {
						httpResponse.setStatus(SC_NOT_IMPLEMENTED);
					}
				}
			} else {
				sessionManager.openSession();
				try {
					Project project = projectManager.findByPath(projectPath);
					if (project == null) {
						sendBatchError(httpResponse, SC_NOT_FOUND, 
								"Project not found: " + projectPath);
					} else {
						httpResponse.setContentType(CONTENT_TYPE);
						if (pathInfo.endsWith("/batch")) {
							processBatch(httpRequest, httpResponse, project.getFacade(), new BooleanSupplier() {

								@Override
								public boolean getAsBoolean() {
									return canReadCode(httpRequest, project);
								}
								
							}, new BooleanSupplier() {
								
								@Override
								public boolean getAsBoolean() {
									return SecurityUtils.canWriteCode(project);
								}
								
							});
						} else if (pathInfo.endsWith("/locks")) {
							if (httpRequest.getMethod().equals("POST")) {
								if (SecurityUtils.canWriteCode(project)) {
									JsonNode lockRequestNode;
									try (InputStream is = httpRequest.getInputStream()) {
										lockRequestNode = objectMapper.readTree(is);
									}
									String path = lockRequestNode.get("path").asText();
									GitLfsLock lock = lockManager.find(path);
									if (lock == null) {
										lock = new GitLfsLock();
										lock.setPath(path);
										lock.setOwner(SecurityUtils.getUser());
										lockManager.save(lock);
										httpResponse.setStatus(SC_CREATED);
									} else {
										httpResponse.setStatus(SC_CONFLICT);
									}
									Map<Object, Object> lockResponse = newHashMap("lock", toMap(lock));
									if (httpResponse.getStatus() == SC_CONFLICT)
										lockResponse.put("message", "Lock exists");
									writeTo(httpResponse, lockResponse);
								} else {
									sendAuthorizationError(httpResponse);
								}
							} else {
								if (canReadCode(httpRequest, project)) {
									String path = httpRequest.getParameter("path");
									
									Long id = null;
									String idString = httpRequest.getParameter("id");
									if (idString != null)
										id = Long.valueOf(idString);
									
									int cursor = 0;
									String cursorString = httpRequest.getParameter("cursor");
									if (cursorString != null)
										cursor = Integer.parseInt(cursorString);
									
									int limit = MAX_PAGE_SIZE;
									String limitString = httpRequest.getParameter("limit");
									if (limitString != null)
										limit = Integer.parseInt(limitString);
									if (limit > MAX_PAGE_SIZE)
										limit = MAX_PAGE_SIZE;
									
									EntityCriteria<GitLfsLock> criteria = EntityCriteria.of(GitLfsLock.class);
									if (path != null)
										criteria.add(Restrictions.eq(GitLfsLock.PROP_PATH, path));
									if (id != null)
										criteria.add(Restrictions.eq(GitLfsLock.PROP_ID, id));
									
									List<Map<Object, Object>> locks = new ArrayList<>();
									for (GitLfsLock lock: lockManager.query(criteria, cursor, limit))
										locks.add(toMap(lock));
									Map<Object, Object> locksResponse = newHashMap("locks", locks);
									if (locks.size() == limit)
										locksResponse.put("next_cursor", String.valueOf(cursor+limit));
									writeTo(httpResponse, locksResponse);
								} else {
									sendAuthorizationError(httpResponse);
								}
							}
						} else if (pathInfo.endsWith("/locks/verify")) {
							if (SecurityUtils.canWriteCode(project)) {
								JsonNode lockVerifyNode;
								try (InputStream is = httpRequest.getInputStream()) {
									lockVerifyNode = objectMapper.readTree(is);
								}
		
								String path = null;
								JsonNode pathNode = lockVerifyNode.get("path");
								if (pathNode != null)
									path = pathNode.asText();
								
								Long id = null;
								JsonNode idNode = lockVerifyNode.get("id");
								if (idNode != null)
									id = idNode.asLong();
								
								int cursor = 0;
								JsonNode cursorNode = lockVerifyNode.get("cursor");
								if (cursorNode != null)
									cursor = cursorNode.intValue();
		
								int limit = MAX_PAGE_SIZE;
								JsonNode limitNode = lockVerifyNode.get("limit");
								if (limitNode != null)
									limit = limitNode.asInt();
								if (limit > MAX_PAGE_SIZE)
									limit = MAX_PAGE_SIZE;
								
								EntityCriteria<GitLfsLock> criteria = EntityCriteria.of(GitLfsLock.class);
								if (path != null)
									criteria.add(Restrictions.eq(GitLfsLock.PROP_PATH, path));
								if (id != null)
									criteria.add(Restrictions.eq(GitLfsLock.PROP_ID, id));
								
								List<Map<Object, Object>> ourLocks =  new ArrayList<>();
								List<Map<Object, Object>> theirLocks = new ArrayList<>();
								
								for (GitLfsLock lock: lockManager.query(criteria, cursor, limit)) {
									if (lock.getOwner().equals(SecurityUtils.getUser()))
										ourLocks.add(toMap(lock));
									else
										theirLocks.add(toMap(lock));
								}
								Map<Object, Object> verifyResponse = newHashMap(
										"ours", ourLocks, 
										"theirs", theirLocks);
								if (ourLocks.size() + theirLocks.size() == limit)
									verifyResponse.put("next_cursor", String.valueOf(cursor+limit));
								writeTo(httpResponse, verifyResponse);
							} else {
								sendAuthorizationError(httpResponse);
							}
						} else if (pathInfo.endsWith("/unlock")) {
							if (SecurityUtils.canWriteCode(project)) {
								Long id = Long.valueOf(StringUtils.substringAfterLast(
										StringUtils.substringBeforeLast(pathInfo, "/"), "/"));
								
								JsonNode lockDeleteNode;
								try (InputStream is = httpRequest.getInputStream()) {
									lockDeleteNode = objectMapper.readTree(is);
								}
								
								boolean force = false;
								JsonNode forceNode = lockDeleteNode.get("force");
								if (forceNode != null)
									force = forceNode.asBoolean();

								GitLfsLock lock = lockManager.load(id);
								if (lock.getOwner().equals(SecurityUtils.getUser())) {
									lockManager.delete(lock);
									writeTo(httpResponse, newHashMap("lock", toMap(lock)));
								} else if (force) {
									if (SecurityUtils.canManage(project)) {
										lockManager.delete(lock);
										writeTo(httpResponse, newHashMap("lock", toMap(lock)));
									} else {
										sendBatchError(httpResponse, SC_FORBIDDEN, "Only project managers can unlock forcibly");
									}
								} else {
									sendBatchError(httpResponse, SC_FORBIDDEN, "Lock is created by other users");
								}
							} else {
								sendAuthorizationError(httpResponse);
							}
						}
					}
				} catch (Exception e) {
					logger.error("Error handling LFS request", e);
					sendBatchError(httpResponse, SC_INTERNAL_SERVER_ERROR, 
							"Internal server error, please check server log");
				} finally {
					sessionManager.closeSession();
				}				
			}
		} else {
			chain.doFilter(request, response);
		}
	}
	
	private void processBatch(HttpServletRequest httpRequest, HttpServletResponse httpResponse, 
			ProjectFacade project, BooleanSupplier readCheck, BooleanSupplier writeCheck) {
		JsonNode batchRequestNode;
		try (InputStream is = httpRequest.getInputStream()) {
			batchRequestNode = objectMapper.readTree(is);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		boolean supportBasicTransfer;
		JsonNode transfersNode = batchRequestNode.get("transfers");
		if (transfersNode != null) {
			supportBasicTransfer = false;
			for (JsonNode transferNode: transfersNode) {
				if (transferNode.asText().equals("basic")) {
					supportBasicTransfer = true;
					break;
				}
			}
		} else {
			supportBasicTransfer = true;
		}
		if (!supportBasicTransfer) {
			sendBatchError(httpResponse, SC_NOT_ACCEPTABLE, 
					"This server can only accept basic transfer");
		} else {
			boolean supportSha256;
			JsonNode hashAlgoNode = batchRequestNode.get("hash_algo");
			if (hashAlgoNode != null)
				supportSha256 = hashAlgoNode.asText().equals("sha256");
			else
				supportSha256 = true;
			if (!supportSha256) {
				sendBatchError(httpResponse, SC_NOT_ACCEPTABLE, 
						"This server can only accept sha256 hash algorithm");
			} else {
				boolean upload = batchRequestNode.get("operation").asText().equals("upload");
				boolean authorized = false;
				if (upload) {
					if (!writeCheck.getAsBoolean()) 
						sendAuthorizationError(httpResponse);
					else
						authorized = true;
				} else {
					if (!readCheck.getAsBoolean())
						sendAuthorizationError(httpResponse);
					else
						authorized = true;
				}
				if (authorized) {
					List<Map<String, Object>> objectsResponse = new ArrayList<>();
					for (JsonNode objectNode: batchRequestNode.get("objects")) {
						String objectId = objectNode.get("oid").asText();
						long objectSize = objectNode.get("size").asLong();
						objectsResponse.add(getObjectResponse(
								httpRequest, project, upload, objectId, objectSize));
					}
					
					Map<String, Object> batchResponse = new HashMap<>();
					batchResponse.put("objects", objectsResponse);
					writeTo(httpResponse, batchResponse);
				}
			}
		}			
	}
	
	private void sendAuthorizationError(HttpServletResponse response) {
		if (SecurityUtils.getUser() != null) {
			sendBatchError(response, SC_FORBIDDEN, "Permission denied");
		} else {
			response.addHeader("LFS-Authenticate", "Basic realm=\"OneDev\"");
			sendBatchError(response, SC_UNAUTHORIZED, "Authentication required");
		}
	}
	
	private void writeTo(HttpServletResponse response, Object object) {
		try (OutputStream os = response.getOutputStream()) {
			os.write(objectMapper.writeValueAsBytes(object));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	
	private Map<Object, Object> toMap(GitLfsLock lock) {
		return newHashMap(
				"id", String.valueOf(lock.getId()), 
				"path", lock.getPath(),
				"locked_at", new SimpleDateFormat(DATETIME_FORMAT).format(lock.getDate()),
				"owner", newHashMap(
						"name", lock.getOwner().getDisplayName()));
	}

	private Map<Object, Object> getActionResponse(HttpServletRequest request, ProjectFacade project, String objectId) {
		Map<Object, Object> actionResponse = newHashMap(
				"href", getObjectUrl(request, project.getPath(), objectId));
		User user = SecurityUtils.getUser();
		if (user != null)
			actionResponse.put(
					"header", newHashMap(
							"Authorization", KubernetesHelper.BEARER + " " + user.getAccessToken()));
		return actionResponse;
	}
	
	private Map<String, Object> getObjectResponse(HttpServletRequest request, ProjectFacade project, boolean upload, 
			String objectId, long objectSize) {
		Map<String, Object> objectResponse = new HashMap<>();
		objectResponse.put("oid", objectId);
		objectResponse.put("size", objectSize);
		LfsObject lfsObject = new LfsObject(project.getId(), objectId);
		if (objectSize > getMaxLFSFileSize()) {
			objectResponse.put("error", newHashMap(
					"code", SC_NOT_ACCEPTABLE, 
					"message", "Exceeded max acceptable LFS file size " + getMaxLFSFileSize()));
		} else if (upload) {
			if (!lfsObject.exists()) {
				objectResponse.put(
						"actions", newHashMap(
								"upload", getActionResponse(request, project, objectId)));
			}
		} else if (lfsObject.exists()) {
			objectResponse.put(
					"actions", newHashMap(
							"download", getActionResponse(request, project, objectId)));
		} else {
			objectResponse.put("error", newHashMap(
					"code", SC_NOT_FOUND, 
					"message", "Object not found"));
		}
		return objectResponse;
	}
	
	private void sendBatchError(HttpServletResponse response, int statusCode, String errorMessage) {
		response.setContentType(CONTENT_TYPE);
		response.setStatus(statusCode);
		Map<String, String> batchResponse = new HashMap<>();
		batchResponse.put("message", errorMessage);
		writeTo(response, batchResponse);
	}

	@Override
	public void destroy() {
	}

}
