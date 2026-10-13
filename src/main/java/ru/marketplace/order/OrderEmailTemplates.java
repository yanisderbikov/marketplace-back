package ru.marketplace.order;

import ru.marketplace.email.EmailLayout;
import ru.marketplace.model.Order;
import ru.marketplace.model.OrderItem;
import ru.marketplace.model.Shop;
import ru.marketplace.util.MoneyUtil;
import ru.marketplace.util.PhoneUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Письма об оплаченном заказе — перенос письма-чека маркетплейса anyforms (EmailTemplate.getMarketplaceOrderEmail
 * + templates/email-marketplace-order.html) в общую рамку EmailLayout: та же таблица позиций в серой плашке,
 * итог, блок ПВЗ и чёрная кнопка-пилюля. Партнёрских шаблонов нет — у всех магазинов одно чёрно-белое письмо.
 * HTML собирается конкатенацией, а не заменой %ПЛЕЙСХОЛДЕРОВ%: название товара или комментарий покупателя
 * с текстом вида «%TOTAL%» иначе подменялись бы следующей заменой.
 */
final class OrderEmailTemplates {

    /**
     * Оформление строк чека — палитра DEFAULT_ROW_STYLE anyforms: строки живут в серой рамке-плашке
     * с боковым отступом 22px, название и цена чёрные, количество серое.
     */
    private static final String ROW_BORDER = "#ececec";
    private static final String ROW_NAME = "#111111";
    private static final String ROW_QTY = "#8c8c8c";
    private static final String ROW_PRICE = "#111111";
    private static final String ROW_SIDE_PADDING = "22px";

    private static final String CDEK_PVZ_LABEL = "пункт выдачи СДЭК";

    private OrderEmailTemplates() {
    }

    static String merchantSubject(Order order) {
        return "Новый заказ " + order.getPublicId();
    }

    static String buyerSubject(Order order) {
        return "Заказ " + order.getPublicId() + " оплачен";
    }

    /** Мерчанту: что купили, кому и куда отправлять + ссылка в админку на список заказов. */
    static String merchantNewOrder(Order order, Shop shop, String frontUrl) {
        StringBuilder body = new StringBuilder();
        body.append(EmailLayout.paragraph("Покупатель оплатил заказ <strong style=\"color:#111111;\">"
                + esc(order.getPublicId()) + "</strong> в&nbsp;магазине «" + esc(shop.getName())
                + "». Оплата прошла через терминал Т-Банка вашего магазина. Соберите заказ и&nbsp;отправьте его"
                + " в&nbsp;выбранный покупателем пункт выдачи СДЭК."));
        body.append(itemsTable(order));
        body.append(EmailLayout.infoBlock("покупатель", customerHtml(order)));
        body.append(EmailLayout.infoBlock(CDEK_PVZ_LABEL, pvzHtml(order, true)));
        if (order.getComment() != null && !order.getComment().isBlank()) {
            body.append(EmailLayout.infoBlock("комментарий покупателя", multiline(order.getComment().trim())));
        }
        body.append(EmailLayout.paragraph("Доставку до&nbsp;пункта выдачи покупатель оплачивает при&nbsp;получении."
                + " Трек-номер можно указать в&nbsp;карточке заказа в&nbsp;админке."));
        body.append(EmailLayout.button("Открыть заказы", frontUrl + "/admin/orders"));
        return EmailLayout.wrap(merchantSubject(order), body.toString());
    }

