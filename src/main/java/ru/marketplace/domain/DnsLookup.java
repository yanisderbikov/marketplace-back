package ru.marketplace.domain;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.naming.Context;
import javax.naming.NameNotFoundException;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Чтение DNS через JNDI (без кэша JVM): видна ли уже запись, которую мерчант добавил у регистратора.
 * Ошибка резолвера — null («не знаем»), отсутствие записи — пустое множество.
 */
@Slf4j
@Component
public class DnsLookup {

    /** Значения записей типа type (A, CNAME, TXT) без точки на конце и в нижнем регистре (кроме TXT). */
    public Set<String> lookup(String name, String type) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        InitialDirContext ctx = null;
        try {
            ctx = new InitialDirContext(env);
            Attributes attributes = ctx.getAttributes(name, new String[]{type});
            Attribute attribute = attributes.get(type);
            Set<String> values = new LinkedHashSet<>();
            if (attribute == null) {
                return values;
            }
            NamingEnumeration<?> all = attribute.getAll();
            while (all.hasMore()) {
                String value = String.valueOf(all.next()).trim();
                if ("TXT".equals(type)) {
                    values.add(value.replace("\"", ""));
                } else {
                    values.add(stripDot(value.toLowerCase(Locale.ROOT)));
                }
            }
            return values;
        } catch (NameNotFoundException e) {
            return Set.of();
        } catch (NamingException e) {
            log.debug("DNS {} {}: {}", type, name, e.toString());
            return null;
        } finally {
            if (ctx != null) {
                try {
                    ctx.close();
                } catch (NamingException ignored) {
                    // закрытие контекста DNS ничего не держит
                }
            }
        }
    }

    static String stripDot(String value) {
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    }
}
