package iudx.rs.proxy.apiserver;

import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import iudx.rs.proxy.apiserver.exceptions.DxRuntimeException;
import iudx.rs.proxy.apiserver.handlers.*;
import iudx.rs.proxy.apiserver.query.NGSILDQueryParams;
import iudx.rs.proxy.apiserver.query.QueryMapper;
import iudx.rs.proxy.apiserver.response.ResponseType;
import iudx.rs.proxy.apiserver.response.ResponseUtil;
import iudx.rs.proxy.apiserver.service.CatalogueService;
import iudx.rs.proxy.common.Api;
import iudx.rs.proxy.common.HttpStatusCode;
import iudx.rs.proxy.common.ResponseUrn;
import iudx.rs.proxy.database.DatabaseService;
import iudx.rs.proxy.databroker.DatabrokerService;
import iudx.rs.proxy.metering.MeteringService;
import iudx.rs.proxy.metering.util.ResponseBuilder;
import iudx.rs.proxy.optional.consentlogs.ConsentLoggingService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static iudx.rs.proxy.apiserver.response.ResponseUtil.generateResponse;
import static iudx.rs.proxy.apiserver.util.ApiServerConstants.*;
import static iudx.rs.proxy.apiserver.util.RequestType.ASYNC_SEARCH;
import static iudx.rs.proxy.apiserver.util.RequestType.ASYNC_STATUS;
import static iudx.rs.proxy.authenticator.Constants.*;
import static iudx.rs.proxy.common.Constants.*;
import static iudx.rs.proxy.common.HttpStatusCode.BAD_REQUEST;
import static iudx.rs.proxy.common.ResponseUrn.BACKING_SERVICE_FORMAT_URN;
import static iudx.rs.proxy.common.ResponseUrn.INVALID_PARAM_URN;


public class AsyncRestApi {
    private static final Logger LOGGER = LogManager.getLogger(AsyncRestApi.class);

    private final Vertx vertx;
    private final Router router;
    private final DatabrokerService databrokerService;
    private final CatalogueService catalogueService;
    private final DatabaseService databaseService;
    private final MeteringService meteringService;
    private final ConsentLoggingService consentLoggingService;

    private final ParamsValidator validator;
    private boolean isAdexInstance;
    private Api api;


    AsyncRestApi(Vertx vertx, Router router, Api api, JsonObject config) {
        this.vertx = vertx;
        this.router = router;
        this.databrokerService = DatabrokerService.createProxy(vertx, DATABROKER_SERVICE_ADDRESS);
        this.catalogueService = new CatalogueService(vertx, config);
        this.validator = new ParamsValidator(catalogueService);
        this.databaseService = DatabaseService.createProxy(vertx, DB_SERVICE_ADDRESS);
        this.meteringService = MeteringService.createProxy(vertx, METERING_SERVICE_ADDRESS);
        this.consentLoggingService = ConsentLoggingService.createProxy(vertx, CONSEENTLOG_SERVICE_ADDRESS);
        this.api = api;
        isAdexInstance = config.getBoolean("isAdexInstance");
    }

    Router init() {
        FailureHandler validationsFailureHandler = new FailureHandler();

        ValidationHandler asyncSearchValidation = new ValidationHandler(vertx, ASYNC_SEARCH);
        router
                .get(SEARCH)
                .handler(asyncSearchValidation)
                .handler(TokenDecodeHandler.create(vertx))
                .handler(new ConsentLogRequestHandler(vertx, isAdexInstance))
                .handler(AuthHandler.create(vertx, api))
                .handler(this::handleAsyncSearchRequest)
                .failureHandler(validationsFailureHandler);

        ValidationHandler asyncStatusValidation = new ValidationHandler(vertx, ASYNC_STATUS);
        router
                .get(STATUS)
                .handler(asyncStatusValidation)
                .handler(TokenDecodeHandler.create(vertx))
                .handler(new ConsentLogRequestHandler(vertx, isAdexInstance))
                .handler(AuthHandler.create(vertx, api))
                .handler(this::handleAsyncStatusRequest)
                .failureHandler(validationsFailureHandler);

        router.route().handler(context -> {
            context.addBodyEndHandler(endHandler -> {
                Future.future(future -> logConsentResponse(context));
            });
        });

        return this.router;
    }

