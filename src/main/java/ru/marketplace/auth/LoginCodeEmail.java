package ru.marketplace.auth;

import ru.marketplace.email.EmailLayout;

/**
 * Письмо с кодом входа — перенос anyforms templates/email-login-code.html в общую рамку EmailLayout:
 * тот же текст и крупная плашка с кодом, но в чёрно-белом стиле платформы вместо жёлто-синего anyforms.
 */
final class LoginCodeEmail {

    /** Строчный бренд в теме письма, как и везде в UI: «Код для входа в marketplace» */
    static final String SUBJECT = "Код для входа в " + EmailLayout.BRAND_NAME;

    private static final String TITLE = "Ваш код";

    /**
     * Код — отдельной ячейкой таблицы: почтовые клиенты не понимают flex и внешний CSS,
     * а крупные цифры с разрядкой удобно переписывать с телефона.
     */
    private static final String CODE_BLOCK = """
            <table role="presentation" cellpadding="0" cellspacing="0" border="0" style="margin:8px 0 24px 0;">
                <tr>
                    <td class="font" style="background-color:#111111; border-radius:14px; padding:18px 34px; font-size:34px; font-weight:800; letter-spacing:10px; color:#ffffff;">%CODE%</td>
                </tr>
            </table>""";

    private LoginCodeEmail() {
    }

    static String build(String code, long ttlMinutes) {
        String body = EmailLayout.paragraph("Введите этот код на странице входа. Он действует "
                        + ttlMinutes + "&nbsp;" + minutesWord(ttlMinutes) + " и работает один раз.")
                + CODE_BLOCK.replace("%CODE%", EmailLayout.escape(code))
                + "<p style=\"margin:0; font-size:14px; line-height:1.6; color:#8c8c8c;\">"
                + "Если вы не запрашивали вход, просто проигнорируйте это письмо — без кода войти нельзя.</p>";
        return EmailLayout.wrap(TITLE, body);
    }

    /** «действует 1 минуту / 2 минуты / 10 минут» — TTL задаётся в properties и может стать любым. */
    static String minutesWord(long n) {
        long mod100 = Math.abs(n) % 100;
        long mod10 = mod100 % 10;
        if (mod100 >= 11 && mod100 <= 14) {
            return "минут";
        }
        if (mod10 == 1) {
            return "минуту";
        }
        if (mod10 >= 2 && mod10 <= 4) {
            return "минуты";
        }
        return "минут";
    }
}
