package ru.marketplace.cdek;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import ru.marketplace.model.Shop;

import java.util.List;
import java.util.UUID;

/**
 * Магазины с подключённым СДЭК — чьими ключами обновлять общий справочник ПВЗ.
 * Сначала включённые и недавно сохранявшие настройки: их ключи вероятнее всего живые.
 */
interface CdekShopAccountRepository extends Repository<Shop, UUID> {

    @Query("""
            select s from Shop s
            where s.cdekClientId is not null and s.cdekClientSecretEnc is not null
            order by s.active desc, s.updatedAt desc
            """)
    List<Shop> findAllWithCdekKeys();
}