    private Future<Void> logConsentResponse(RoutingContext routingContext) {
        LOGGER.info("logging consent log for response");
        Promise<Void> promise = Promise.promise();
        if (isAdexInstance) {
            String consentLog = null;
            switch (routingContext.response().getStatusCode()) {
                case 200:
                case 201: {
                    consentLog = "DATA_SENT";
                    break;
                }
                case 400:
                case 401:
                case 404: {
                    consentLog = "DATA_DENIED";
                    break;
                }
            }
            LOGGER.info("response ended : {}", routingContext.response());
            LOGGER.info("consent log : {}", consentLog);
            LOGGER.info("response code : {}", routingContext.response().getStatusCode());
            LOGGER.info("response body : {}", routingContext.response().bodyEndHandler(null));

            String finalConsentLog = consentLog;

            JsonObject logRequest = new JsonObject();
            logRequest.put("logType", finalConsentLog);

            Future<JsonObject> consentAuditLog = consentLoggingService.log(logRequest, routingContext.get("jwtData"));
            consentAuditLog.onSuccess(auditLogHandler -> promise.complete())
                    .onFailure(auditLogFailure -> {
                        LOGGER.error("failed info: {}", auditLogFailure.getMessage());
                        promise.fail(auditLogFailure);
                    });

        }


        return promise.future();
    }

    private void handleAsyncSearchRequest(RoutingContext routingContext) {
        LOGGER.trace("handleAsyncSearchRequest started");
        HttpServerRequest request = routingContext.request();
        HttpServerResponse response = routingContext.response();
        String instanceId = request.getHeader(HEADER_HOST);
        MultiMap params = getQueryParams(routingContext, response).get();

        if (containsTemporalParams(params) && !isValidTemporalQuery(params)) {
            routingContext.fail(
                    400, new DxRuntimeException(400, ResponseUrn.BAD_REQUEST_URN, "Invalid temporal query"));
            return;
        }

        if (containsGeoParams(params) && !isValidGeoQuery(params)) {
            routingContext.fail(
                    400, new DxRuntimeException(400, ResponseUrn.BAD_REQUEST_URN, "Invalid geo query"));
            return;
        }
        Future<Boolean> validationResult = validator.validate(params);

        validationResult.onComplete(
                validationHandler -> {
                    if (validationHandler.succeeded()) {
                        NGSILDQueryParams ngsildquery = new NGSILDQueryParams(params);
                        QueryMapper queryMapper = new QueryMapper();
                        JsonObject json = queryMapper.toJson(ngsildquery, true, true);
                        json.put(JSON_INSTANCEID, instanceId);
                        LOGGER.debug("Info: IUDX json query;" + json);
                        JsonObject requestBody = new JsonObject();
                        requestBody.put("ids", json.getJsonArray("id"));

                        if (routingContext.request().getHeader(HEADER_RESPONSE_FILE_FORMAT) != null) {
                            json.put("format", routingContext.request().getHeader(HEADER_RESPONSE_FILE_FORMAT));
                        }

                        Future<List<String>> filtersFuture =
                                catalogueService.getApplicableFilters(json.getJsonArray("id").getString(0));
                        filtersFuture.onComplete(
                                filtersHandler -> {
                                    if (filtersHandler.succeeded()) {
                                        json.put("applicableFilters", filtersHandler.result());
                                        LOGGER.debug("Async Json :" + json);
                                        adapterResponseForSearchQuery(routingContext, json, response, true);
                                    } else {
                                        LOGGER.error("catalogue item/group doesn't have filters.");
                                    }
                                });
                    } else if (validationHandler.failed()) {
                        LOGGER.error("Fail: Bad request;");
                        handleResponse(
                                response, BAD_REQUEST, INVALID_PARAM_URN, validationHandler.cause().getMessage());
                    }
                });


    }

