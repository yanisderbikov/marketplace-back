package ru.marketplace.common;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/** Страница списка для фронта: { items, total, page (с 0), size }. */
public record PageDTO<T>(List<T> items, long total, int page, int size) {

    public static <T> PageDTO<T> of(Page<T> page) {
        return new PageDTO<>(page.getContent(), page.getTotalElements(), page.getNumber(), page.getSize());
    }

    /** Страница сущностей → страница DTO. */
    public static <E, T> PageDTO<T> of(Page<E> page, Function<? super E, ? extends T> mapper) {
        List<T> items = page.getContent().stream().<T>map(mapper).toList();
        return new PageDTO<>(items, page.getTotalElements(), page.getNumber(), page.getSize());
    }
}
