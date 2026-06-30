package com.example.orders.auth.api;

import com.example.orders.auth.security.AuthJwtKeys;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Публикует открытую часть ключа подписи в виде JWK Set для шлюза и сервисов.
 */
@RestController
public class JwksController {

    /** Стандартный путь публикации JWK Set. */
    public static final String PATH = "/.well-known/jwks.json";

    private final AuthJwtKeys keys;

    JwksController(AuthJwtKeys keys) {
        this.keys = keys;
    }

    /** @return JWK Set с одним открытым ключом RSA */
    @GetMapping(path = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jwks() {
        return keys.publicJwkSet().toJSONObject();
    }
}
