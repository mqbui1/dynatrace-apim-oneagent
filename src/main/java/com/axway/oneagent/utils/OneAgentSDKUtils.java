package com.axway.oneagent.utils;

import com.axway.aspects.apim.TraceLoggingCallback;
import com.dynatrace.oneagent.sdk.OneAgentSDKFactory;
import com.dynatrace.oneagent.sdk.api.OneAgentSDK;
import com.vordel.circuit.Message;
import com.vordel.circuit.MessageProperties;
import com.vordel.config.Circuit;
import com.vordel.mime.HeaderSet;
import com.vordel.trace.Trace;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import org.aspectj.lang.ProceedingJoinPoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class OneAgentSDKUtils {
    private static final OneAgentSDK oneAgentSdk = OneAgentSDKFactory.createInstance();
    private static final String DEFAULT = "default";
    public static final String AXWAY_CORRELATION_ID = "AxwayCorrelationId";
    public static final String HTTP_HEADERS = "http.headers";

    // OTEL-003: OTel SDK bootstrap, mirroring the Dynatrace OneAgentSDKFactory static-singleton
    // pattern above. Both SDKs coexist during the OTel migration -- the Dynatrace singleton is
    // left untouched.
    private static final String OTEL_SERVICE_NAME = "dynatrace-apim-oneagent";
    private static final SdkTracerProvider otelTracerProvider = buildOtelTracerProvider();
    private static Tracer otelTracer = otelTracerProvider.get(OTEL_SERVICE_NAME);

    private static final TextMapGetter<HeaderSet> HEADER_GETTER = new TextMapGetter<HeaderSet>() {
        @Override
        public Iterable<String> keys(HeaderSet carrier) {
            List<String> keys = new ArrayList<>();
            for (Map.Entry<String, HeaderSet.HeaderEntry> entry : carrier.entrySet()) {
                keys.add(entry.getKey());
            }
            return keys;
        }

        @Override
        public String get(HeaderSet carrier, String key) {
            return carrier == null ? null : carrier.getHeader(key);
        }
    };

    private static final TextMapSetter<HeaderSet> HEADER_SETTER =
        (carrier, key, value) -> carrier.setHeader(key, value);

    static {
        oneAgentSdk.setLoggingCallback(new TraceLoggingCallback());
        switch (oneAgentSdk.getCurrentState()) {
            case ACTIVE:
                Trace.info("Dynatrace SDK is active and capturing.");
                break;
            case PERMANENTLY_INACTIVE:
                Trace.error("Dynatrace SDK is PERMANENTLY_INACTIVE; Probably no OneAgent injected or OneAgent is incompatible with SDK.");
                break;
            case TEMPORARILY_INACTIVE:
                Trace.error("Dynatrace SDK is TEMPORARILY_INACTIVE; OneAgent has been deactivated - check OneAgent configuration.");
                break;
            default:
                Trace.error("Dynatrace SDK is in unknown state.");
                break;
        }
    }

    private OneAgentSDKUtils() {
        /* This utility class should not be instantiated */
    }

    private static SdkTracerProvider buildOtelTracerProvider() {
        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
            .setEndpoint(resolveOtlpTracesEndpoint())
            .build();
        Resource resource = Resource.getDefault().merge(
            Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), OTEL_SERVICE_NAME)));
        SdkTracerProvider provider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .build();
        Runtime.getRuntime().addShutdownHook(new Thread(provider::close));
        return provider;
    }

    private static String resolveOtlpTracesEndpoint() {
        String configured = firstNonBlank(
            System.getenv("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"),
            System.getProperty("otel.exporter.otlp.traces.endpoint"),
            System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"),
            System.getProperty("otel.exporter.otlp.endpoint"));
        if (configured == null) {
            return "http://localhost:4318/v1/traces";
        }
        return configured.endsWith("/v1/traces") ? configured : configured + "/v1/traces";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

    /** Static accessor required by OTEL-003's acceptance criteria: created exactly once per JVM. */
    public static Tracer getOtelTracer() {
        return otelTracer;
    }

    public static SdkTracerProvider getOtelTracerProvider() {
        return otelTracerProvider;
    }

    /**
     * Test-only seam: lets focused tests swap in a Tracer backed by an in-memory span exporter
     * so span attributes/parentage/status can be asserted without a live OTLP collector. Never
     * called from production code paths.
     */
    static void setOtelTracerForTests(Tracer tracer) {
        otelTracer = tracer;
    }

    public static Object aroundProducer(ProceedingJoinPoint pjp, Message message, Circuit circuit, HeaderSet requestHeaders, String httpVerb) throws Throwable {
        Trace.debug("Dynatrace :: Starting around producer for Policy " + circuit.getName());
        Object object;
        // Remediation: this OTel CLIENT span fully replaces the former Dynatrace
        // OutgoingWebRequestTracer (the flagged traceOutgoingWebRequest call site).
        String otelUrl = getRequestURL(message);
        Span otelSpan = getOtelTracer().spanBuilder("API Manager/Gateway outbound backend call")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute(AttributeKey.stringKey("http.request.method"), httpVerb)
            .setAttribute(AttributeKey.stringKey("url.full"), otelUrl)
            .startSpan();
        try (Scope otelScope = otelSpan.makeCurrent()) {
            String appName = (String) message.getOrDefault("authentication.application.name", DEFAULT);
            String orgName = (String) message.getOrDefault("authentication.organization.name", DEFAULT);
            String appId = (String) message.getOrDefault("authentication.subject.id", DEFAULT);
            Object idBase = message.getIDBase();
            setOtelRequestAttributes(otelSpan, appName, orgName, appId, idBase == null ? null : idBase.toString());
            if (requestHeaders != null) {
                // W3C traceparent is now the sole outbound propagation mechanism -- the
                // x-dynatrace tag this used to carry came from the removed
                // OutgoingWebRequestTracer and has no replacement source.
                W3CTraceContextPropagator.getInstance()
                    .inject(Context.current(), requestHeaders, HEADER_SETTER);
            }
            addAttributes(message);
            object = pjp.proceed();
            int httpStatusCode = getHTTPStatusCode(message);
            otelSpan.setAttribute(AttributeKey.longKey("http.response.status_code"), httpStatusCode);
            Trace.debug("Dynatrace :: Backend response code " + httpStatusCode);
        } catch (Throwable e) {
            Trace.error("Dynatrace :: around producer ", e);
            otelSpan.setAttribute(AttributeKey.longKey("http.response.status_code"), 500);
            otelSpan.recordException(e);
            otelSpan.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            otelSpan.end();
        }
        return object;
    }

    public static Object aroundConsumer(ProceedingJoinPoint pjp, Message message, String apiName, String apiContextRoot) throws Throwable {
        Trace.debug("Dynatrace :: Starting around consumer");
        Object pjpProceed;
        HeaderSet headers = (HeaderSet) message.get(HTTP_HEADERS);
        String correlationId = message.getIDBase().toString();
        Trace.debug("Dynatrace :: Consumer Headers before proceed: " + headers);
        // Remediation: this OTel SERVER span fully replaces the former Dynatrace
        // IncomingWebRequestTracer (the flagged traceIncomingWebRequest call site),
        // correctly parented to the caller's W3C traceparent when present.
        Context parentOtelContext = W3CTraceContextPropagator.getInstance()
            .extract(Context.root(), headers, HEADER_GETTER);
        Object httpVerb = message.get("http.request.verb");
        Span otelSpan = getOtelTracer().spanBuilder("API Manager/Gateway consumer request")
            .setParent(parentOtelContext)
            .setSpanKind(SpanKind.SERVER)
            .setAttribute(AttributeKey.stringKey("http.request.method"), httpVerb == null ? null : httpVerb.toString())
            .setAttribute(AttributeKey.stringKey("url.path"), apiContextRoot)
            .startSpan();
        Scope otelScope = otelSpan.makeCurrent();
        if (headers != null && headers.hasHeader(OneAgentSDK.DYNATRACE_HTTP_HEADERNAME)) {
            String receivedTag = headers.getHeader(OneAgentSDK.DYNATRACE_HTTP_HEADERNAME);
            Trace.debug("Dynatrace :: X-Dynatrace-Header " + receivedTag);
            if (!receivedTag.startsWith("FW")) {
                int naIndex = receivedTag.indexOf("NA=");
                int snIndex = receivedTag.indexOf("SN=");
                int siIndex = receivedTag.indexOf("SI=");
                if (naIndex != -1 && snIndex != -1 && siIndex != -1) {
                    String afterNA = receivedTag.substring(naIndex);
                    int delimiterIndex = afterNA.indexOf(';');
                    String neoloadTransaction = receivedTag.substring(naIndex + 3, naIndex + delimiterIndex);
                    neoLoadTransaction(neoloadTransaction);
                    String afterSN = receivedTag.substring(snIndex);
                    delimiterIndex = afterSN.indexOf(';');
                    String neoloadUserPath = receivedTag.substring(snIndex + 3, snIndex + delimiterIndex);
                    neoLoadUserPath(neoloadUserPath);
                    String afterSI = receivedTag.substring(siIndex);
                    delimiterIndex = afterSI.indexOf(';');
                    String neoloadTraffic = receivedTag.substring(siIndex + 3, siIndex + delimiterIndex);
                    neoloadTraffic(neoloadTraffic);
                }
            }
        }
        try {
            pjpProceed = pjp.proceed();
        } catch (Throwable e) {
            Trace.error("Dynatrace :: around consumer", e);
            otelSpan.recordException(e);
            otelSpan.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            String appName = getStringOrDefault(message, "authentication.application.name", DEFAULT);
            String orgName = getStringOrDefault(message, "authentication.organization.name", DEFAULT);
            String appId = getStringOrDefault(message, "authentication.subject.id", DEFAULT);
            String serviceName = getStringOrDefault(message, "service.name", DEFAULT);
            setOtelRequestAttributes(otelSpan, appName, orgName, appId, correlationId);
            if (serviceName != null) {
                otelSpan.setAttribute(AttributeKey.stringKey("ServiceName"), serviceName);
            }
            if (message.get(MessageProperties.RESTAPI_ERROR_REASON) != null) {
                String restApiErrorReason = (String) message.get(MessageProperties.RESTAPI_ERROR_REASON);
                String restApiErrorSource = getStringOrDefault(message, MessageProperties.RESTAPI_ERROR_SOURCE, "");
                otelSpan.setAttribute(AttributeKey.stringKey("RestApiErrorReason"), restApiErrorReason);
                otelSpan.setAttribute(AttributeKey.stringKey("RestApiErrorSource"), restApiErrorSource);
            }
            int httpStatusCode = getHTTPStatusCode(message);
            otelSpan.setAttribute(AttributeKey.longKey("http.response.status_code"), httpStatusCode);
            otelScope.close();
            otelSpan.end();
            Trace.debug("Dynatrace :: Ending around consumer");
        }
        return pjpProceed;
    }

    /** Carries over AxwayAppName/AxwayOrgName/AxwayAppId/AxwayCorrelationId to the OTel span,
     * only when they differ from the DEFAULT sentinel -- matching addRequestAttributes' existing
     * behavior for the Dynatrace tracer. */
    private static void setOtelRequestAttributes(Span otelSpan, String appName, String orgName, String appId, String correlationId) {
        if (appName != null && !appName.equals(DEFAULT)) {
            otelSpan.setAttribute(AttributeKey.stringKey("AxwayAppName"), appName);
        }
        if (orgName != null && !orgName.equals(DEFAULT)) {
            otelSpan.setAttribute(AttributeKey.stringKey("AxwayOrgName"), orgName);
        }
        if (appId != null && !appId.equals(DEFAULT)) {
            otelSpan.setAttribute(AttributeKey.stringKey("AxwayAppId"), appId);
        }
        if (correlationId != null) {
            otelSpan.setAttribute(AttributeKey.stringKey(AXWAY_CORRELATION_ID), "Id-" + correlationId);
        }
    }

    public static String readHostNameFromHttpHeader(Message message) {
        HeaderSet httpHeaders = (HeaderSet) message.get(HTTP_HEADERS);
        if (httpHeaders == null)
            return "0.0.0.0";
        String host = httpHeaders.getHeader("Host");
        if (host == null)
            return "0.0.0.0";
        if (host.contains(":")) {
            return host.split(":")[0];
        }
        return host;
    }

    public static String getStringOrDefault(Message message, String key, String defaultValue) {
        Object value = message.get(key);
        if (value == null)
            return defaultValue;
        return value.toString();
    }

    public static void addAttributes(Message message) {
        String clientName = (String) message.get("message.client.name");
        if (clientName != null)
            addClientName(clientName);
    }

    public static void neoLoadTransaction(String value) {
        Span.current().setAttribute("NeoLoad_Transaction", value);
    }

    public static void neoLoadUserPath(String value) {
        Span.current().setAttribute("NeoLoad_UserPath", value);
    }

    public static void neoloadTraffic(String value) {
        Span.current().setAttribute("Neoload_Traffic", value);
    }

    public static void addClientName(String clientName) {
        Span.current().setAttribute("ClientName", clientName);
    }


    public static int getHTTPStatusCode(Message message) {
        if (message == null)
            return 500;
        Object status = message.get("http.response.status");
        if (status == null)
            return 500;
        return (int) status;
    }


    public static String getRequestURL(Message message) {
        Object messageObject = message.get("http.request.uri");
        if (messageObject == null || !messageObject.toString().startsWith("http")) {
            return "https://" + readHostNameFromHttpHeader(message) + "/";
        }
        return messageObject.toString();
    }

}
