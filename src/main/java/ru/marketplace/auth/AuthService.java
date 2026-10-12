package ru.marketplace.auth;

import ru.marketplace.auth.dto.LoginResponseDTO;
import ru.marketplace.auth.dto.MeDTO;

public interface AuthService {
    void requestLoginCode(String email);

    LoginResponseDTO verifyLoginCode(String email, String code);

    /** Текущий мерчант из SecurityContext (401, если его уже нет в БД). */
    MeDTO me();
}
