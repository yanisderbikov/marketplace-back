package ru.marketplace.config;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.marketplace.auth.JwtTokenService;
import ru.marketplace.auth.SuperAdminResolver;
import ru.marketplace.repository.UserRepository;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Единая точка разбора токена: фильтр только опознаёт, кто пришёл, и кладёт роли в контекст —
 * решение «пускать или нет» принимает WebSecurityConfig.
 * Principal — нормализованная почта (её читает CurrentMerchant).
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String MERCHANT_AUTHORITY = "ROLE_MERCHANT";
    public static final String SUPER_ADMIN_AUTHORITY = "ROLE_SUPER_ADMIN";

    private static final String AUTH_HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";
    /**
     * Сколько помним, что пользователь с такой почтой есть в БД: каждый запрос админки
     * иначе ходил бы в users. Кэшируем только найденных — только что зарегистрированный
     * мерчант не должен минуту ловить 401 из-за запомненного «нет такого».
     */
    private static final Duration USER_CACHE_TTL = Duration.ofMinutes(1);

    private final JwtTokenService jwtTokenService;
    private final SuperAdminResolver superAdminResolver;
    private final UserRepository userRepository;
    private final Cache<String, Boolean> knownUsers = CacheBuilder.newBuilder()
            .expireAfterWrite(USER_CACHE_TTL)
            .maximumSize(10_000)
            .build();

    public JwtAuthFilter(JwtTokenService jwtTokenService,
                         SuperAdminResolver superAdminResolver,
                         UserRepository userRepository) {
        this.jwtTokenService = jwtTokenService;
        this.superAdminResolver = superAdminResolver;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String bearer = extractBearer(request.getHeader(AUTH_HEADER));
        if (StringUtils.hasText(bearer) && jwtTokenService.isValid(bearer)) {
            String email = SuperAdminResolver.normalize(jwtTokenService.getEmail(bearer));
            if (StringUtils.hasText(email) && userExists(email)) {
                authenticate(email);
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean userExists(String email) {
        if (knownUsers.getIfPresent(email) != null) {
            return true;
        }
        boolean exists = userRepository.existsByEmail(email);
        if (exists) {
            knownUsers.put(email, Boolean.TRUE);
        }
        return exists;
    }

    private void authenticate(String email) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(MERCHANT_AUTHORITY));
        if (superAdminResolver.isSuperAdmin(email)) {
            authorities.add(new SimpleGrantedAuthority(SUPER_ADMIN_AUTHORITY));
        }
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(email, null, authorities));
    }

    private String extractBearer(String header) {
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length());
        }
        return null;
    }
}
