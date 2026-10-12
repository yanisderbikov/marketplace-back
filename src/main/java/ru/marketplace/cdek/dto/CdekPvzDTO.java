package ru.marketplace.cdek.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Пункт выдачи СДЭК: кэш в таблице cdek_pvz и ответ поиска ПВЗ на витрине. */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CdekPvzDTO {
    private String code;
    private String name;
    private String countryCode;
    private String region;
    private String city;
    private String postalCode;
    private String address;
    private String fullAddress;
    private String workTime;
    private Double longitude;
    private Double latitude;
}
