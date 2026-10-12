package ru.marketplace.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Супер-админы платформы — список почт из env ADMIN_SUPER_EMAILS (через запятую).
 * В БД роль не храним: список правится одной переменной и не требует миграций.
 */
@Component
public class SuperAdminResolver {

    private final Set<String> superAdminEmails;

    public SuperAdminResolver(@Value("${admin.super.emails}") String superAdminEmails) {
        this.superAdminEmails = superAdminEmails == null ? Set.of() : Arrays.stream(superAdminEmails.split(","))
                .map(SuperAdminResolver::normalize)
                .filter(Objects::nonNull)
                .filter(email -> !email.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (this.superAdminEmails.isEmpty()) {
            throw new IllegalStateException("ADMIN_SUPER_EMAILS не задан: без супер-админа некому включать и выключать магазины");
        }
    }

    public Set<String> emails() {
        return superAdminEmails;
    }

    public boolean isSuperAdmin(String email) {
        String normalized = normalize(email);
        return normalized != null && superAdminEmails.contains(normalized);
    }

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
