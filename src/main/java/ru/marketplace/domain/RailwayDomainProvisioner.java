package ru.marketplace.domain;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import ru.marketplace.domain.dto.DnsRecordDTO;
import ru.marketplace.domain.dto.ShopDomainDTO;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Railway: свой домен магазина — custom domain сервиса гейтвея. Railway выдаёт под него CNAME
 * (и TXT для подтверждения владения) и сам выпускает сертификат, когда записи появятся в DNS.
 * Ходим в публичный GraphQL API Railway токеном проекта (RAILWAY_API_TOKEN).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "domains.provisioner", havingValue = "railway")
public class RailwayDomainProvisioner implements DomainProvisioner {

    static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String VERIFY_PREFIX = "railway-verify=";

    private static final String STATUS_FIELDS = """
            id domain status {
              verified verificationDnsHost verificationToken certificateStatus certificateErrorMessage
              dnsRecords { hostlabel fqdn zone recordType requiredValue currentValue status purpose }
            }""";
    private static final String FIND = """
            query($p: String!, $e: String!, $s: String!) {
              domains(projectId: $p, environmentId: $e, serviceId: $s) { customDomains { %s } }
            }""".formatted(STATUS_FIELDS);
    private static final String CREATE = """
            mutation($input: CustomDomainCreateInput!) { customDomainCreate(input: $input) { %s } }
            """.formatted(STATUS_FIELDS);
    private static final String DELETE = "mutation($id: String!) { customDomainDelete(id: $id) }";

    /** null — токен не задан: магазины и витрины работают, а подключение доменов отвечает 502 до появления токена */
    private final WebClient webClient;
    private final String projectId;
    private final String environmentId;
    private final String serviceId;
    private final int targetPort;

    public RailwayDomainProvisioner(@Value("${domains.railway.api-url}") String apiUrl,
                                    @Value("${domains.railway.token}") String token,
                                    @Value("${domains.railway.token-kind}") String tokenKind,
                                    @Value("${domains.railway.project-id}") String projectId,
                                    @Value("${domains.railway.environment-id}") String environmentId,
                                    @Value("${domains.railway.service-id}") String serviceId,
                                    @Value("${domains.railway.target-port}") String targetPort) {
        this.projectId = require(projectId, "RAILWAY_PROJECT_ID");
        this.environmentId = require(environmentId, "RAILWAY_ENVIRONMENT_ID");
        this.serviceId = require(serviceId, "RAILWAY_GATEWAY_SERVICE_ID");
        this.targetPort = Integer.parseInt(require(targetPort, "RAILWAY_GATEWAY_PORT"));
        // Токен проекта идёт своим заголовком, токен аккаунта/воркспейса — Bearer
        String kind = require(tokenKind, "RAILWAY_API_TOKEN_KIND").toLowerCase(Locale.ROOT);
        if (token == null || token.isBlank()) {
            // Без токена не роняем весь бэкенд: свой домен — необязательная функция кабинета
            log.error("RAILWAY_API_TOKEN не задан: свои домены витрин подключить нельзя, пока его не добавят");
            this.webClient = null;
            return;
        }
        String secret = token.trim();
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(apiUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
        switch (kind) {
            case "project" -> builder.defaultHeader("Project-Access-Token", secret);
            case "account" -> builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + secret);
            default -> throw new IllegalStateException("RAILWAY_API_TOKEN_KIND: project или account, а не " + kind);
        }
        this.webClient = builder.build();
    }

    @Override
    public ShopDomainDTO attach(String domain) {
        Optional<JsonNode> existing = find(domain);
        if (existing.isPresent()) {
            return toDto(domain, existing.get());
        }
        JsonNode created = call(CREATE, Map.of("input", Map.of(
                "domain", domain,
                "projectId", projectId,
                "environmentId", environmentId,
                "serviceId", serviceId,
                "targetPort", targetPort))).path("customDomainCreate");
        log.info("Домен {} подключён к гейтвею в Railway ({})", domain, created.path("id").asText());
        return toDto(domain, created);
    }

    @Override
    public ShopDomainDTO describe(String domain) {
        return find(domain).map(node -> toDto(domain, node))
                // Домен в магазине есть, а в Railway нет (удалили руками) — подключаем заново
                .orElseGet(() -> attach(domain));
    }

