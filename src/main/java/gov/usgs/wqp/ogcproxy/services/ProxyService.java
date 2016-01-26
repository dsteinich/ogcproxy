package gov.usgs.wqp.ogcproxy.services;

import static org.springframework.util.StringUtils.isEmpty;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpTrace;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.context.request.async.DeferredResult;

import gov.usgs.wqp.ogcproxy.exceptions.OGCProxyException;
import gov.usgs.wqp.ogcproxy.exceptions.OGCProxyExceptionID;
import gov.usgs.wqp.ogcproxy.model.ogc.parameters.WFSParameters;
import gov.usgs.wqp.ogcproxy.model.ogc.parameters.WMSParameters;
import gov.usgs.wqp.ogcproxy.model.ogc.services.OGCServices;
import gov.usgs.wqp.ogcproxy.model.parameters.ProxyDataSourceParameter;
import gov.usgs.wqp.ogcproxy.model.parameters.SearchParameters;
import gov.usgs.wqp.ogcproxy.model.parser.xml.ogc.RequestWrapper;
import gov.usgs.wqp.ogcproxy.services.wqp.WQPLayerBuildingService;
import gov.usgs.wqp.ogcproxy.utils.ProxyServiceResult;
import gov.usgs.wqp.ogcproxy.utils.ProxyUtil;
import gov.usgs.wqp.ogcproxy.utils.SystemUtils;


public class ProxyService {
	/*========================================================================
	 * Wired Spring Beans ===========================================================
	 */
	@Autowired
	private Environment environment;

	@Autowired
	private WQPLayerBuildingService wqpLayerBuildingService;

	
	/* ========================================================================
	 * Static Local =======================================================
	 */
	private static final Logger LOG = LoggerFactory.getLogger(ProxyService.class);
	
	private static boolean initialized;
	private static boolean layerPassthrough;
	
	private static long threadTimeout = 604800000; // 1000 * 60 * 60 * 24 * 7 (1 week)
	private static long threadSleep   = 500;
	
	private static String geoserverProtocol  = "http";
	private static String geoserverHost      = "localhost";
	private static String geoserverPort      = "8080";
	private static String forwardUrl         = "http://localhost:8080";
	private static String geoserverContext   = "/geoserver";
	private static String geoserverWorkspace = "qw_portal_map";

	private static int connection_ttl            = 15 * 60 * 1000; // 15 minutes, default is infinite
	private static int connections_max_total     = 256;
	private static int connections_max_route     = 32;
	private static int client_socket_timeout     = 5 * 60 * 1000; // 5 minutes, default is infinite
	private static int client_connection_timeout = 15 * 1000; // 15 seconds, default is infinte

	private static Set<String> ignoredClientRequestHeaderSet  = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
	private static Set<String> ignoredServerResponseHeaderSet = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
	
	private static final ProxyService INSTANCE = new ProxyService();

	/* ========================================================================
	 * Local ===========================================================
	 */
	protected PoolingHttpClientConnectionManager clientConnectionManager;
	protected CloseableHttpClient serverClient;

	

	/**
	 * Private Constructor for Singleton Pattern
	 */
	private ProxyService() {}

	/**
	 * Singleton accessor
	 *
	 * @return ProxyService instance
	 */
	public static ProxyService getInstance() {
		return INSTANCE;
	}

