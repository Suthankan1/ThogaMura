package lk.thogamura.gateway.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "thogamura.access.enabled", havingValue = "true")
final class WorkspaceHttpBoundary extends OncePerRequestFilter {
    private static final Set<String> DIAGNOSTICS = Set.of("/api/v1/meta", "/api/v1/ping", "/api/v1/network-status", "/api/v1/events");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/v2/")) response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (path.startsWith("/api/v1/") && !DIAGNOSTICS.contains(path)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("""
                    {"code":"FORBIDDEN","message":"Use the authenticated branch workspace for inventory."}
                    """);
            return;
        }
        chain.doFilter(request, response);
    }
}
