package ru.marketplace.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.marketplace.model.LoginCode;

import java.util.Optional;

/**
 * Чтение кода входа с блокировкой строки (SELECT … FOR UPDATE).
 * Без неё параллельные проверки одного кода читали бы одно и то же attempts и теряли
 * инкременты — пачкой одновременных запросов можно было бы перебрать код в обход лимита попыток.
 * Отдельный репозиторий, а не метод в LoginCodeRepository: блокировка нужна только модулю входа.
 */
interface LoginCodeLockRepository extends Repository<LoginCode, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from LoginCode c where c.email = :email")
    Optional<LoginCode> findForUpdate(@Param("email") String email);

    /**
     * Пустая строка-заглушка для почты, у которой кода ещё нет, — чтобы первому запросу кода было что
     * заблокировать. Без неё два одновременных первых запроса (двойной клик) оба не находили строку,
     * оба слали письмо, а второй падал на PK при коммите (500, и код из его письма не работал).
     * С заглушкой второй INSERT … ON CONFLICT ждёт коммита первого и дальше видит паузу на повтор.
     * Заглушка не переживает транзакцию сама по себе: в той же транзакции её перезаписывает настоящий
     * код, а при ошибке письма она откатывается вместе с ним. sent_at = epoch — пауза на повтор не действует,
     * пустой code_hash не совпадёт ни с одним HMAC, окна неверных кодов ещё нет.
     */
    @Modifying
    @Query(value = """
            INSERT INTO login_code (email, code_hash, expires_at, sent_at, attempts, fail_window_start, fails_in_window)
            VALUES (:email, '', 'epoch', 'epoch', 0, NULL, 0)
            ON CONFLICT (email) DO NOTHING
            """, nativeQuery = true)
    int insertPlaceholderIfAbsent(@Param("email") String email);
}