    /** Покупателю: подтверждение оплаты, состав, ПВЗ и как связаться с магазином. */
    static String buyerPaid(Order order, Shop shop, String frontUrl) {
        String greeting = order.getCustomerName() == null || order.getCustomerName().isBlank()
                ? "Здравствуйте!"
                : "Здравствуйте, " + esc(order.getCustomerName().trim()) + "!";
        StringBuilder body = new StringBuilder();
        body.append(EmailLayout.paragraph(greeting + " Спасибо за&nbsp;заказ в&nbsp;магазине «" + esc(shop.getName())
                + "». Оплата получена — ниже состав заказа. Магазин соберёт заказ и&nbsp;отправит его"
                + " в&nbsp;выбранный пункт выдачи СДЭК."));
        body.append(itemsTable(order));
        body.append(EmailLayout.infoBlock(CDEK_PVZ_LABEL, pvzHtml(order, false)));
        body.append(EmailLayout.paragraph("Доставка до&nbsp;пункта выдачи оплачивается при&nbsp;получении."));
        body.append(EmailLayout.button("Перейти в магазин", storefrontUrl(shop, frontUrl)));
        String contacts = shopContactsHtml(shop);
        if (!contacts.isEmpty()) {
            // Поддержка — отдельным блоком под чертой, как в письме anyforms
            body.append("<div style=\"margin-top:8px; padding-top:24px; border-top:1px solid #ececec; font-size:14px;"
                    + " line-height:1.6; color:#333333;\">Есть вопросы по&nbsp;заказу? Напишите магазину: ")
                    .append(contacts)
                    .append(" — и&nbsp;укажите номер заказа <strong style=\"color:#111111;\">")
                    .append(esc(order.getPublicId()))
                    .append("</strong>.</div>");
        }
        return EmailLayout.wrap(buyerSubject(order), body.toString());
    }

