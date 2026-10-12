package ru.marketplace.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import ru.marketplace.auth.dto.MeDTO;
import ru.marketplace.auth.dto.MeShopDTO;
import ru.marketplace.common.CurrentMerchant;
import ru.marketplace.email.EmailService;
import ru.marketplace.model.LoginCode;
import ru.marketplace.model.Shop;
import ru.marketplace.model.User;
import ru.marketplace.repository.LoginCodeRepository;
import ru.marketplace.repository.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceImplTest {

    private static final String SUPER = "boss@example.com";
    private static final String MERCHANT = "shop@example.com";
    private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");
    private static final Pattern CODE_IN_LETTER = Pattern.compile("(\\d{6})</td>");

    private final LoginCodeRepository loginCodeRepository = mock(LoginCodeRepository.class);
    private final LoginCodeLockRepository loginCodeLockRepository = mock(LoginCodeLockRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtTokenService jwtTokenService = mock(JwtTokenService.class);
    private final EmailService emailService = mock(EmailService.class);
    private final CurrentMerchant currentMerchant = mock(CurrentMerchant.class);
    /** Таблица login_code в памяти: репозитории-моки читают и пишут сюда */
    private final Map<String, LoginCode> codes = new HashMap<>();
    private MutableClock clock;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        service = newService("test-code-secret");
        when(loginCodeLockRepository.findForUpdate(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(codes.get(inv.<String>getArgument(0))));
        when(loginCodeRepository.save(any())).thenAnswer(inv -> {
            LoginCode code = inv.getArgument(0);
            codes.put(code.getEmail(), code);
            return code;
        });
        doAnswer(inv -> codes.remove(inv.<LoginCode>getArgument(0).getEmail()))
                .when(loginCodeRepository).delete(any());
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jwtTokenService.createToken(anyString(), anyBoolean())).thenReturn("jwt");
    }

    private AuthServiceImpl newService(String secret) {
        return newService(secret, 20);
    }

    private AuthServiceImpl newService(String secret, int maxFailsPerDay) {
        return new AuthServiceImpl(loginCodeRepository, loginCodeLockRepository, userRepository, jwtTokenService,
                new SuperAdminResolver(SUPER), emailService, currentMerchant, clock, secret, 10, 60, 5, maxFailsPerDay);
    }

    private static ResponseStatusException tooManyRequests(Runnable action) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
        return e;
    }

    private String sentCode(String email) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendEmail(eq(email), anyString(), body.capture());
        Matcher m = CODE_IN_LETTER.matcher(body.getValue());
        assertTrue(m.find(), "в письме нет шестизначного кода");
        return m.group(1);
    }

    private static String wrongCode(String code) {
        return code.equals("000000") ? "000001" : "000000";
    }

    private static ResponseStatusException badRequest(Runnable action) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, action::run);
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        return e;
    }

    @Test
    void storedHashIsKeyedSoPlainSha256OfCodeDoesNotMatch() throws Exception {
        service.requestLoginCode(MERCHANT);
        String code = sentCode(MERCHANT);

        String stored = codes.get(MERCHANT).getCodeHash();
        byte[] sha = java.security.MessageDigest.getInstance("SHA-256").digest(code.getBytes());
        assertNotEquals(java.util.HexFormat.of().formatHex(sha), stored);
        assertNotEquals(code, stored);
        assertEquals(64, stored.length());
    }

    @Test
    void anyEmailGetsCodeBecauseFirstLoginIsRegistration() {
        service.requestLoginCode(" New@Example.COM ");

        verify(emailService).sendEmail(eq("new@example.com"), eq("Код для входа в marketplace"), anyString());
        LoginCode stored = codes.get("new@example.com");
        assertNotNull(stored);
        assertEquals(NOW.plus(Duration.ofMinutes(10)), stored.getExpiresAt());
        assertEquals(NOW, stored.getSentAt());
        assertEquals(0, stored.getAttempts());
        // Пользователь появляется только после верного кода
        verify(userRepository, never()).save(any());
    }

    @Test
    void letterHasCodeTtlAndBrand() {
        service.requestLoginCode(MERCHANT);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmail(eq(MERCHANT), anyString(), body.capture());
        assertTrue(body.getValue().contains("10&nbsp;минут"));
        assertTrue(body.getValue().contains("marketplace"));
        assertTrue(CODE_IN_LETTER.matcher(body.getValue()).find());
    }

    @Test
    void codeVerifiesOnceAndRegistersNewMerchant() {
        service.requestLoginCode(MERCHANT);
        String code = sentCode(MERCHANT);
        clock.advance(Duration.ofMinutes(3));

        assertEquals("jwt", service.verifyLoginCode(" Shop@Example.com", code).getToken());

        verify(jwtTokenService).createToken(MERCHANT, false);
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User created = saved.getValue();
        assertEquals(MERCHANT, created.getEmail());
        assertEquals("shop", created.getName());
        assertNull(created.getShop());
        Instant loginAt = NOW.plus(Duration.ofMinutes(3));
        assertEquals(loginAt, created.getCreatedAt());
        assertEquals(loginAt, created.getLastLoginAt());
        assertFalse(codes.containsKey(MERCHANT), "код одноразовый — после входа удалён");

        ResponseStatusException again = badRequest(() -> service.verifyLoginCode(MERCHANT, code));
        assertEquals("Код устарел. Запросите новый", again.getReason());
    }

    @Test
    void existingMerchantIsNotRegisteredTwice() {
        Shop shop = new Shop();
        User existing = new User();
        existing.setId(7L);
        existing.setEmail(MERCHANT);
        existing.setName("Аня");
        existing.setShop(shop);
        existing.setCreatedAt(NOW.minus(Duration.ofDays(30)));
        when(userRepository.findByEmail(MERCHANT)).thenReturn(Optional.of(existing));

        service.requestLoginCode(MERCHANT);
        service.verifyLoginCode(MERCHANT, sentCode(MERCHANT));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertSame(existing, saved.getValue());
        assertEquals("Аня", existing.getName());
        assertSame(shop, existing.getShop());
        assertEquals(NOW.minus(Duration.ofDays(30)), existing.getCreatedAt());
        assertEquals(NOW, existing.getLastLoginAt());
    }

    @Test
    void superAdminFlagGoesIntoToken() {
        service.requestLoginCode(SUPER.toUpperCase());

        service.verifyLoginCode(SUPER, sentCode(SUPER));

        verify(jwtTokenService).createToken(SUPER, true);
    }

    @Test
    void wrongCodeCountsAttemptsAndBurnsCodeAtLimit() {
        service.requestLoginCode(MERCHANT);
        String code = sentCode(MERCHANT);
        String wrong = wrongCode(code);

        assertEquals("Неверный код. Осталось попыток: 4",
                badRequest(() -> service.verifyLoginCode(MERCHANT, wrong)).getReason());
        for (int i = 0; i < 3; i++) {
            badRequest(() -> service.verifyLoginCode(MERCHANT, wrong));
        }
        assertEquals("Неверный код. Запросите новый",
                badRequest(() -> service.verifyLoginCode(MERCHANT, wrong)).getReason());
        assertEquals(5, codes.get(MERCHANT).getAttempts());

        // Сгоревший код не принимается даже верным
        assertEquals("Слишком много неверных попыток. Запросите новый код",
                badRequest(() -> service.verifyLoginCode(MERCHANT, code)).getReason());
        verify(jwtTokenService, never()).createToken(anyString(), anyBoolean());
        verify(userRepository, never()).save(any());
    }

    @Test
    void burnedCodeKeepsResendPauseThenNewCodeWorks() {
        service.requestLoginCode(MERCHANT);
        String wrong = wrongCode(sentCode(MERCHANT));
        for (int i = 0; i < 5; i++) {
            badRequest(() -> service.verifyLoginCode(MERCHANT, wrong));
        }

        clock.advance(Duration.ofSeconds(30));
        service.requestLoginCode(MERCHANT);
        verify(emailService, times(1)).sendEmail(eq(MERCHANT), anyString(), anyString());

        clock.advance(Duration.ofSeconds(31));
        service.requestLoginCode(MERCHANT);
        verify(emailService, times(2)).sendEmail(eq(MERCHANT), anyString(), anyString());
        assertEquals(0, codes.get(MERCHANT).getAttempts());
        assertEquals("jwt", service.verifyLoginCode(MERCHANT, sentCode(MERCHANT)).getToken());
    }

    @Test
    void expiredCodeIsRejected() {
        service.requestLoginCode(MERCHANT);
        String code = sentCode(MERCHANT);

        clock.advance(Duration.ofMinutes(10));

        assertEquals("Код устарел. Запросите новый",
                badRequest(() -> service.verifyLoginCode(MERCHANT, code)).getReason());
        assertFalse(codes.containsKey(MERCHANT));
        verify(jwtTokenService, never()).createToken(anyString(), anyBoolean());
    }

    @Test
    void verifyWithoutRequestedCodeIsRejected() {
        assertEquals("Код устарел. Запросите новый",
                badRequest(() -> service.verifyLoginCode(MERCHANT, "123456")).getReason());
        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void resendIsThrottledForAMinute() {
        service.requestLoginCode(MERCHANT);
        String firstCode = sentCode(MERCHANT);

        clock.advance(Duration.ofSeconds(30));
        assertDoesNotThrow(() -> service.requestLoginCode(MERCHANT));
        verify(emailService, times(1)).sendEmail(eq(MERCHANT), anyString(), anyString());
        assertEquals("jwt", service.verifyLoginCode(MERCHANT, firstCode).getToken());

        // После входа код удалён — новый можно запросить сразу, следующий — только через минуту
        service.requestLoginCode(MERCHANT);
        service.requestLoginCode(MERCHANT);
        verify(emailService, times(2)).sendEmail(eq(MERCHANT), anyString(), anyString());
        clock.advance(Duration.ofSeconds(61));
        service.requestLoginCode(MERCHANT);
        verify(emailService, times(3)).sendEmail(eq(MERCHANT), anyString(), anyString());
    }

    @Test
    void resendReplacesOldCode() {
        service.requestLoginCode(MERCHANT);
        String firstCode = sentCode(MERCHANT);
        clock.advance(Duration.ofSeconds(61));
        service.requestLoginCode(MERCHANT);
        String secondCode = sentCode(MERCHANT);

        if (!firstCode.equals(secondCode)) {
            badRequest(() -> service.verifyLoginCode(MERCHANT, firstCode));
        }
        assertEquals("jwt", service.verifyLoginCode(MERCHANT, secondCode).getToken());
    }

    @Test
    void firstRequestLocksPlaceholderRowSoParallelFirstRequestsQueueUp() {
        // Как в БД: INSERT … ON CONFLICT DO NOTHING кладёт заглушку (sent_at = epoch, пустой хэш)
        when(loginCodeLockRepository.insertPlaceholderIfAbsent(anyString())).thenAnswer(inv -> {
            String email = inv.getArgument(0);
            if (codes.containsKey(email)) {
                return 0;
            }
            LoginCode placeholder = new LoginCode();
            placeholder.setEmail(email);
            placeholder.setCodeHash("");
            placeholder.setExpiresAt(Instant.EPOCH);
            placeholder.setSentAt(Instant.EPOCH);
            placeholder.setAttempts(0);
            placeholder.setFailsInWindow(0);
            codes.put(email, placeholder);
            return 1;
        });

        service.requestLoginCode(" New@Example.COM ");

        var inOrder = inOrder(loginCodeLockRepository);
        inOrder.verify(loginCodeLockRepository).insertPlaceholderIfAbsent("new@example.com");
        inOrder.verify(loginCodeLockRepository).findForUpdate("new@example.com");
        // Заглушка не включает паузу на повтор и перезаписана настоящим кодом
        String code = sentCode("new@example.com");
        LoginCode stored = codes.get("new@example.com");
        assertEquals(NOW, stored.getSentAt());
        assertEquals(64, stored.getCodeHash().length());
        assertEquals("jwt", service.verifyLoginCode("new@example.com", code).getToken());
    }

    @Test
    void mailFailureBecomesBadGateway() {
        doThrow(new RuntimeException("NotiSend down")).when(emailService).sendEmail(anyString(), anyString(), anyString());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.requestLoginCode(MERCHANT));
        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());
    }

    @Test
    void blankSecretFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> newService(" "));
        assertThrows(IllegalStateException.class, () -> newService(null));
    }

    @Test
    void meReturnsShopAndSuperAdminFlag() {
        Shop shop = new Shop();
        shop.setId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
        shop.setSlug("candles");
        shop.setName("Свечи");
        shop.setActive(false);
        User user = new User();
        user.setEmail(SUPER);
        user.setName("boss");
        user.setShop(shop);
        when(currentMerchant.user()).thenReturn(user);

        assertEquals(new MeDTO(SUPER, "boss", true,
                        new MeShopDTO(shop.getId(), "candles", "Свечи", false)),
                service.me());
    }

    @Test
    void meWithoutShopHasNullShop() {
        User user = new User();
        user.setEmail(MERCHANT);
        user.setName("shop");
        when(currentMerchant.user()).thenReturn(user);

        assertEquals(new MeDTO(MERCHANT, "shop", false, null), service.me());
    }

    // ── Суточный потолок неверных кодов почты ──

    @Test
    void failuresAreCountedAcrossCodesAndCappedPerDay() {
        service = newService("test-code-secret", 7);
        service.requestLoginCode(MERCHANT);
        String wrong = wrongCode(sentCode(MERCHANT));
        for (int i = 0; i < 5; i++) {
            badRequest(() -> service.verifyLoginCode(MERCHANT, wrong));
        }

        // Новый код обнуляет попытки кода, но не счётчик почты: остаток — меньший из двух
        clock.advance(Duration.ofSeconds(61));
        service.requestLoginCode(MERCHANT);
        String code = sentCode(MERCHANT);
        assertEquals(0, codes.get(MERCHANT).getAttempts());
        assertEquals(5, codes.get(MERCHANT).getFailsInWindow());
        assertEquals("Неверный код. Осталось попыток: 1",
                badRequest(() -> service.verifyLoginCode(MERCHANT, wrongCode(code))).getReason());

        // Потолок исчерпан: 429, и даже верный код не принимается до конца окна
        assertEquals(AuthServiceImpl.TOO_MANY_FAILS,
                tooManyRequests(() -> service.verifyLoginCode(MERCHANT, wrongCode(code))).getReason());
        assertEquals(AuthServiceImpl.TOO_MANY_FAILS,
                tooManyRequests(() -> service.verifyLoginCode(MERCHANT, code)).getReason());
        verify(jwtTokenService, never()).createToken(anyString(), anyBoolean());

        // Новых писем тоже нет — код всё равно не приняли бы
        clock.advance(Duration.ofMinutes(5));
        assertEquals(AuthServiceImpl.TOO_MANY_FAILS,
                tooManyRequests(() -> service.requestLoginCode(MERCHANT)).getReason());
        verify(emailService, times(2)).sendEmail(eq(MERCHANT), anyString(), anyString());
    }

    @Test
    void lockoutEndsWithTheWindow() {
        service = newService("test-code-secret", 5);
        service.requestLoginCode(MERCHANT);
        String wrong = wrongCode(sentCode(MERCHANT));
        for (int i = 0; i < 4; i++) {
            badRequest(() -> service.verifyLoginCode(MERCHANT, wrong));
        }
        tooManyRequests(() -> service.verifyLoginCode(MERCHANT, wrong));

        clock.advance(AuthServiceImpl.FAIL_WINDOW.minusSeconds(1));
        tooManyRequests(() -> service.requestLoginCode(MERCHANT));

        clock.advance(Duration.ofSeconds(1));
        service.requestLoginCode(MERCHANT);
        String code = sentCode(MERCHANT);
        // Первая ошибка после окна начинает новое окно
        assertEquals("Неверный код. Осталось попыток: 4",
                badRequest(() -> service.verifyLoginCode(MERCHANT, wrongCode(code))).getReason());
        assertEquals(1, codes.get(MERCHANT).getFailsInWindow());
        assertEquals(clock.instant(), codes.get(MERCHANT).getFailWindowStart());
        assertEquals("jwt", service.verifyLoginCode(MERCHANT, code).getToken());
    }

    @Test
    void expiredCodeKeepsRowWhileFailWindowIsActive() {
        service.requestLoginCode(MERCHANT);
        String code = sentCode(MERCHANT);
        badRequest(() -> service.verifyLoginCode(MERCHANT, wrongCode(code)));

        clock.advance(Duration.ofMinutes(10));
        assertEquals("Код устарел. Запросите новый",
                badRequest(() -> service.verifyLoginCode(MERCHANT, code)).getReason());
        // Строка жива — счётчик не обнулился, дождавшись истечения кода
        assertEquals(1, codes.get(MERCHANT).getFailsInWindow());

        service.requestLoginCode(MERCHANT);
        assertEquals(1, codes.get(MERCHANT).getFailsInWindow());
    }

    @Test
    void successfulLoginClearsFailWindow() {
        service.requestLoginCode(MERCHANT);
        String code = sentCode(MERCHANT);
        badRequest(() -> service.verifyLoginCode(MERCHANT, wrongCode(code)));

        assertEquals("jwt", service.verifyLoginCode(MERCHANT, code).getToken());
        assertFalse(codes.containsKey(MERCHANT));
    }

    @Test
    void nonPositiveFailCapFailsAtStartup() {
        assertThrows(IllegalStateException.class, () -> newService("test-code-secret", 0));
    }

    @Test
    void newRowStartsWithoutFailWindow() {
        service.requestLoginCode(MERCHANT);

        assertEquals(0, codes.get(MERCHANT).getFailsInWindow());
        assertNull(codes.get(MERCHANT).getFailWindowStart());
    }
}
