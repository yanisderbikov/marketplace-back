package ru.marketplace.cdek.dto;

/** Точка для калькулятора СДЭК (from_location / to_location): страна, индекс, город, адрес. */
public record CdekLocation(String countryCode, String postalCode, String city, String address) {

    public static CdekLocation fromPvz(CdekPvzDTO pvz) {
        return new CdekLocation(pvz.getCountryCode(), pvz.getPostalCode(), pvz.getCity(), pvz.getAddress());
    }
}
