package ru.marketplace.domain;

/** Внешний API подключения доменов не ответил или отказал; текст — для лога, мерчанту отдаём общий. */
public class DomainProvisioningException extends RuntimeException {
    public DomainProvisioningException(String message) {
        super(message);
    }

    public DomainProvisioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