	/**
	 * Since we are using Spring DI we cannot access the environment bean in
	 * the constructor. We'll just use a locked initialized variable to
	 * check initialization after instantiation and set the env properties here.
	 */
	@PostConstruct
	public void initialize() {
		if ( initialized ) {
			return;
		}
		synchronized (ProxyService.class) {
			if ( initialized ) {
				return;
			}
			initialized = true;
			
			try {
				threadTimeout = Long.parseLong(environment.getProperty("proxy.thread.timeout"));
			} catch (Exception e) {
				LOG.error("ProxyService() Constructor Exception: Failed to parse property [proxy.thread.timeout] "
						+ "- Keeping cache timeout period default to ["+ threadTimeout+ "].\n"
						+ e.getMessage()+ "\n");
			}

			try {
				threadSleep = Long.parseLong(environment.getProperty("proxy.thread.sleep"));
			} catch (Exception e) {
				LOG.error("ProxyService() Constructor Exception: Failed to parse property [proxy.thread.sleep] "
						+ "- Keeping thread sleep default to ["+ threadSleep + "].\n" + e.getMessage() + "\n");
			}

			try {
				String defaultProto = geoserverProtocol;
				geoserverProtocol = environment.getProperty("wqp.geoserver.proto");
				if (isEmpty(geoserverProtocol)) {
					LOG.error("ProxyService() Constructor Exception: Failed to parse property [wqp.geoserver.proto] "
							+ "- Setting geoserver protocol default to ["+ defaultProto + "].\n");
					geoserverProtocol = defaultProto;
				}
			} catch (Exception e) {
				LOG.error("ProxyService() Constructor Exception: Failed to parse property [wqp.geoserver.proto] "
						+ "- Setting geoserver protocol default to ["+ geoserverProtocol + "].\n");
			}

			try {
				String defaultHost = geoserverHost;
				geoserverHost = environment.getProperty("wqp.geoserver.host");
				if (isEmpty(geoserverHost)) {
					LOG.error("ProxyService() Constructor Exception: Failed to parse property [wqp.geoserver.host] "
							+ "- Setting geoserver host default to ["+ defaultHost + "].\n");
					geoserverHost = defaultHost;
				}
			} catch (Exception e) {
				LOG.error("ProxyService() Constructor Exception: Failed to parse property [wqp.geoserver.host] "
						+ "- Setting geoserver host default to ["+ geoserverHost + "].\n");
			}

			try {
				String defaultPort = geoserverPort;
				geoserverPort = environment.getProperty("wqp.geoserver.port");
				if (isEmpty(geoserverPort)) {
					LOG.error("ProxyService() Constructor Exception: Failed to parse property [wqp.geoserver.port] "
							+ "- Setting geoserver port default to ["+ defaultPort + "].\n");
					geoserverPort = defaultPort;
				}
			} catch (Exception e) {
				LOG.error("ProxyService() Constructor Exception: Failed to parse property [wqp.geoserver.port] "
						+ "- Setting geoserver port default to ["+ geoserverPort + "].\n");
			}

			forwardUrl = geoserverProtocol + "://" + geoserverHost+ ":" + geoserverPort;
			LOG.info("ProxyService() Constructor Info: Setting GeoServer forwarding URL to ["+ forwardUrl + "]");

			try {
				String defaultContext = geoserverContext;
				geoserverContext = environment
						.getProperty("wqp.geoserver.context");
				if (isEmpty(geoserverContext)) {
					LOG.error("ProxyService() Constructor Exception: Failed to parse property [wqp.geoserver.context] "
							+ "- Setting geoserver context default to ["+ defaultContext + "].\n");
					geoserverContext = defaultContext;
				}
			} catch (Exception e) {
				LOG.error("ProxyService() Constructor Exception: Failed to parse property [wqp.geoserver.context] "
						+ "- Setting geoserver context default to ["+ geoserverContext + "].\n");
			}

			try {
				String defaultWorkspace = geoserverWorkspace;
				geoserverWorkspace = environment
						.getProperty("wqp.geoserver.workspace");
				if (isEmpty(geoserverWorkspace)) {
					LOG.error("ProxyService() Constructor Exception: Failed to parse property [wqp.geoserver.workspace] "
							+ "- Setting geoserver workspace default to ["+ defaultWorkspace + "].\n");
					geoserverWorkspace = defaultWorkspace;
				}
			} catch (Exception e) {
				LOG.error("ProxyService() Constructor Exception: Failed to parse property [wqp.geoserver.workspace] "
						+ "- Setting geoserver workspace default to ["+ geoserverWorkspace + "].\n");
			}

			// Set the pass-through behavior. If a searchParam request
			// comes through and the layer parameter is NOT set to a
			// "known" value, we can either pass it through and work as
			// expected or return an error
			try {
				layerPassthrough = Boolean.parseBoolean(environment.getProperty("proxy.layerparam.passthrough"));
			} catch (Exception e) {
				LOG.error("ProxyService() Constructor Exception: Failed to parse property [proxy.layerparam.passthrough] "
						+ "- Proxy Layer Passthrough setting default to ["+ layerPassthrough+ "].\n"
						+ e.getMessage()+ "\n");
			}

			// Initialize connection manager, this is thread-safe. if we
			// use this
			// with any HttpClient instance it becomes thread-safe.
			clientConnectionManager = new PoolingHttpClientConnectionManager(
					connection_ttl, TimeUnit.MILLISECONDS);
			clientConnectionManager.setMaxTotal(connections_max_total);
			clientConnectionManager.setDefaultMaxPerRoute(connections_max_route);

			RequestConfig config = RequestConfig.custom().setConnectTimeout(client_connection_timeout)
					.setSocketTimeout(client_socket_timeout).build();

			//.disableContentCompression() keeps the response from geoserver in it's native form so the existing 
			//logic still works - otherwise it tries to decompress it causing issues in the scrubbing process.
			serverClient = HttpClients.custom().setConnectionManager(clientConnectionManager)
					.disableContentCompression().setDefaultRequestConfig(config).build();

			// Ignored headers relating to proxing requests
			// don't parameterize, need to swtich host from proxy to server
			ignoredClientRequestHeaderSet.add("host");
			// don't parameterize, let proxy to server call do it's own handling
			ignoredClientRequestHeaderSet.add("connection");
			// parameterize (cookies passthru?)
			ignoredClientRequestHeaderSet.add("cookie");
			// parameterize (authorization passthru?)
			ignoredClientRequestHeaderSet.add("authorization");
			// ignore header in request, this is set in client call.
			ignoredClientRequestHeaderSet.add("content-length");

			// Ignored headers relating to proxing reponses.
			// don't parameterize
			ignoredServerResponseHeaderSet.add("transfer-encoding");
			// don't parameterize
			ignoredServerResponseHeaderSet.add("keep-alive");
			// parameterize (cookies passthru?)
			ignoredServerResponseHeaderSet.add("set-cookie");
			// parameterize (authorization passthru?)
			ignoredServerResponseHeaderSet.add("authorization");
			// ignoredServerResponseHeaderSet.add("content-length");
			// allow for now, NOTE: are you doing response body content rewrite?
		}
	}


