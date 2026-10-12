package ru.marketplace.util;

public final class PhoneUtil {

    private PhoneUtil() {
    }

    /** Номер в цифрах E.164 без «+»: 8 (999) 123-45-67 → 79991234567; null, если это не похоже на телефон. */
    public static String toE164(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() == 10) {
            return "7" + digits;
        }
        if (digits.length() == 11 && digits.startsWith("8")) {
            return "7" + digits.substring(1);
        }
        if (digits.length() >= 11 && digits.length() <= 15) {
            return digits;
        }
        return null;
    }

    /** Последние 10 цифр номера для сравнения клиентов по телефону; пусто, если цифр меньше. */
    public static String last10(String phone) {
        if (phone == null) {
            return "";
        }
        String digits = phone.replaceAll("\\D", "");
        return digits.length() >= 10 ? digits.substring(digits.length() - 10) : "";
    }
}
