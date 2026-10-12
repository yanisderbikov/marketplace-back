package ru.marketplace.email;

/**
 * Общая HTML-обёртка писем платформы — перенос чёрно-белого шаблона заказа anyforms
 * (email-marketplace-order.html): серый фон, белая карточка, строчная подпись бренда, футер.
 * Шаблон в коде, а не в resources/templates: у всех писем одна рамка, отличается только тело.
 * Таблицы и inline-стили — потому что почтовые клиенты не понимают внешний CSS и flex.
 */
public final class EmailLayout {

    public static final String BRAND_NAME = "marketplace";

    private static final String FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI','Roboto','Helvetica Neue',Arial,sans-serif";

    private static final String LAYOUT = """
            <!DOCTYPE html>
            <html lang="ru">
            <head>
                <meta charset="UTF-8">
                <meta http-equiv="X-UA-Compatible" content="IE=edge">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <meta name="x-apple-disable-message-reformatting">
                <meta name="color-scheme" content="light only">
                <meta name="supported-color-schemes" content="light only">
                <title>%TITLE%</title>
                <style>
                    body { margin:0; padding:0; width:100% !important; -webkit-text-size-adjust:100%; -ms-text-size-adjust:100%; }
                    table { border-collapse:collapse; mso-table-lspace:0; mso-table-rspace:0; }
                    img { border:0; outline:none; text-decoration:none; -ms-interpolation-mode:bicubic; display:block; }
                    a { text-decoration:none; }
                    .font { font-family:%FONT%; }
                    @media (max-width:600px){
                        .card { width:100% !important; }
                        .pad { padding-left:24px !important; padding-right:24px !important; }
                    }
                </style>
            </head>
            <body class="font" style="margin:0; padding:0; background-color:#f2f2f2; font-family:%FONT%; color:#111111;">
            <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0" style="background-color:#f2f2f2;">
                <tr>
                    <td align="center" style="padding:32px 12px;">

                        <!-- основная карточка -->
                        <table role="presentation" width="560" class="card" cellpadding="0" cellspacing="0" border="0" style="width:560px; max-width:560px; background-color:#ffffff; border:1px solid #e2e2e2; border-radius:16px; overflow:hidden;">
                            <!-- бренд -->
                            <tr>
                                <td class="pad font" style="padding:36px 40px 0 40px;">
                                    <div style="font-size:12px; letter-spacing:0.5px; text-transform:lowercase; font-weight:600; color:#8c8c8c;">%BRAND%</div>
                                </td>
                            </tr>
                            <!-- заголовок -->
                            <tr>
                                <td class="pad font" style="padding:12px 40px 0 40px;">
                                    <h1 style="margin:0; font-size:26px; line-height:1.15; font-weight:800; color:#111111;">%TITLE%</h1>
                                </td>
                            </tr>
                            <!-- тело -->
                            <tr>
                                <td class="pad font" style="padding:14px 40px 32px 40px; font-size:16px; line-height:1.6; color:#333333;">
                                    %BODY%
                                </td>
                            </tr>
                        </table>

                        <!-- футер -->
                        <table role="presentation" width="560" class="card" cellpadding="0" cellspacing="0" border="0" style="width:560px; max-width:560px;">
                            <tr>
                                <td class="font" style="padding:20px 40px 24px 40px; font-size:12px; line-height:1.6; color:#a0a0a0; text-align:center;">
                                    © %BRAND%
                                </td>
                            </tr>
                        </table>

                    </td>
                </tr>
            </table>
            </body>
            </html>
            """;

    private EmailLayout() {
    }

    /**
     * Письмо целиком: title — обычный текст (экранируется), bodyHtml — готовый HTML тела
     * (всё пользовательское внутри него экранировать через {@link #escape(String)}).
     */
    public static String wrap(String title, String bodyHtml) {
        String safeTitle = escape(title);
        return LAYOUT
                .replace("%FONT%", FONT)
                .replace("%BRAND%", BRAND_NAME)
                .replace("%TITLE%", safeTitle)
                .replace("%BODY%", bodyHtml == null ? "" : bodyHtml);
    }

    /** Абзац тела письма; html уже безопасен. */
    public static String paragraph(String html) {
        return "<p style=\"margin:0 0 16px 0; font-size:16px; line-height:1.6; color:#333333;\">" + html + "</p>";
    }

    /** Чёрная кнопка-пилюля, как CTA в письмах anyforms. */
    public static String button(String text, String href) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:8px 0 16px 0;\"><tr><td>"
                + "<a href=\"" + escape(href) + "\" class=\"font\" style=\"display:inline-block; background-color:#000000; color:#ffffff;"
                + " font-size:15px; font-weight:700; padding:14px 26px; border-radius:999px;\">" + escape(text) + "</a>"
                + "</td></tr></table>";
    }

    /** Серая плашка «подпись строчными — значение», как блок ПВЗ в письме заказа anyforms; valueHtml уже безопасен. */
    public static String infoBlock(String label, String valueHtml) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\""
                + " style=\"background-color:#fafafa; border:1px solid #ececec; border-radius:12px; margin:0 0 16px 0;\">"
                + "<tr><td class=\"font\" style=\"padding:18px 22px 6px 22px; font-size:12px; line-height:1.5; color:#8c8c8c;"
                + " text-transform:lowercase; letter-spacing:0.5px;\">" + escape(label) + "</td></tr>"
                + "<tr><td class=\"font\" style=\"padding:0 22px 18px 22px; font-size:16px; line-height:1.5; font-weight:600;"
                + " color:#111111;\">" + (valueHtml == null ? "" : valueHtml) + "</td></tr>"
                + "</table>";
    }

    /** Экранирование пользовательского текста (имя покупателя, название товара…) для HTML письма. */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
