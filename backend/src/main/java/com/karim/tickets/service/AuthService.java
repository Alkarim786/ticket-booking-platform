package com.karim.tickets.service;

import com.karim.tickets.domain.Enums.Role;
import com.karim.tickets.domain.User;
import com.karim.tickets.repo.Repos.UserRepository;
import com.karim.tickets.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwt;

    public AuthService(UserRepository users, PasswordEncoder passwords, JwtService jwt) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
    }

    public record Session(String token, User user) {}

    @Transactional
    public Session register(String email, String password, String name) {
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("EMAIL_TAKEN", "an account with this email already exists");
        }
        User u = users.save(new User(email.toLowerCase(), passwords.encode(password), name, Role.USER, "LOCAL"));
        return new Session(jwt.issue(u), u);
    }

    public Session login(String email, String password) {
        User u = users.findByEmailIgnoreCase(email)
                .filter(x -> x.getPasswordHash() != null && passwords.matches(password, x.getPasswordHash()))
                // Same error for unknown email and wrong password: don't leak which accounts exist.
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "invalid email or password"));
        return new Session(jwt.issue(u), u);
    }

    /** Find-or-create for OAuth2 (Google) sign-in; such accounts have no local password. */
    @Transactional
    public Session oauthLogin(String email, String name, String provider) {
        User u = users.findByEmailIgnoreCase(email)
                .orElseGet(() -> users.save(new User(email.toLowerCase(), null, name, Role.USER, provider)));
        return new Session(jwt.issue(u), u);
    }

    public User get(long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("user"));
    }
}
