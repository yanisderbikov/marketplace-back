package ru.marketplace.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Одноразовый код входа. Отдельная таблица, а не поля users (как было в anyforms):
 * код запрашивают ещё до регистрации, пользователя создаём только после верного кода.
 * Хранится только HMAC-SHA256 кода — сам код есть лишь в письме.
 */
@Entity
@Table(name = "login_code")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoginCode {

    /** Нормализованная почта: на почту не больше одного живого кода */
    @Id
    @Column(nullable = false)
    private String email;

    @Column(name = "code_hash", nullable = false, length = 128)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "sent_at", nullable = false)
    private Instant sentAt;

    /** Неверные попытки текущего кода; новый код обнуляет */
    @Column(nullable = false)
    private Integer attempts;

    /**
     * Начало суточного окна неверных кодов почты; null — неверных ещё не было.
     * В отличие от attempts, новый код окно НЕ сбрасывает: иначе перебор шёл бы по 5 попыток
     * на каждый свежий код без общего потолка.
     */
    @Column(name = "fail_window_start")
    private Instant failWindowStart;

    /** Неверных кодов почты в окне fail_window_start (по всем кодам) */
    @Column(name = "fails_in_window", nullable = false)
    private Integer failsInWindow;
}
