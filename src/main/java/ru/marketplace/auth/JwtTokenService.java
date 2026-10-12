package ru.marketplace.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT мерчанта (HS256): sub = почта, claim super — флаг супер-админа для фронта.
 * Права на сервере берутся не из claim, а заново из admin.super.emails в JwtAuthFilter:
 * убрали почту из списка — доступ пропал сразу, без ожидания истечения токена.
 */
@Service
public class JwtTokenService {

    public static final String SUPER_CLAIM = "super";

    private final SecretKey key;
    private final long expirationMs;

    public JwtTokenService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.expiration.seconds}") long expirationSeconds) {
        // hmacShaKeyFor сам падает на старте, если секрет короче 32 байт (HS256)
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationSeconds * 1000L;
    }

    public String createToken(String email, boolean superAdmin) {
        var now = new Date();
        var expiry = new Date(now.getTime() + expirationMs);
        return Jwts.builder()
                .subject(email)
                .claim(SUPER_CLAIM, superAdmin)
                .issuedAt(now)
                .expiration(expiry)
                // Алгоритм зафиксирован: без него jjwt выбирает HS384/HS512 по длине секрета,
                // и токен молча менял бы алгоритм при смене JWT_SECRET
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public boolean isValid(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String getEmail(String token) {
        return parseClaims(token).getSubject();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }
}