    private void handleAsyncStatusRequest(RoutingContext routingContext) {
        LOGGER.trace("handleAsyncStatusRequest started");
        String sub = ((JsonObject) routingContext.data().get("authInfo")).getString(USER_ID);
        HttpServerRequest request = routingContext.request();
        HttpServerResponse response = routingContext.response();
        String searchId = request.getParam("searchId");
        StringBuilder query = new StringBuilder(SELECT_ASYNC_DETAILS
                .replace("$0", searchId));
        JsonObject queryJson = new JsonObject()
                .put("query", query);
        databaseService.executeQuery(
                queryJson,
                dbHandler -> {
                    if (dbHandler.succeeded()) {
                        JsonArray dbResult = dbHandler.result().getJsonArray("result");
                        if (dbResult.isEmpty() || dbResult.hasNull(0)) {
                            ResponseBuilder responseBuilder =
                                    new ResponseBuilder("failed")
                                            .setTypeAndTitle(400, ResponseUrn.BAD_REQUEST_URN.getUrn())
                                            .setMessage(
                                                    "Invalid searchId");
                            processBackendResponse(response, responseBuilder.getResponse().toString());
                            return;
                        }
                        JsonObject result = dbResult.getJsonObject(0);
                        if (result.getString("consumer_id").equalsIgnoreCase(sub)) {
                            JsonObject requestJson = new JsonObject();
                            requestJson.put("searchId", searchId);
                            requestJson.put("routingKey", result.getString("resource_id"));
                            adapterResponseForSearchQuery(routingContext, requestJson, response, false);
                        } else {
                            ResponseBuilder responseBuilder =
                                    new ResponseBuilder("failed")
                                            .setTypeAndTitle(400, ResponseUrn.BAD_REQUEST_URN.getUrn())
                                            .setMessage(
                                                    "Please use same user token to check status as "
                                                            + "used while calling search API");
                            processBackendResponse(response, responseBuilder.getResponse().toString());
                        }
                    } else {
                        ResponseBuilder responseBuilder =
                                new ResponseBuilder("failed")
                                        .setTypeAndTitle(400, ResponseUrn.BAD_REQUEST_URN.getUrn())
                                        .setMessage(
                                                "invalid searchId/serachId not present in db");
                        processBackendResponse(response, responseBuilder.getResponse().toString());
                    }
                });


    }

    private Optional<MultiMap> getQueryParams(
            RoutingContext routingContext, HttpServerResponse response) {
        MultiMap queryParams = null;
        try {
            queryParams = MultiMap.caseInsensitiveMultiMap();
            // Internally + sign is dropped and treated as space, replacing + with %2B do the trick
            String uri = routingContext.request().uri().replaceAll("\\+", "%2B");
            Map<String, List<String>> decodedParams =
                    new QueryStringDecoder(uri, HttpConstants.DEFAULT_CHARSET, true, 1024, true).parameters();
            for (Map.Entry<String, List<String>> entry : decodedParams.entrySet()) {
                LOGGER.debug("Info: param :" + entry.getKey() + " value : " + entry.getValue());
                queryParams.add(entry.getKey(), entry.getValue());
            }
        } catch (IllegalArgumentException ex) {
            response
                    .putHeader(CONTENT_TYPE, APPLICATION_JSON)
                    .setStatusCode(BAD_REQUEST.getValue())
                    .end(generateResponse(BAD_REQUEST, INVALID_PARAM_URN).toString());
        }
        return Optional.of(queryParams);
    }


    private boolean isValidTemporalQuery(MultiMap params) {
        return params.contains(JSON_TIMEREL)
                && params.contains(JSON_TIME)
                && params.contains(JSON_ENDTIME);
    }

    private boolean containsTemporalParams(MultiMap params) {
        return params.contains(JSON_TIMEREL)
                || params.contains(JSON_TIME)
                || params.contains(JSON_ENDTIME);
    }

    private boolean isValidGeoQuery(MultiMap params) {
        return params.contains(JSON_GEOPROPERTY)
                && params.contains(JSON_GEOREL)
                && params.contains(JSON_GEOMETRY)
                && params.contains(JSON_COORDINATES);
    }

    private boolean containsGeoParams(MultiMap params) {
        return params.contains(JSON_GEOPROPERTY)
                || params.contains(JSON_GEOREL)
                || params.contains(JSON_GEOMETRY)
                || params.contains(JSON_COORDINATES);
    }

