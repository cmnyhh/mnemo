package com.hs.memory.api;

import com.hs.memory.token.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer 鉴权。**只认数据库令牌**（控制台「令牌」页签发），没有额外的固定令牌。
 *
 * <p>租户取自令牌行（{@code tenant_id}）。注意它只是「签发时把配置抄了一份」：
 * 真正决定租户的是 {@code MEMORY_TENANT}，所以删掉令牌不会丢租户，重新签发仍回到同一个。
 * 当前是单租户部署（见 {@code SchemaInitializer} 启动时的租户一致性检查）；要做多租户，
 * 需要把租户做成独立实体、签发时显式选择，而不是继续从配置里抄。
 *
 * <p>同时把令牌 id 放进 request attribute：它只用于给写入的记录做来源标记
 * （{@code memory_item.token_id}），不参与鉴权判断。
 *
 * <p>令牌不存在 / 已撤销 / 已过期统一 401，共用一句错误 —— 区分这几种等于告诉
 * 攻击者哪一段前缀猜对了。
 *
 * <p>库不可用时返回 503 而不是 401：401 会让客户端以为令牌失效、去换一把新的，
 * 而实际问题在存储层，换令牌解决不了。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiTokenFilter.class);

    private final TokenService tokens;

    public ApiTokenFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        // 控制台是另一套鉴权（口令 + 会话），这里必须放行；否则登录页都加载不出来。
        if ("/v1/memory/health".equals(path) || "/v1/memory/readyz".equals(path)
                || "/".equals(path) || "/index.html".equals(path) || "/console".equals(path)
                || "/favicon.ico".equals(path)
                // 前端产物：Vite 输出带 hash 的文件名，无法逐个列举
                || path.startsWith("/assets/")
                || path.startsWith("/admin/")) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorized(response, "缺少 Bearer token");
            return;
        }
        String token = header.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            unauthorized(response, "token 无效或已失效");
            return;
        }

        Optional<TokenService.TokenRow> row;
        try {
            row = tokens.verify(token);
        } catch (DataAccessException e) {
            log.warn("令牌校验查询数据库失败：{}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"令牌存储暂不可用\"}");
            return;
        }
        if (row.isEmpty()) {
            unauthorized(response, "token 无效或已失效");
            return;
        }
        request.setAttribute("tenant", row.get().tenant());
        request.setAttribute("tokenId", row.get().id().toString());
        chain.doFilter(request, response);
    }

    private static void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