    /** Таблица позиций в серой плашке + «Итого», как чек в письме anyforms. Цена в строке — за все штуки позиции. */
    static String itemsTable(Order order) {
        StringBuilder sb = new StringBuilder();
        sb.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\"")
                .append(" style=\"background-color:#fafafa; border:1px solid #ececec; border-radius:12px; margin:8px 0 16px 0;\">")
                .append("<tr><td colspan=\"3\" class=\"font\" style=\"padding:18px 22px 10px 22px; font-size:12px;")
                .append(" line-height:1.5; color:#8c8c8c; text-transform:lowercase; letter-spacing:0.5px;\">состав заказа</td></tr>");
        for (OrderItem item : sortedItems(order)) {
            int qty = item.getQuantity() == null ? 1 : item.getQuantity();
            long lineKopecks = (item.getUnitPriceKopecks() == null ? 0L : item.getUnitPriceKopecks()) * qty;
            sb.append("<tr>")
                    .append("<td class=\"font\" style=\"padding:12px ").append(ROW_SIDE_PADDING)
                    .append("; font-size:15px; line-height:1.4; color:").append(ROW_NAME)
                    .append("; border-top:1px solid ").append(ROW_BORDER).append(";\">")
                    .append(esc(item.getProductName())).append("</td>")
                    .append("<td class=\"font\" align=\"center\" style=\"padding:12px 10px; font-size:15px; color:")
                    .append(ROW_QTY).append("; border-top:1px solid ").append(ROW_BORDER).append("; white-space:nowrap;\">×")
                    .append(qty).append("</td>")
                    .append("<td class=\"font\" align=\"right\" style=\"padding:12px ").append(ROW_SIDE_PADDING)
                    .append("; font-size:15px; color:").append(ROW_PRICE)
                    .append("; border-top:1px solid ").append(ROW_BORDER).append("; white-space:nowrap;\">")
                    .append(money(lineKopecks)).append("</td>")
                    .append("</tr>");
        }
        long total = order.getItemsTotalKopecks() == null ? 0L : order.getItemsTotalKopecks();
        sb.append("<tr>")
                .append("<td class=\"font\" style=\"padding:16px 22px; font-size:15px; font-weight:700; color:#111111;")
                .append(" border-top:2px solid #e2e2e2;\">Итого</td>")
                .append("<td style=\"border-top:2px solid #e2e2e2;\"></td>")
                .append("<td class=\"font\" align=\"right\" style=\"padding:16px 22px; font-size:16px; font-weight:800;")
                .append(" color:#111111; border-top:2px solid #e2e2e2; white-space:nowrap;\">")
                .append(money(total)).append("</td>")
                .append("</tr>")
                .append("</table>");
        return sb.toString();
    }

    /** Имя, телефон и почта покупателя — телефон и почта ссылками, чтобы мерчант связался в одно касание. */
    private static String customerHtml(Order order) {
        List<String> lines = new ArrayList<>();
        if (order.getCustomerName() != null && !order.getCustomerName().isBlank()) {
            lines.add(esc(order.getCustomerName().trim()));
        }
        if (order.getCustomerPhone() != null && !order.getCustomerPhone().isBlank()) {
            lines.add(phoneLink(order.getCustomerPhone().trim()));
        }
        if (order.getCustomerEmail() != null && !order.getCustomerEmail().isBlank()) {
            lines.add(link("mailto:" + order.getCustomerEmail().trim(), order.getCustomerEmail().trim()));
        }
        lines.add("<span style=\"font-weight:400; color:#8c8c8c;\">согласие на&nbsp;рассылку: "
                + (Boolean.TRUE.equals(order.getMarketingConsent()) ? "да" : "нет") + "</span>");
        return String.join("<br>", lines);
    }

    /** «Город, адрес» как joinPvz в anyforms; мерчанту дополнительно код ПВЗ — по нему оформляют отправку в СДЭК. */
    private static String pvzHtml(Order order, boolean withCode) {
        StringBuilder sb = new StringBuilder();
        if (order.getPvzCity() != null && !order.getPvzCity().isBlank()) {
            sb.append(esc(order.getPvzCity().trim()));
        }
        if (order.getPvzAddress() != null && !order.getPvzAddress().isBlank()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(esc(order.getPvzAddress().trim()));
        }
        if (withCode && order.getPvzCode() != null && !order.getPvzCode().isBlank()) {
            if (sb.length() > 0) {
                sb.append("<br>");
            }
            sb.append("<span style=\"font-weight:400; color:#8c8c8c;\">код ПВЗ: ")
                    .append(esc(order.getPvzCode().trim())).append("</span>");
        }
        if (sb.length() == 0) {
            sb.append(esc(order.getPvzCode()));
        }
        return sb.toString();
    }

    /** Telegram, почта и телефон магазина — что заполнено в «Настройках»; пусто — блока нет. */
    private static String shopContactsHtml(Shop shop) {
        List<String> parts = new ArrayList<>();
        if (shop.getSupportTelegram() != null && !shop.getSupportTelegram().isBlank()) {
            String tg = shop.getSupportTelegram().trim();
            parts.add("Telegram&nbsp;" + link("https://t.me/" + tg, "@" + tg));
        }
        if (shop.getContactEmail() != null && !shop.getContactEmail().isBlank()) {
            String email = shop.getContactEmail().trim();
            parts.add("почта&nbsp;" + link("mailto:" + email, email));
        }
        if (shop.getContactPhone() != null && !shop.getContactPhone().isBlank()) {
            parts.add("телефон&nbsp;" + phoneLink(shop.getContactPhone().trim()));
        }
        return String.join(", ", parts);
    }

    private static String phoneLink(String phone) {
        String e164 = PhoneUtil.toE164(phone);
        return e164 == null ? esc(phone) : link("tel:+" + e164, phone);
    }

    private static String link(String href, String text) {
        return "<a href=\"" + esc(href) + "\" style=\"color:#111111; font-weight:700; text-decoration:underline;\">"
                + esc(text) + "</a>";
    }

    /** Комментарий покупателя может быть в несколько строк — переносы сохраняем. */
    private static String multiline(String text) {
        return esc(text).replace("\r\n", "\n").replace("\n", "<br>");
    }

    /** «1 190 ₽» без переноса внутри суммы. */
    private static String money(long kopecks) {
        return esc(MoneyUtil.formatRubles(kopecks)).replace(" ", "&nbsp;");
    }

    private static List<OrderItem> sortedItems(Order order) {
        return order.getItems().stream()
                .sorted(Comparator.comparing(OrderItem::getId, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    private static String esc(String s) {
        return EmailLayout.escape(s);
    }

    /** Витрина на своём домене, если он привязан, иначе /shop/{slug} на домене платформы. */
    static String storefrontUrl(Shop shop, String frontUrl) {
        String domain = shop.getCustomDomain();
        return domain == null || domain.isBlank() ? frontUrl + "/shop/" + shop.getSlug() : "https://" + domain;
    }
}