	/**
	 * This method provides WMS request proxying along with intercepting
	 * WMS requests and providing additional functionality based on the
	 * filters found in the request parameters.
	 *
	 * @param request
	 * @param response
	 * @param requestParams
	 * @param finalResult
	 */
	public DeferredResult<String> performGetRequestWms(HttpServletRequest request, HttpServletResponse response, Map<String, String> requestParams) {
		
		String primaryLayerParam    = WMSParameters.layers.toString();
		String secondaryLayerParam  = WMSParameters.query_layers.toString();
		
		// get value ignoring case -- TODO not DRY
		String primaryLayerActual    = ProxyUtil.getCaseSensitiveParameter(primaryLayerParam, requestParams);
		String secondaryLayerActual  = ProxyUtil.getCaseSensitiveParameter(secondaryLayerParam, requestParams);
		
		// put value with proper case - TODO not DRY
		if ( ! primaryLayerParam.equals(primaryLayerActual) ) {
			String value = requestParams.remove(primaryLayerActual);
			if (value !=null ) {
				requestParams.put(primaryLayerParam, value);
			}
		}
		if ( ! secondaryLayerParam.equals(secondaryLayerActual) ) {
			String value = requestParams.remove(secondaryLayerActual);
			if (value !=null ) {
				requestParams.put(secondaryLayerParam, value);
			}
		}
				
		return performRequest(request, response, requestParams, OGCServices.WMS, primaryLayerParam, secondaryLayerParam);
	}

