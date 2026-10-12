package ru.marketplace.payment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Подпись Т-Банка: SHA-256 от значений корневых скалярных параметров + Password, отсортированных по ключу.
 * Ожидаемые токены посчитаны независимо (sha256 от склейки руками), первый — пример из документации Т-Банка.
 */
class TinkoffTokenServiceTest {

    static final String DOC_PASSWORD = "11111111111111";
    static final String DOC_TERMINAL = "MerchantTerminalKey";
    /** Пример «Подпись запроса» из документации Т-Банка (Init: TerminalKey, Amount, OrderId, Description). */
    static final String DOC_INIT_TOKEN = "72dd466f8ace0a37a1f740ce5fb78101712bc0665d91a8108c7c8a0ccd426db2";
    /** sha256("19200" "12345" "0" "1230" "A1B2C3" "430000******0777" Password "13660" "CONFIRMED" "true" TerminalKey). */
    static final String NOTIFICATION_TOKEN = "23d8231fc2513c182012dde3b1cb324b9b3276151bc685f040bcfc7375269303";

    private final TinkoffTokenService service = new TinkoffTokenService();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private Map<String, Object> parse(String json) throws Exception {
        return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    static String notificationJson(String token) {
        return """
                {"TerminalKey":"MerchantTerminalKey","OrderId":"A1B2C3","Success":true,"Status":"CONFIRMED",
                 "PaymentId":13660,"ErrorCode":"0","Amount":19200,"CardId":12345,"Pan":"430000******0777",
                 "ExpDate":"1230","Receipt":{"Email":"a@b.ru","Items":[{"Name":"x"}]},"DATA":{"Source":"cards"},
                 "RebillId":null,"Token":"%s"}
                """.formatted(token);
    }

    @Test
    void signsDocumentationExample() {
        Map<String, String> params = Map.of(
                "TerminalKey", DOC_TERMINAL,
                "Amount", "19200",
                "OrderId", "00000",
                "Description", "Подарочная карта на 1000 рублей");

        assertEquals(DOC_INIT_TOKEN, service.sign(params, DOC_PASSWORD));
    }

    @Test
    void signDoesNotDependOnParamOrderButOnPassword() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("OrderId", "00000");
        params.put("Description", "Подарочная карта на 1000 рублей");
        params.put("Amount", "19200");
        params.put("TerminalKey", DOC_TERMINAL);

        assertEquals(DOC_INIT_TOKEN, service.sign(params, DOC_PASSWORD));
        assertNotEquals(DOC_INIT_TOKEN, service.sign(params, "другой-пароль"));
    }

    @Test
    void verifiesNotificationIgnoringNestedObjectsAndNulls() throws Exception {
        assertTrue(service.verify(parse(notificationJson(NOTIFICATION_TOKEN)), DOC_PASSWORD));
    }

    @Test
    void acceptsUppercaseToken() throws Exception {
        assertTrue(service.verify(parse(notificationJson(NOTIFICATION_TOKEN.toUpperCase())), DOC_PASSWORD));
    }

    @Test
    void rejectsWrongTokenWrongPasswordAndTamperedField() throws Exception {
        assertFalse(service.verify(parse(notificationJson("0".repeat(64))), DOC_PASSWORD));
        assertFalse(service.verify(parse(notificationJson(NOTIFICATION_TOKEN)), "чужой-пароль"));

        Map<String, Object> tampered = parse(notificationJson(NOTIFICATION_TOKEN));
        tampered.put("Amount", 100);
        assertFalse(service.verify(tampered, DOC_PASSWORD));

        // Новое поле, о котором мы не знаем, тоже входит в подпись
        Map<String, Object> extraField = parse(notificationJson(NOTIFICATION_TOKEN));
        extraField.put("NewBankField", "x");
        assertFalse(service.verify(extraField, DOC_PASSWORD));
    }

    @Test
    void rejectsMissingOrBlankTokenAndNullPassword() throws Exception {
        Map<String, Object> noToken = parse(notificationJson(NOTIFICATION_TOKEN));
        noToken.remove("Token");
        assertFalse(service.verify(noToken, DOC_PASSWORD));
        assertFalse(service.verify(parse(notificationJson(" ")), DOC_PASSWORD));
        assertFalse(service.verify(parse(notificationJson(NOTIFICATION_TOKEN)), null));
    }

    @Test
    void scalarParamsUseBankTextForm() throws Exception {
        Map<String, String> params = TinkoffTokenService.scalarRootParams(parse(
                "{\"Success\":false,\"Amount\":19200,\"Big\":12345678901234567890,\"Status\":\"REJECTED\","
                        + "\"Token\":\"x\",\"Receipt\":{},\"Items\":[1],\"Empty\":null}"));

        assertEquals(Map.of(
                "Success", "false",
                "Amount", "19200",
                "Big", "12345678901234567890",
                "Status", "REJECTED"), params);
    }
}
