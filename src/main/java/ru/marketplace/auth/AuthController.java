package ru.marketplace.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.marketplace.auth.dto.LoginResponseDTO;
import ru.marketplace.auth.dto.MeDTO;
import ru.marketplace.auth.dto.RequestCodeDTO;
import ru.marketplace.auth.dto.VerifyCodeDTO;
import ru.marketplace.common.ClientIp;

import java.util.Map;

/**
 * Вход по коду из письма. Запрос и проверка кода — под /api/public (токена ещё нет),
 * «кто я» — под /api/auth (нужен токен мерчанта). Ошибки отдаёт GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Вход и регистрация мерчанта по коду из письма")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;

    @Operation(summary = "Отправить код входа на почту",
            description = "Любая почта: первый вход и есть регистрация. Повторный запрос раньше чем через минуту "
                    + "письмо не шлёт — действует прежний код. 429 — лимит запросов с одного IP за час "
                    + "или почта временно заблокирована после серии неверных кодов, 502 — письмо не ушло")
    @PostMapping("/public/auth/request-code")
    public ResponseEntity<Map<String, String>> requestCode(@Valid @RequestBody RequestCodeDTO request,
                                                           HttpServletRequest httpRequest) {
        loginRateLimiter.acquire(ClientIp.of(httpRequest));
        authService.requestLoginCode(request.getEmail());
        return ResponseEntity.ok(Map.of());
    }

    @Operation(summary = "Подтвердить код и получить токен",
            description = "Новая почта заводит мерчанта без магазина — магазин создаётся на онбординге. "
                    + "429 — лимит проверок с одного IP за час или слишком много неверных кодов для почты за сутки")
    @PostMapping("/public/auth/verify-code")
    public ResponseEntity<LoginResponseDTO> verifyCode(@Valid @RequestBody VerifyCodeDTO request,
                                                       HttpServletRequest httpRequest) {
        loginRateLimiter.acquireVerify(ClientIp.of(httpRequest));
        return ResponseEntity.ok(authService.verifyLoginCode(request.getEmail(), request.getCode()));
    }

    @Operation(summary = "Кто я",
            description = "Почта, имя, флаг супер-админа и магазин из БД: фронт строит по ним меню админки "
                    + "и решает, вести ли на онбординг",
            security = @SecurityRequirement(name = "Bearer"))
    @GetMapping("/auth/me")
    public ResponseEntity<MeDTO> me() {
        return ResponseEntity.ok(authService.me());
    }
}
