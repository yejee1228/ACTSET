package com.actset.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * MVP는 세션 쿠키 인증 + 같은 오리진 내장 배포가 기준이다(docs/09).
     * CSRF 토큰 발급·검증은 1-22에서 레이트리밋과 함께 구현한다 — 그 전까지는 세션 쿠키를
     * SameSite=Lax로 두어 단순 크로스사이트 요청 위조를 완화한다(아래 쿠키 설정 참고).
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/signup", "/api/v1/auth/login",
                        "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm").permitAll()
                .requestMatchers("/api/v1/formats").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().permitAll()
            )
            .exceptionHandling(ex -> ex.authenticationEntryPoint(unauthorizedEntryPoint()))
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())
            .logout(logout -> logout
                .logoutUrl("/api/v1/auth/logout")
                .logoutSuccessHandler((req, res, authentication) -> res.setStatus(HttpStatus.NO_CONTENT.value()))
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            );
        return http.build();
    }

    /** 미인증 요청은 로그인 화면 리다이렉트가 아니라 401 JSON으로 응답한다(docs/11). */
    private org.springframework.security.web.AuthenticationEntryPoint unauthorizedEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            new ObjectMapper().writeValue(response.getWriter(),
                    java.util.Map.of("error", java.util.Map.of(
                            "code", "UNAUTHENTICATED", "message", "로그인이 필요합니다.")));
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
