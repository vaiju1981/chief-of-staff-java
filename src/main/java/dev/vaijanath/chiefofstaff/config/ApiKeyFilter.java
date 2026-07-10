package dev.vaijanath.chiefofstaff.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Optional bearer-token gate. Active only when {@code COS_API_KEY} is set: then every request except
 * {@code /health} and {@code /actuator/**} must carry {@code Authorization: Bearer <key>}. Intended for
 * exposing the server beyond localhost, since the MCP filesystem tools can read the machine's files.
 *
 * <p>Inactive (pass-through) by default, so local dev and Open WebUI on the same box are unaffected.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class ApiKeyFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyFilter.class);

    private final CosProperties props;

    ApiKeyFilter(CosProperties props) {
        this.props = props;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!props.hasApiKey()) {
            chain.doFilter(request, response);
            return;
        }
        HttpServletRequest http = (HttpServletRequest) request;
        String path = http.getServletPath();
        if ("/health".equals(path) || path.startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }
        String auth = http.getHeader("Authorization");
        String expected = "Bearer " + props.apiKey();
        if (auth != null && auth.equals(expected)) {
            chain.doFilter(request, response);
        } else {
            log.warn("[auth] rejected {} {} (missing/invalid API key)", http.getMethod(), path);
            ((HttpServletResponse) response).sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
        }
    }
}
