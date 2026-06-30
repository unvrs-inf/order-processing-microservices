package com.example.orders.auth.service;

import com.example.orders.auth.api.AuthResponse;
import com.example.orders.auth.api.LoginRequest;
import com.example.orders.auth.api.RegisterRequest;
import com.example.orders.auth.api.UserResponse;
import com.example.orders.auth.domain.Role;
import com.example.orders.auth.domain.UserAccount;
import com.example.orders.auth.domain.UserAccountRepository;
import com.example.orders.auth.error.DuplicateEmailException;
import com.example.orders.auth.error.InvalidCredentialsException;
import com.example.orders.auth.error.UnauthenticatedException;
import com.example.orders.auth.security.TokenService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Регистрация, вход и чтение учётной записи из токена. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    AuthService(UserAccountRepository users, PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    /**
     * Создаёт учётную запись {@link Role#USER}. Уникальное ограничение обрабатывает конкурентную регистрацию с тем же адресом.
     * @param request нормализованные данные регистрации
     * @return токен новой учётной записи
     * @throws DuplicateEmailException если адрес уже занят
     */
    public AuthResponse register(RegisterRequest request) {
        String email = request.email();
        if (users.existsByEmail(email)) {
            throw new DuplicateEmailException(email);
        }

        UserAccount user = UserAccount.create(
                request.name(), email, passwordEncoder.encode(request.password()), Role.USER);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Другой запрос первым зарегистрировал тот же адрес.
            log.debug("Unique violation while registering {}", email, e);
            throw new DuplicateEmailException(email);
        }
        log.info("Registered account {} ({})", user.getId(), email);
        return respond(user);
    }

    /**
     * @param request нормализованные данные входа
     * @return токен учётной записи
     * @throws InvalidCredentialsException при неизвестном адресе или неверном пароле
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        UserAccount user = users.findByEmail(request.email())
                .orElseThrow(InvalidCredentialsException::new);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        return respond(user);
    }

    /**
     * @param subject значение поля {@code sub}
     * @return учётная запись владельца токена
     * @throws UnauthenticatedException если учётная запись отсутствует
     */
    @Transactional(readOnly = true)
    public UserResponse currentUser(String subject) {
        UUID id;
        try {
            id = UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new UnauthenticatedException();
        }
        return users.findById(id)
                .map(UserResponse::from)
                .orElseThrow(UnauthenticatedException::new);
    }

    private AuthResponse respond(UserAccount user) {
        TokenService.IssuedToken token = tokenService.issue(user);
        return AuthResponse.bearer(token.value(), token.expiresInSeconds(), UserResponse.from(user));
    }
}