	/**
	 * As of initial release, this method provides the same
	 * functionality as the WMS proxying provides, including building
	 * a layer if the passed in search params do not exist.
	 *
	 * The main reason we called out a different method for WFS calls
	 * is the possibility of doing completely different logic based
	 * on this type of call.
	 *
	 * @param request
	 * @param response
	 * @param requestParams
	 * @param finalResult
	 */
	public DeferredResult<String>  performGetRequestWfs(HttpServletRequest request, HttpServletResponse response, Map<String, String> requestParams) {
		
		String primaryLayerParam    = WFSParameters.typeNames.toString();
		String secondaryLayerParam  = WFSParameters.typeName.toString();
		
		return performRequest(request, response, requestParams, OGCServices.WFS, primaryLayerParam, secondaryLayerParam);
	}
	
	/**
	 * This method provides WMS/WFS request proxying along with intercepting
	 * WMS/WFS requests and providing additional functionality based on the
	 * filters found in the request parameters.
	 *
	 * @param request
	 * @param response
	 * @param requestParams
	 * @param finalResult
	 */
	public DeferredResult<String> performRequest(HttpServletRequest request, HttpServletResponse response, 
			Map<String, String> requestParams, OGCServices ogcService, String primaryLayerParam, String secondaryLayerParam) {
		
		DeferredResult<String> deferredResult    = new DeferredResult<String>();

		ProxyDataSourceParameter dataSource	     = ProxyDataSourceParameter.UNKNOWN;
		
		SearchParameters<String, List<String>> searchParams = new SearchParameters<String, List<String>>();
		Map<String, String> ogcParams = new HashMap<String, String>();
		ProxyUtil.separateParameters(requestParams, ogcParams, searchParams);
		
		// Lets see if the layers and/or queryParameter parameter is what
		// we are expecting and decide what to do depending on its value.
		// We need to capture the pure servlet parameter key for our searchParams parameter.
		// Since this can be case INSENSITIVE but we use its value as a key in a map, we need
		// to know what the exact character sequence is going forward.
		Set<String> layerParams = new HashSet<String>();
		
		String primaryLayerValue = ogcParams.get(primaryLayerParam);
		if ( ! isEmpty(primaryLayerValue) ) {
			dataSource = ProxyDataSourceParameter.getTypeFromString(primaryLayerValue);

			if (dataSource != ProxyDataSourceParameter.UNKNOWN) {
				layerParams.add(primaryLayerParam);
			}
		}

		String secondaryLayerValue = ogcParams.get(secondaryLayerParam);
		if ( ! isEmpty(secondaryLayerValue) ) {
			dataSource = ProxyDataSourceParameter.getTypeFromString(secondaryLayerValue);

			if (dataSource != ProxyDataSourceParameter.UNKNOWN) {
				layerParams.add(secondaryLayerParam);
			}
		}
		
		// use dynamic layers for search params
		if (searchParams.size() > 0) {
			// Did we find a legitimate layer value or do we need to return an
			// error (we must have a layer value to do a dynamic search)?
			if ((layerParams.size() == 0) && ! layerPassthrough ) {
				deferredResult.setResult(ProxyUtil.PROXY_LAYER_ERROR);
				return deferredResult;
			}

			// We can now proceed with the request. Depending on the value of
			// the layer parameter we will call the correct layer building service.
			LOG.info("ProxyService.performRequest() Info: Kicking off search parameter logic for data source ["
					+ dataSource + "]");
			if (dataSource == ProxyDataSourceParameter.WQP_SITES) {
				// TODO asdf this must handle the POST
				ProxyServiceResult result = wqpLayerBuildingService
						.getDynamicLayer(ogcParams, searchParams, layerParams, ogcService, ProxyDataSourceParameter.WQP_SITES);

				if (result != ProxyServiceResult.SUCCESS) {
					deferredResult.setResult(result.toString());
					return deferredResult;
				}
			}
		}
		
		// We now need to perform the proxy call to the GeoServer and return the result to the client
		String ogcRequestType = ogcParams.get("request");
		if (ogcRequestType == null) {
			ogcRequestType = "";
		}
		
		boolean proxySuccess = proxyRequest(request, response, ogcParams, ogcRequestType, ogcService, dataSource);
		
		if (proxySuccess) {
			LOG.info("ProxyService.performRequest() INFO: Proxy request is completed successfully.");
			deferredResult.setResult(ProxyServiceResult.SUCCESS.toString());
		} else {
			LOG.error("ProxyService.performRequest() Error:  Unable to proxy client request.");
			deferredResult.setResult(ProxyServiceResult.ERROR.toString());
		}
		return deferredResult;
	}
	

	
	