    @Override
    public void detach(String domain) {
        Optional<JsonNode> existing = find(domain);
        if (existing.isEmpty()) {
            return;
        }
        call(DELETE, Map.of("id", existing.get().path("id").asText()));
        log.info("Домен {} отключён от гейтвея в Railway", domain);
    }

    private Optional<JsonNode> find(String domain) {
        JsonNode domains = call(FIND, Map.of("p", projectId, "e", environmentId, "s", serviceId))
                .path("domains").path("customDomains");
        for (JsonNode node : domains) {
            if (domain.equalsIgnoreCase(node.path("domain").asText())) {
                return Optional.of(node);
            }
        }
        return Optional.empty();
    }

    private JsonNode call(String query, Map<String, Object> variables) {
        if (webClient == null) {
            throw new DomainProvisioningException("RAILWAY_API_TOKEN не задан");
        }
        JsonNode response;
        try {
            response = webClient.post()
                    .bodyValue(Map.of("query", query, "variables", variables))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(TIMEOUT)
                    .block();
        } catch (WebClientResponseException e) {
            throw new DomainProvisioningException("Railway API ответил " + e.getStatusCode() + ": "
                    + e.getResponseBodyAsString(), e);
        } catch (RuntimeException e) {
            throw new DomainProvisioningException("Railway API недоступен: " + e, e);
        }
        if (response == null) {
            throw new DomainProvisioningException("Railway API: пустой ответ");
        }
        JsonNode errors = response.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new DomainProvisioningException("Railway API: " + errors);
        }
        return response.path("data");
    }

    /** Ответ Railway → записи для регистратора. TXT подтверждения Railway отдаёт отдельно от dnsRecords. */
    static ShopDomainDTO toDto(String domain, JsonNode node) {
        JsonNode status = node.path("status");
        List<DnsRecordDTO> records = new ArrayList<>();
        String zone = null;
        boolean hasTxt = false;
        for (JsonNode r : status.path("dnsRecords")) {
            String type = enumTail(r.path("recordType").asText(), "DNS_RECORD_TYPE_");
            String fqdn = r.path("fqdn").asText(domain);
            if (zone == null && !r.path("zone").asText().isBlank()) {
                zone = r.path("zone").asText();
            }
            hasTxt |= "TXT".equals(type);
            String purpose = r.path("purpose").asText().contains("TRAFFIC") ? DnsRecordDTO.ROUTING : DnsRecordDTO.VERIFICATION;
            records.add(new DnsRecordDTO(type, hostOrApex(r.path("hostlabel").asText()), fqdn,
                    r.path("requiredValue").asText(), purpose,
                    "DNS_RECORD_STATUS_PROPAGATED".equals(r.path("status").asText())));
        }
        boolean verified = status.path("verified").asBoolean(false);
        String verifyHost = status.path("verificationDnsHost").asText("");
        String token = status.path("verificationToken").asText("");
        if (!hasTxt && !verifyHost.isBlank() && !token.isBlank()) {
            String value = token.startsWith(VERIFY_PREFIX) ? token : VERIFY_PREFIX + token;
            String fqdn = zone == null ? verifyHost : verifyHost + "." + zone;
            records.add(new DnsRecordDTO("TXT", verifyHost, fqdn, value, DnsRecordDTO.VERIFICATION, verified));
        }

        String certificate = status.path("certificateStatus").asText("");
        if (certificate.endsWith("_VALID")) {
            return new ShopDomainDTO(domain, ShopDomainDTO.ACTIVE, "Домен подключён, сертификат HTTPS выпущен.", records);
        }
        if (certificate.endsWith("_ISSUE_FAILED")) {
            String error = status.path("certificateErrorMessage").asText("");
            return new ShopDomainDTO(domain, ShopDomainDTO.ERROR,
                    "Не удалось выпустить сертификат" + (error.isBlank() ? "" : ": " + error)
                            + ". Проверьте записи и нажмите «Проверить» ещё раз.", records);
        }
        return new ShopDomainDTO(domain, ShopDomainDTO.PENDING,
                "Добавьте записи у регистратора домена. Когда они появятся в DNS (от нескольких минут до суток), "
                        + "сертификат HTTPS выпустится сам.", records);
    }

    private static String hostOrApex(String host) {
        return host == null || host.isBlank() ? "@" : host;
    }

    private static String enumTail(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " не задан (DOMAIN_PROVISIONER=railway)");
        }
        return value.trim();
    }
}