    private void adapterResponseForSearchQuery(RoutingContext context, JsonObject json,
                                               HttpServerResponse response, boolean isDbOprationRequired) {
        JsonObject authInfo = (JsonObject) context.data().get("authInfo");
        json.put(API, authInfo.getValue(API_ENDPOINT));
        String publicKey = context.request().getHeader(HEADER_PUBLIC_KEY);
        json.put(HEADER_PUBLIC_KEY, publicKey);
        if (isAdexInstance) {
            json.put("ppbNumber", extractPPBNo(authInfo)); // this is exclusive for ADeX deployment.
        }
        databrokerService.executeAdapterQueryRPC(json, handler -> {
            if (handler.succeeded()) {
                JsonObject adapterResponse = handler.result();
                int status = adapterResponse.containsKey("statusCode") ? adapterResponse.getInteger("statusCode") : 400;
                response.putHeader(CONTENT_TYPE, APPLICATION_JSON);
                response.setStatusCode(status);
                if (status == 201 && isDbOprationRequired) {
                    LOGGER.info("Success: adapter call Success with {}", status);
                    String resource_Id = json.getJsonArray("id").getString(0);
                    StringBuilder insertQuery = new StringBuilder(ISERT_ASYNC_REQUEST_DETAIL_SQL
                            .replace("$0", adapterResponse.getString("searchId"))
                            .replace("$1", authInfo.getString(USER_ID))
                            .replace("$2", resource_Id)
                            .replace("$3", json.toString()));

                    JsonObject queryJson = new JsonObject()
                            .put("query", insertQuery);
                    databaseService.executeQuery(
                            queryJson,
                            dbHandler -> {
                                if (dbHandler.succeeded()) {
                                    JsonObject resultJson = dbHandler.result();
                                    if (resultJson.getString((JSON_TYPE)).equalsIgnoreCase(ResponseUrn.SUCCESS_URN.getUrn())) {
                                        adapterResponse.put(JSON_TYPE, ResponseUrn.SUCCESS_URN.getUrn());
                                        adapterResponse.put(JSON_TITLE, "query submitted successfully");
                                        JsonObject userResponse = new JsonObject();
                                        userResponse.put(JSON_TYPE, ResponseUrn.SUCCESS_URN.getUrn());
                                        userResponse.put(JSON_TITLE, "query submitted successfully");
                                        userResponse.put("results", new JsonArray().add(new JsonObject().put("searchId", adapterResponse.getString("searchId"))));
                                        response.end(userResponse.toString());
                                        context.data().put(RESPONSE_SIZE, response.bytesWritten());
                                    }
                                } else {
                                    LOGGER.error("Failed to insert into db ");
                                    ResponseBuilder responseBuilder =
                                            new ResponseBuilder("failed")
                                                    .setTypeAndTitle(ResponseType.InternalError.getCode(), ResponseType.InternalError.getMessage())
                                                    .setMessage("Fail to generate searchID");
                                    processBackendResponse(response, responseBuilder.getResponse().toString());
                                }
                            });
                } else if (status == 200 && !isDbOprationRequired) {
                    LOGGER.info("Success: adapter call Success with {}", status);
                    JsonObject userResponse = new JsonObject();
                    userResponse.put(JSON_TYPE, ResponseUrn.SUCCESS_URN.getUrn());
                    userResponse.put(JSON_TITLE, "Success");
                    String userId = authInfo.getString(USER_ID);
                    // Extract the "results" section from adapter response
                    JsonArray results = adapterResponse.getJsonArray("results");
                    JsonObject resultsObject = results.getJsonObject(0);
                    // Add user_id field after searchId in each object within the results array
                    resultsObject.put("userId", userId);
                    userResponse.put("results", results);
                    response.end(userResponse.toString());
                    context.data().put(RESPONSE_SIZE, response.bytesWritten());
                    Future.future(fu -> updateAuditTable(context));

                } else {
                    LOGGER.info("Success: adapter call success with {}", status);
                    HttpStatusCode responseUrn = HttpStatusCode.getByValue(status);
                    String adapterFailureMessage = adapterResponse.getString("details");
                    JsonObject responseJson = ResponseUtil.generateResponse(responseUrn, adapterFailureMessage);
                    response.end(responseJson.toString());
                }

            } else {
                LOGGER.error("Failure: Adapter Search Fail");
                response.putHeader(CONTENT_TYPE, APPLICATION_JSON)
                        .setStatusCode(400)
                        .end(handler.cause().getMessage());
            }
        });
        context.next();
    }