	/**
	 * Logic and supporting methods stolen from
	 * gov.usgs.cida.proxy.AlternateProxyServlet
	 *
	 * @param clientRequest
	 * @param clientResponse
	 * @param requestParams
	 * @return
	 */
	private boolean proxyRequest(HttpServletRequest clientRequest, HttpServletResponse clientResponse, Map<String, String> requestParams,
			String ogcRequestType, OGCServices serviceType, ProxyDataSourceParameter dataSource) {
		
		try {
			// TODO asdf this must handle the POST
			HttpUriRequest serverRequest = generateServerRequest(clientRequest, requestParams);
			handleServerRequest(clientRequest, clientResponse, serverRequest, requestParams, ogcRequestType, serviceType, dataSource);
		} catch (OGCProxyException e) {
			LOG.error("ProxyService.proxyRequest() Error: proxying client request: " + e.getMessage());
			return false;
		}

		return true;
	}

	private HttpUriRequest generateServerRequest( HttpServletRequest clientRequest, final Map<String, String> ogcParams) 
			throws OGCProxyException {
		
		HttpUriRequest serverRequest = null;
		
		try {
			// 1) Generate Server URI
			String serverRequestURIAsString = ProxyUtil.getServerRequestURIAsString(clientRequest, ogcParams,
							ProxyService.forwardUrl, ProxyService.geoserverContext);

			LOG.info("ProxyService.generateServerRequest(): request to GeoServer is: [\n" + serverRequestURIAsString + "]");

			// instantiating to URL then calling toURI gives us some error
			// checking as URI(String) appears too forgiving.
			URI serverRequestURI = new URL(serverRequestURIAsString).toURI();

			// 2 ) Create request base on client request method
			String clientRequestMethod = clientRequest.getMethod();
			
			if ("HEAD".equals(clientRequestMethod)) {
				serverRequest = new HttpHead(serverRequestURI);
			} else if ("GET".equals(clientRequestMethod)) {
				serverRequest = new HttpGet(serverRequestURI);
			} else if ("POST".equals(clientRequestMethod)) {
				serverRequest = new HttpPost(serverRequestURI);
			} else if ("PUT".equals(clientRequestMethod)) {
				serverRequest = new HttpPut(serverRequestURI);
			} else if ("DELETE".equals(clientRequestMethod)) {
				serverRequest = new HttpDelete(serverRequestURI);
			} else if ("TRACE".equals(clientRequestMethod)) {
				serverRequest = new HttpTrace(serverRequestURI);
			} else if ("OPTIONS".equals(clientRequestMethod)) {
				serverRequest = new HttpOptions(serverRequestURI);
			} else {
				String msg = "ProxyService.generateServerRequest() Exception : Unsupported request method [" + serverRequest + "].";
				LOG.error(msg);

				OGCProxyExceptionID id = OGCProxyExceptionID.UNSUPPORTED_REQUEST_METHOD;
				throw new OGCProxyException(id, "ProxyService", "generateServerRequest()", msg);
			}

			// 3) Map client request headers to server request
			ProxyUtil.generateServerRequestHeaders(clientRequest, serverRequest, ProxyService.ignoredClientRequestHeaderSet);

			// 4) Copy client request body to server request
			int contentLength = clientRequest.getContentLength();
			String body = "";
			// revise the length to the length of the body without the searchParams
			if (clientRequest instanceof RequestWrapper) {
				String dynamicLayerName = ogcParams.get(WFSParameters.typeName.toString());
				body = ((RequestWrapper)clientRequest).getPostBodySansSearchParams();
				if ( ! isEmpty(dynamicLayerName) ) {
					body = ((RequestWrapper)clientRequest).getPostBodySansSearchParams().replaceAll(ProxyDataSourceParameter.WQP_SITES.toString(), dynamicLayerName);
				}
				// TODO asdf need to replace typeName with dynamic layer name here
				contentLength = body.length();
			}
			
			if (contentLength > 0) {
				// is this a POST (or PUT)
				if (serverRequest instanceof HttpEntityEnclosingRequest) {
					try {
						HttpEntityEnclosingRequest entityRequest = (HttpEntityEnclosingRequest) serverRequest;
						
						InputStreamEntity serverRequestEntity = new InputStreamEntity(clientRequest.getInputStream(), contentLength);
						serverRequestEntity.setContentType(clientRequest.getContentType());
						((HttpEntityEnclosingRequest) serverRequest).setEntity(serverRequestEntity);
												
				        HttpEntity entity = new ByteArrayEntity(body.getBytes("UTF-8"));
				        entityRequest.setEntity(entity);
				 
					} catch (IOException e) {
						String msg = "ProxyService.generateServerRequest() Exception : Error reading client request body [" + e.getMessage() + "].";
						LOG.error(msg);

						OGCProxyExceptionID id = OGCProxyExceptionID.ERROR_READING_CLIENT_REQUEST_BODY;
						throw new OGCProxyException(id, "ProxyService", "generateServerRequest()", msg);
					}
				} else {
					String msg = "ProxyService.generateServerRequest() Exception : Content in request body unsupported for client request method [" + serverRequest.getMethod() + "].";
					LOG.error(msg);

					OGCProxyExceptionID id = OGCProxyExceptionID.UNSUPPORTED_CONTENT_FOR_REQUEST_METHOD;
					throw new OGCProxyException(id, "ProxyService", "generateServerRequest()", msg);
				}
			}

		} catch (MalformedURLException e) {
			String msg = "ProxyService.generateServerRequest() Exception : Syntax error parsing server URL ["
					+ e.getMessage() + "].";
			LOG.error(msg);

			OGCProxyExceptionID id = OGCProxyExceptionID.URL_PARSING_EXCEPTION;
			throw new OGCProxyException(id, "ProxyService",
					"generateServerRequest()", msg);
		} catch (URISyntaxException e) {
			String msg = "ProxyService.generateServerRequest() Exception : Syntax error parsing server URL ["
					+ e.getMessage() + "].";
			LOG.error(msg);

			OGCProxyExceptionID id = OGCProxyExceptionID.URL_PARSING_EXCEPTION;
			throw new OGCProxyException(id, "ProxyService",
					"generateServerRequest()", msg);
		}

		return serverRequest;
	}

