package ru.marketplace.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.auth.dto.LoginResponseDTO;
import ru.marketplace.auth.dto.MeDTO;
import ru.marketplace.auth.dto.MeShopDTO;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.email.EmailService;
import ru.marketplace.email.EmailMasking;
import ru.marketplace.model.LoginCode;
import ru.marketplace.model.Shop;
import ru.marketplace.model.User;
import ru.marketplace.repository.LoginCodeRepository;
import ru.marketplace.repository.UserRepository;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Вход мерчанта по одноразовому коду из письма — перенос anyforms AuthServiceImpl.
 * Отличия: код просит любая почта (регистрация = первый вход), коды живут в login_code
 * по почте, а пользователь заводится только после верного кода — неподтверждённые почты не копятся в users.
 */
@Slf4j
@Service
class AuthServiceImpl implements AuthService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /**
     * Окно общего потолка неверных кодов почты (auth.login-code.max-fails-per-day). Отсчитывается от первой
     * неверной попытки и новым кодом не сбрасывается — иначе 5 попыток на код раз в минуту давали бы
     * ~7 200 попыток в сутки на любую почту, включая супер-админов.
     */
    static final Duration FAIL_WINDOW = Duration.ofHours(24);
    static final String TOO_MANY_FAILS = "Слишком много неверных кодов для этой почты. Попробуйте позже";

    private final LoginCodeRepository loginCodeRepository;
    private final LoginCodeLockRepository loginCodeLockRepository;
    private final UserRepository userRepository;
    private final JwtTokenService jwtTokenService;
    private final SuperAdminResolver superAdminResolver;
    private final EmailService emailService;
    private final CurrentMerchant currentMerchant;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec codeHmacKey;
    private final Duration codeTtl;
    private final Duration resendInterval;
    private final int maxAttempts;
    private final int maxFailsPerDay;

    AuthServiceImpl(LoginCodeRepository loginCodeRepository,
                    LoginCodeLockRepository loginCodeLockRepository,
                    UserRepository userRepository,
                    JwtTokenService jwtTokenService,
                    SuperAdminResolver superAdminResolver,
                    EmailService emailService,
                    CurrentMerchant currentMerchant,
                    Clock clock,
                    @Value("${auth.login-code.secret}") String codeSecret,
                    @Value("${auth.login-code.ttl-minutes}") long ttlMinutes,
                    @Value("${auth.login-code.resend-seconds}") long resendSeconds,
                    @Value("${auth.login-code.max-attempts}") int maxAttempts,
                    @Value("${auth.login-code.max-fails-per-day}") int maxFailsPerDay) {
        if (codeSecret == null || codeSecret.isBlank()) {
            throw new IllegalStateException("AUTH_LOGIN_CODE_SECRET не задан: без него коды входа хранятся небезопасно");
        }
        if (maxAttempts <= 0) {
            throw new IllegalStateException("auth.login-code.max-attempts должен быть больше нуля");
        }
        if (maxFailsPerDay <= 0) {
            throw new IllegalStateException("auth.login-code.max-fails-per-day должен быть больше нуля");
        }
        this.codeHmacKey = new SecretKeySpec(codeSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        this.loginCodeRepository = loginCodeRepository;
        this.loginCodeLockRepository = loginCodeLockRepository;
        this.userRepository = userRepository;
        this.jwtTokenService = jwtTokenService;
        this.superAdminResolver = superAdminResolver;
        this.emailService = emailService;
        this.currentMerchant = currentMerchant;
        this.clock = clock;
        this.codeTtl = Duration.ofMinutes(ttlMinutes);
        this.resendInterval = Duration.ofSeconds(resendSeconds);
        this.maxAttempts = maxAttempts;
        this.maxFailsPerDay = maxFailsPerDay;
    }

    @Override
    @Transactional
    public void requestLoginCode(String rawEmail) {
        String email = SuperAdminResolver.normalize(rawEmail);
        Instant now = clock.instant();

        // Сначала гарантируем строку, потом блокируем её: параллельные запросы одной почты идут строго по очереди
        loginCodeLockRepository.insertPlaceholderIfAbsent(email);
        Optional<LoginCode> existing = loginCodeLockRepository.findForUpdate(email);
        if (existing.isPresent() && isLockedOut(existing.get(), now)) {
            // Новый код всё равно не примут до конца окна — не шлём бесполезных писем
            log.warn("Запрос кода входа для {}: почта заблокирована после {} неверных кодов за сутки",
                    mask(email), existing.get().getFailsInWindow());
            throw tooManyFails();
        }
        if (existing.isPresent() && existing.get().getSentAt().plus(resendInterval).isAfter(now)) {
            log.info("Повторный запрос кода входа для {} раньше чем через {} сек: действует прежний код",
                    mask(email), resendInterval.toSeconds());
            return;
        }

        String code = String.format("%06d", random.nextInt(1_000_000));
        LoginCode loginCode = existing.orElseGet(() -> {
            LoginCode created = new LoginCode();
            created.setFailsInWindow(0);
            return created;
        });
        loginCode.setEmail(email);
        loginCode.setCodeHash(hash(code));
        loginCode.setExpiresAt(now.plus(codeTtl));
        loginCode.setSentAt(now);
        // Сбрасываются только попытки этого кода; окно неверных кодов почты переживает новый код
        loginCode.setAttempts(0);
        loginCodeRepository.save(loginCode);

        try {
            emailService.sendEmail(email, LoginCodeEmail.SUBJECT, LoginCodeEmail.build(code, codeTtl.toMinutes()));
        } catch (RuntimeException e) {
            // Исключение откатывает транзакцию: код не сохранится и не включит паузу на повтор —
            // мерчант сможет запросить письмо снова сразу
            log.error("Не удалось отправить код входа на {}", mask(email), e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Не удалось отправить письмо. Попробуйте ещё раз через минуту");
        }
        log.info("Код входа отправлен на {}", mask(email));
    }

    @Override
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public LoginResponseDTO verifyLoginCode(String rawEmail, String code) {
        String email = SuperAdminResolver.normalize(rawEmail);
        Instant now = clock.instant();

        // Строка кода блокируется до конца транзакции: проверки одной почты идут строго по очереди
        LoginCode loginCode = loginCodeLockRepository.findForUpdate(email)
                .orElseThrow(AuthServiceImpl::codeExpired);
        if (isLockedOut(loginCode, now)) {
            throw tooManyFails();
        }
        if (!loginCode.getExpiresAt().isAfter(now)) {
            // Пока идёт окно неверных кодов, строку не удаляем: вместе с ней обнулился бы и счётчик,
            // и перебор можно было бы продолжить, просто дождавшись истечения кода
            if (!hasActiveFailWindow(loginCode, now)) {
                loginCodeRepository.delete(loginCode);
            }
            throw codeExpired();
        }
        // Сожжённый код не удаляем: в строке остаётся sentAt, и пауза на повторную отправку продолжает действовать
        if (loginCode.getAttempts() >= maxAttempts) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Слишком много неверных попыток. Запросите новый код");
        }
        if (!MessageDigest.isEqual(
                hash(code == null ? "" : code.trim()).getBytes(StandardCharsets.UTF_8),
                loginCode.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            loginCode.setAttempts(loginCode.getAttempts() + 1);
            countFailure(loginCode, now);
            loginCodeRepository.save(loginCode);
            if (isLockedOut(loginCode, now)) {
                log.warn("Вход для {}: {} неверных кодов за сутки — почта заблокирована до конца окна",
                        mask(email), loginCode.getFailsInWindow());
                throw tooManyFails();
            }
            // Показываем меньший из остатков: попыток этого кода и суточного потолка почты
            int left = Math.min(maxAttempts - loginCode.getAttempts(), maxFailsPerDay - loginCode.getFailsInWindow());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    left > 0 ? "Неверный код. Осталось попыток: " + left
                             : "Неверный код. Запросите новый");
        }

        // Код одноразовый; вместе с ним уходят пауза на повтор (после выхода войти можно сразу)
        // и окно неверных кодов: владелец почты только что доказал, что читает её
        loginCodeRepository.delete(loginCode);

        User user = userRepository.findByEmail(email).orElseGet(() -> register(email, now));
        user.setLastLoginAt(now);
        userRepository.save(user);

        boolean superAdmin = superAdminResolver.isSuperAdmin(email);
        log.info("Вход мерчанта: пользователь id={}{}", user.getId(), superAdmin ? " (супер-админ)" : "");
        return new LoginResponseDTO(jwtTokenService.createToken(email, superAdmin));
    }

    @Override
    @Transactional(readOnly = true)
    public MeDTO me() {
        User user = currentMerchant.user();
        Shop shop = user.getShop();
        MeShopDTO shopDto = shop == null ? null
                : new MeShopDTO(shop.getId(), shop.getSlug(), shop.getName(), Boolean.TRUE.equals(shop.getActive()));
        return new MeDTO(user.getEmail(), user.getName(), superAdminResolver.isSuperAdmin(user.getEmail()), shopDto);
    }

    /** Регистрация = первый успешный вход: имя — часть почты до @, чтобы в админке было как обратиться. */
    private User register(String email, Instant now) {
        User user = new User();
        user.setEmail(email);
        int at = email.indexOf('@');
        user.setName(at > 0 ? email.substring(0, at) : email);
        user.setCreatedAt(now);
        log.info("Новый мерчант: первая регистрация по почте {}", mask(email));
        return user;
    }

    /** Окно неверных кодов почты ещё идёт. */
    private static boolean hasActiveFailWindow(LoginCode loginCode, Instant now) {
        return loginCode.getFailWindowStart() != null
                && loginCode.getFailWindowStart().plus(FAIL_WINDOW).isAfter(now);
    }

    /** Потолок неверных кодов почты за окно исчерпан: ни новых кодов, ни проверок до конца окна. */
    private boolean isLockedOut(LoginCode loginCode, Instant now) {
        return hasActiveFailWindow(loginCode, now)
                && loginCode.getFailsInWindow() != null
                && loginCode.getFailsInWindow() >= maxFailsPerDay;
    }

    /** Неверный код: в текущее окно почты или с него начинается новое. */
    private static void countFailure(LoginCode loginCode, Instant now) {
        if (hasActiveFailWindow(loginCode, now)) {
            loginCode.setFailsInWindow(loginCode.getFailsInWindow() + 1);
        } else {
            loginCode.setFailWindowStart(now);
            loginCode.setFailsInWindow(1);
        }
    }

    private static ResponseStatusException tooManyFails() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY_FAILS);
    }

    private static ResponseStatusException codeExpired() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Код устарел. Запросите новый");
    }

    private String hash(String code) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(codeHmacKey);
            return HexFormat.of().formatHex(mac.doFinal(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String mask(String email) {
        return EmailMasking.mask(email);
    }
}