    public String extractPPBNo(JsonObject authInfo) {
        LOGGER.debug("auth info :{}", authInfo);
        if (authInfo == null) return "";
        JsonObject cons = authInfo.getJsonObject(JSON_CONS);
        if (cons == null) return "";
        String ppbno = cons.getString("ppbNumber");
        if (ppbno == null) return "";
        return ppbno;
    }

    private void processBackendResponse(HttpServerResponse response, String failureMessage) {
        LOGGER.debug("Info : " + failureMessage);
        try {
            JsonObject json = new JsonObject(failureMessage);
            int type = json.getInteger(JSON_TYPE);
            HttpStatusCode status = HttpStatusCode.getByValue(type);
            String urnTitle = json.getString(JSON_TITLE);
            String detail = json.getString(JSON_DETAIL);
            ResponseUrn urn;
            if (urnTitle != null) {
                urn = ResponseUrn.fromCode(urnTitle);
            } else {
                urn = ResponseUrn.fromCode(type + "");
            }
            // return urn in body
            response
                    .putHeader(CONTENT_TYPE, APPLICATION_JSON)
                    .setStatusCode(type)
                    .end(generateResponse(status, urn, detail).toString());
        } catch (DecodeException ex) {
            LOGGER.error("ERROR : Expecting Json from backend service [ jsonFormattingException ]");
            handleResponse(response, BAD_REQUEST, BACKING_SERVICE_FORMAT_URN);
        }
    }

    private void handleResponse(HttpServerResponse response, HttpStatusCode code, ResponseUrn urn) {
        handleResponse(response, code, urn, code.getDescription());
    }

    private void handleResponse(
            HttpServerResponse response, HttpStatusCode statusCode, ResponseUrn urn, String message) {
        response
                .putHeader(CONTENT_TYPE, APPLICATION_JSON)
                .setStatusCode(statusCode.getValue())
                .end(generateResponse(statusCode, urn, message).toString());
    }

    private void updateAuditTable(RoutingContext context) {
        Promise<Void> promise = Promise.promise();
        JsonObject authInfo = (JsonObject) context.data().get("authInfo");

        JsonObject request = new JsonObject();

        ZonedDateTime zst = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
        long time = zst.toInstant().toEpochMilli();
        String isoTime = zst.truncatedTo(ChronoUnit.SECONDS).toString();
        String resourceid = authInfo.getString(ID);
        String role = authInfo.getString(ROLE);
        String drl = authInfo.getString(DRL);
        if (role.equalsIgnoreCase("delegate") && drl != null) {
            request.put(DELEGATOR_ID, authInfo.getString(DID));
        } else {
            request.put(DELEGATOR_ID, authInfo.getString(USER_ID));
        }
        JsonObject jsonObject = CatalogueService.getCatalogueItemJson(resourceid);
        String providerID = jsonObject.getString("provider");
        String type =
                jsonObject.containsKey(RESOURCE_GROUP) ? "RESOURCE" : "RESOURCE_GROUP";
        String resourceGroup =
                jsonObject.containsKey(RESOURCE_GROUP)
                        ? jsonObject.getString(RESOURCE_GROUP)
                        : jsonObject.getString(ID);
        request.put(RESOURCE_GROUP, resourceGroup);
        request.put(TYPE_KEY, type);
        request.put(EPOCH_TIME, time);
        request.put(ISO_TIME, isoTime);
        request.put(USER_ID, authInfo.getValue(USER_ID));
        request.put(ID, authInfo.getValue(ID));
        request.put(API, authInfo.getValue(API_ENDPOINT));
        request.put(RESPONSE_SIZE, context.data().get(RESPONSE_SIZE));
        request.put(PROVIDER_ID, providerID);

        meteringService.insertMeteringValuesInRMQ(
                request,
                handler -> {
                    if (handler.succeeded()) {
                        LOGGER.info("message published in RMQ.");
                        promise.complete();
                    } else {
                        LOGGER.error("failed to publish message in RMQ.");
                        promise.complete();
                    }
                });

        promise.future();
    }

}
