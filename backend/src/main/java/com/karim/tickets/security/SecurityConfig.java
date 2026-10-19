package com.karim.tickets.security;

import com.karim.tickets.config.AppProperties;
import com.karim.tickets.service.AuthService;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtService jwt, AppProperties props,
                            ObjectProvider<ClientRegistrationRepository> oauthClients, AuthService auth) throws Exception {
        boolean google = oauthClients.getIfAvailable() != null;
        http.csrf(c -> c.disable()) // stateless bearer-token API; no cookies are used for auth
                .cors(c -> {})
                .sessionManagement(s -> s.sessionCreationPolicy(
                        google ? SessionCreationPolicy.IF_REQUIRED : SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // SSE responses finish on an async dispatch; the original request was already authorized.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/payments/webhook",
                                "/actuator/health/**", "/oauth2/**", "/login/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/events", "/api/events/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(jwt.decoder()).jwtAuthenticationConverter(rolesConverter())));
        if (google) {
            // "Sign in with Google": find-or-create the user, then hand the SPA our own JWT.
            http.oauth2Login(o -> o.successHandler((req, res, authn) -> {
                OAuth2User u = (OAuth2User) authn.getPrincipal();
                var session = auth.oauthLogin(u.getAttribute("email"), u.getAttribute("name"), "GOOGLE");
                res.sendRedirect(props.frontendUrl() + "/oauth/callback#token=" + session.token());
            }));
        }
        return http.build();
    }

    static JwtAuthenticationConverter rolesConverter() {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<String> roles = jwt.getClaimAsStringList("roles");
            Collection<GrantedAuthority> out = roles == null ? List.of()
                    : roles.stream().map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList();
            return out;
        });
        return c;
    }

    @Bean
    JwtDecoder jwtDecoder(JwtService jwt) {
        return jwt.decoder();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of(props.frontendUrl()));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