	private void handleServerRequest(HttpServletRequest clientRequest,
			HttpServletResponse clientResponse, HttpUriRequest serverRequest,
			final Map<String, String> ogcParams, String ogcRequestType,
			OGCServices serviceType, ProxyDataSourceParameter dataSource) throws OGCProxyException {
		
		try {
			HttpContext localContext = new BasicHttpContext();
			HttpResponse methodReponse = serverClient.execute(serverRequest, localContext);
			handleServerResponse(clientRequest, clientResponse, methodReponse,
					ogcParams, ogcRequestType, serviceType, dataSource);
		} catch (ClientProtocolException e) {
			String msg = "ProxyService.handleServerRequest() Exception : Client protocol error ["
					+ e.getMessage() + "]";
			LOG.error(msg);

			OGCProxyExceptionID id = OGCProxyExceptionID.CLIENT_PROTOCOL_ERROR;
			throw new OGCProxyException(id, "ProxyService",
					"handleServerRequest()", msg);
		} catch (IOException e) {
			String msg = "ProxyService.handleServerRequest() Exception : I/O error on server request ["
					+ e.getMessage() + "]";
			LOG.error(msg);

			OGCProxyExceptionID id = OGCProxyExceptionID.SERVER_REQUEST_IO_ERROR;
			throw new OGCProxyException(id, "ProxyService",
					"handleServerRequest()", msg);
		}
	}

