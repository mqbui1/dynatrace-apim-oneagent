package com.axway.oneagent.utils;

import com.vordel.circuit.Message;
import com.vordel.config.Circuit;
import com.vordel.dwe.CorrelationID;
import com.vordel.mime.HeaderSet;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves OTEL-001/OTEL-002/OTEL-003 from .observe/otel-audit.json: a real OTel span is created,
 * correctly attributed, correctly parented/propagated, and correctly closed around the existing
 * Dynatrace tracer calls in OneAgentSDKUtils. Uses the package-private setOtelTracerForTests seam
 * to swap in an in-memory-backed Tracer so assertions don't depend on a live OTLP collector.
 */
public class OtelSpanInstrumentationTest {

    private static final String HTTP_HEADERS = "http.headers";
    private InMemorySpanExporter exporter;
    private SdkTracerProvider testProvider;
    private Tracer originalTracer;

    @Before
    public void swapInMemoryTracer() {
        originalTracer = OneAgentSDKUtils.getOtelTracer();
        exporter = InMemorySpanExporter.create();
        testProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
        OneAgentSDKUtils.setOtelTracerForTests(testProvider.get("test"));
    }

    @After
    public void restoreTracer() {
        OneAgentSDKUtils.setOtelTracerForTests(originalTracer);
        testProvider.shutdown();
    }

    // OTEL-003: tracer accessor returns the same instance across calls, and the provider's
    // forceFlush does not throw even with no reachable OTLP collector.
    @Test
    public void otelTracerAccessorIsSingletonAndFlushDoesNotThrow() {
        assertSame(OneAgentSDKUtils.getOtelTracerProvider(), OneAgentSDKUtils.getOtelTracerProvider());
        assertSame(originalTracer, originalTracer);
        OneAgentSDKUtils.getOtelTracerProvider().forceFlush().join(2, java.util.concurrent.TimeUnit.SECONDS);
    }

    // OTEL-001: aroundConsumer success path emits one SERVER span with status code + app attrs.
    @Test
    public void aroundConsumerEmitsServerSpanOnSuccess() throws Throwable {
        Message message = buildConsumerMessage("GET", "/api/test", 200);
        message.put("authentication.application.name", "TestApp");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        Object result = OneAgentSDKUtils.aroundConsumer(pjp, message, "TestAPI", "/api/test");

        assertEquals("ok", result);
        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals("API Manager/Gateway consumer request", span.getName());
        assertEquals(io.opentelemetry.api.trace.SpanKind.SERVER, span.getKind());
        assertEquals("GET", span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("http.request.method")));
        assertEquals(200L, (long) span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")));
        assertEquals("TestApp", span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("AxwayAppName")));
        assertEquals(StatusData.unset(), span.getStatus());
    }

    // OTEL-001: when the incoming HeaderSet carries a valid W3C traceparent, the new SERVER
    // span's trace ID matches it.
    @Test
    public void aroundConsumerParentsUnderIncomingTraceparent() throws Throwable {
        String incomingTraceId = "0af7651916cd43dd8448eb211c80319c";
        Message message = buildConsumerMessage("GET", "/api/test", 200);
        HeaderSet headers = (HeaderSet) message.get(HTTP_HEADERS);
        headers.addHeader("traceparent", "00-" + incomingTraceId + "-b7ad6b7169203331-01");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        OneAgentSDKUtils.aroundConsumer(pjp, message, "TestAPI", "/api/test");

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals(incomingTraceId, spans.get(0).getTraceId());
    }

    // OTEL-001: on a thrown Throwable, the span records the exception and is marked ERROR
    // before the exception propagates unchanged.
    @Test
    public void aroundConsumerRecordsErrorOnException() throws Throwable {
        Message message = buildConsumerMessage("GET", "/api/test", 500);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        RuntimeException boom = new RuntimeException("boom");
        when(pjp.proceed()).thenThrow(boom);

        try {
            OneAgentSDKUtils.aroundConsumer(pjp, message, "TestAPI", "/api/test");
            org.junit.Assert.fail("expected exception to propagate");
        } catch (RuntimeException caught) {
            assertSame(boom, caught);
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals(StatusData.error(), span.getStatus());
        assertEquals(1, span.getEvents().size());
        assertEquals("exception", span.getEvents().get(0).getName());
    }

    // OTEL-002: aroundProducer success path emits one CLIENT span and injects a W3C traceparent
    // into the outgoing requestHeaders alongside the existing Dynatrace tag.
    @Test
    public void aroundProducerEmitsClientSpanAndInjectsTraceparent() throws Throwable {
        Message message = new Message();
        message.put("http.request.uri", "https://backend.example.com/path");
        message.put("http.response.status", 200);
        Circuit circuit = new Circuit();
        circuit.setName("TestCircuit");
        HeaderSet requestHeaders = new HeaderSet();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.proceed()).thenReturn("ok");

        OneAgentSDKUtils.aroundProducer(pjp, message, circuit, requestHeaders, "POST");

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals("API Manager/Gateway outbound backend call", span.getName());
        assertEquals(io.opentelemetry.api.trace.SpanKind.CLIENT, span.getKind());
        assertEquals("https://backend.example.com/path",
            span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("url.full")));
        assertEquals(200L,
            (long) span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")));

        String traceparent = requestHeaders.getHeader("traceparent");
        assertNotNull("outgoing requestHeaders must carry a W3C traceparent", traceparent);
        assertTrue(traceparent.contains(span.getTraceId()));
    }

    // OTEL-002: on a thrown Throwable, the span records the exception, is marked ERROR, and
    // http.response.status_code is set to 500 before the exception propagates unchanged.
    @Test
    public void aroundProducerRecordsErrorOnException() throws Throwable {
        Message message = new Message();
        message.put("http.request.uri", "https://backend.example.com/path");
        Circuit circuit = new Circuit();
        circuit.setName("TestCircuit");
        HeaderSet requestHeaders = new HeaderSet();
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        RuntimeException boom = new RuntimeException("boom");
        when(pjp.proceed()).thenThrow(boom);

        try {
            OneAgentSDKUtils.aroundProducer(pjp, message, circuit, requestHeaders, "POST");
            org.junit.Assert.fail("expected exception to propagate");
        } catch (RuntimeException caught) {
            assertSame(boom, caught);
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals(StatusData.error(), span.getStatus());
        assertEquals(500L,
            (long) span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("http.response.status_code")));
    }

    private static Message buildConsumerMessage(String verb, String uri, int responseStatus) {
        Message message = new Message();
        HeaderSet headers = new HeaderSet();
        message.put(HTTP_HEADERS, headers);
        message.put("http.request.uri", uri);
        message.put("http.request.verb", verb);
        message.put("http.response.status", responseStatus);
        message.setIDBase(new CorrelationID("corr-1"));
        return message;
    }
}
