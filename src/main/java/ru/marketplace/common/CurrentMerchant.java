package ru.marketplace.common;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.config.JwtAuthFilter;
import ru.marketplace.model.Shop;
import ru.marketplace.model.User;
import ru.marketplace.repository.UserRepository;

/**
 * Кто вызывает ручку мерчанта: пользователь по почте из SecurityContext (её кладёт JwtAuthFilter)
 * и его магазин. Магазин всегда берётся отсюда, а не из запроса, — мерчант не может
 * подставить чужой shopId.
 */
@Component
@RequiredArgsConstructor
public class CurrentMerchant {

    private final UserRepository userRepository;

    /** Текущий пользователь (с уже загруженным магазином) или 401. */
    public User user() {
        String email = email();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid auth token"));
    }

    /** Магазин текущего пользователя или 409 — пока он не прошёл онбординг. */
    public Shop shop() {
        Shop shop = user().getShop();
        if (shop == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Сначала создайте магазин");
        }
        return shop;
    }

    public boolean isSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> JwtAuthFilter.SUPER_ADMIN_AUTHORITY.equals(a.getAuthority()));
    }

    private String email() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof String email) || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid auth token");
        }
        return email;
    }
}