	private void handleServerResponse(HttpServletRequest clientRequest,
			HttpServletResponse clientResponse, HttpResponse serverResponse,
			final Map<String, String> ogcParams, String ogcRequestType,
			OGCServices serviceType, ProxyDataSourceParameter dataSource) throws OGCProxyException {
		
		String clientRequestURLAsString = ProxyUtil.getClientRequestURIAsString(clientRequest);
		String serverRequestURLAsString = ProxyUtil.getServerRequestURIAsString(clientRequest, ogcParams,
						ProxyService.forwardUrl, ProxyService.geoserverContext);

		// 1) Map server response status to client response
		// NOTE: There's no clear way to map status message,
		// HttpServletResponse.sendError(int, String)
		// will display some custom html (we don't want that here).
		// HttpServletResponse.setStatus(int, String)
		// is deprecated and i'm not certain there is (will be) an functional implementation behind it.
		StatusLine serverStatusLine = serverResponse.getStatusLine();
		int statusCode = serverStatusLine.getStatusCode();
		clientResponse.setStatus(statusCode);
		LOG.debug("ProxyService.handleServerResponse() DEBUG: Mapped server status code "
				+ statusCode);

		// 2) Map server response headers to client response
		ProxyUtil.generateClientResponseHeaders(clientResponse, serverResponse, ProxyService.ignoredServerResponseHeaderSet);

		// 3) Copy server response body to client response
		HttpEntity methodEntity = serverResponse.getEntity();
		if (methodEntity != null) {

			InputStream  is = null;
			OutputStream os = null;

			boolean contentCompressed = false;

			if (methodEntity.getContentEncoding() != null) {
				String contentEncoding = methodEntity.getContentEncoding().getValue();
				if ( "gzip".equalsIgnoreCase(contentEncoding) ) {
					contentCompressed = true;
				}
			}

			long responseBytes = 0;

			try {
				// !!! Are you here to edit this to enable response body content rewrite?
				// You may want to remove or edit the "Content-Length" header !!!
				try {
					is = methodEntity.getContent();
				} catch (IOException e) {
					String msg = "ProxyService.handleServerResponse() Exception : Error obtaining input stream for server response ["
							+ e.getMessage() + "]";
					LOG.error(msg);

					OGCProxyExceptionID id = OGCProxyExceptionID.SERVER_RESPONSE_INPUT_STREAM_ERROR;
					throw new OGCProxyException(id, "ProxyService",
							"handleServerResponse()", msg);
				}

				try {
					os = clientResponse.getOutputStream();
				} catch (IOException e) {
					String msg = "ProxyService.handleServerResponse() Exception : Error obtaining output stream for client response ["
							+ e.getMessage() + "]";
					LOG.error(msg);

					OGCProxyExceptionID id = OGCProxyExceptionID.CLIENT_RESPONSE_OUTPUT_STREAM_ERROR;
					throw new OGCProxyException(id, "ProxyService",
							"handleServerResponse()", msg);
				}

				byte[] serverContent;
				try {
					serverContent = IOUtils.toByteArray(is);
				} catch (IOException e) {
					String msg = "ProxyService.handleServerResponse() Exception : Error copying server response to byteArray ["
							+ e.getMessage() + "]";
					LOG.error(msg);

					OGCProxyExceptionID id = OGCProxyExceptionID.SERVER_TO_CLIENT_RESPONSE_ERROR;
					throw new OGCProxyException(id, "ProxyService",
							"handleServerResponse()", msg);
				}
				
				// Now, if the server response is xml we need to inspect it and
				// modify depending on a couple logic steps.
				//
				// 		WMS uses "text/xml" as the contentType
				// 		WFS uses "application/xml" as the contentType.
				//
				// We will just search for "xml" in the contentType value to
				// cover both cases.  If we need more contentType support we'll
				// have to expand the following logic.
				byte[] inspectedBytes;
				String contentType = methodEntity.getContentType().getValue();
				if ((contentType != null) && (contentType.toLowerCase().contains("xml"))) {
					inspectedBytes = inspectServerContent(clientRequest, ogcRequestType, serviceType, serverContent, contentCompressed, dataSource);
					
					// We must set the content-length here for the possible change
					// in content from the inspection
					clientResponse.setContentLength(inspectedBytes.length);
				} else {
					inspectedBytes = serverContent;
				}
	
				try {
					IOUtils.write(inspectedBytes, os);
				} catch (IOException e) {
					String msg = "ProxyService.handleServerResponse() Exception : Error copying server response to client ["
							+ e.getMessage() + "]";
					LOG.error(msg);

					OGCProxyExceptionID id = OGCProxyExceptionID.SERVER_TO_CLIENT_RESPONSE_ERROR;
					throw new OGCProxyException(id, "ProxyService",
							"handleServerResponse()", msg);
				}

			} finally {
				LOG.debug("ProxyService.handleServerResponse() DEBUG: Copied "
						+ responseBytes
						+ " bytes from server response for proxy from "
						+ clientRequestURLAsString + " to "
						+ serverRequestURLAsString);
				try {
					// This is important to guarantee connection release back
					// into
					// connection pool for future reuse!
					EntityUtils.consume(methodEntity);
				} catch (IOException e) {
					LOG.error("ProxyService.handleServerResponse() Error: consuming remaining bytes in server response entity for proxy reponse from "
							+ clientRequestURLAsString
							+ " to "
							+ serverRequestURLAsString);
				}
				IOUtils.closeQuietly(is);
				IOUtils.closeQuietly(os);
			}
		} else {
			LOG.warn("ProxyService.handleServerResponse() WARN: Server response was empty for proxy response from "
					+ clientRequestURLAsString
					+ " to "
					+ serverRequestURLAsString);
		}
	}
	
