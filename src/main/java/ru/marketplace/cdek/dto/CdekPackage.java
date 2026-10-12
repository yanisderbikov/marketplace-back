package ru.marketplace.cdek.dto;

/** Одно место посылки для калькулятора СДЭК: вес в граммах, габариты в сантиметрах. */
public record CdekPackage(int weightGrams, int lengthCm, int widthCm, int heightCm) {
}
