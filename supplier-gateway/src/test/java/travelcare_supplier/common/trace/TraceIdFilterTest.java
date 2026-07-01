package travelcare_supplier.common.trace;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class TraceIdFilterTest {

    @Test
    void reusesInboundTraceIdAndClearsThreadLocalAfterRequest() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TraceIdFilter.TRACE_ID_HEADER, "trace-123");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(TraceIdFilter.currentTraceId()).isEqualTo("trace-123"));

        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isEqualTo("trace-123");
        assertThat(TraceIdFilter.currentTraceId()).isNull();
    }

    @Test
    void generatesTraceIdWhenInboundHeaderIsMissing() throws Exception {
        TraceIdFilter filter = new TraceIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) ->
                assertThat(TraceIdFilter.currentTraceId()).isNotBlank();

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader(TraceIdFilter.TRACE_ID_HEADER)).isNotBlank();
    }
}