	private byte[] inspectServerContent(HttpServletRequest clientRequest, String ogcRequestType, OGCServices serviceType, byte[] serverContent, boolean contentCompressed, ProxyDataSourceParameter dataSource) throws OGCProxyException {
		String stringContent = "";
		if (contentCompressed) {
			stringContent = SystemUtils.uncompressGzipAsString(serverContent);
		} else {
			stringContent = Arrays.toString(serverContent);
		}
		
		// We now need to do some inspection on the data.  If the original OGC
		// request is a GetCapabilities, we need to insert the service's specific
		// information into the response.
		if (ProxyUtil.OGC_GET_CAPABILITIES.toLowerCase().equals(ogcRequestType.toLowerCase())) {
			// This is a GetCapabilities call.  We need to include the service
			// specific GetCapabilities information in the result so we conform
			// to the OGC spec
			switch (dataSource) {
				case WQP_SITES: {
					stringContent = wqpLayerBuildingService.addGetCapabilitiesInfo(serviceType, stringContent);
					break;
				}
				default: {
					break;
				}
			}
		}
		
		// We also need to scrub the response for any mention of the actual
		// GeoServer's location and replace it with this proxy's location.
		if (stringContent.contains(geoserverHost)) {
			LOG.info("ProxyService.inspectServerContent() INFO : Server response contains references to its hostname.  Redirecting entries to the proxy...");

			String proxyServerName = clientRequest.getServerName();
			String proxyContextPath = clientRequest
					.getContextPath();
			String proxyPort = clientRequest.getLocalPort() + "";
			String proxyProtocol = clientRequest.getScheme();

			stringContent = ProxyUtil.redirectContentToProxy(
					stringContent, geoserverProtocol,
					proxyProtocol, geoserverHost, proxyServerName,
					geoserverPort, proxyPort, geoserverContext,
					proxyContextPath);
		} else {
			LOG.debug("\n\nProxyService.handleServerResponse() DEBUG : Server response does not contain references to its hostname.  Continuing...\n\n");
		}
		
		if (contentCompressed) {
			return SystemUtils.compressStringToGzip(stringContent);
		} else {
			return stringContent.getBytes();
		}
	}

}
