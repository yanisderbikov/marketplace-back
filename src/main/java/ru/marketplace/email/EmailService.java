package ru.marketplace.email;

public interface EmailService {

    /**
     * Отправляет HTML-письмо от имени платформы (адрес и имя отправителя — из env NotiSend).
     * Бросает RuntimeException, если письмо не ушло: вызывающий решает, это 502 (код входа)
     * или только запись в лог (уведомления о заказе).
     */
    void sendEmail(String to, String subject, String html);
}
