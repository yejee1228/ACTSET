package com.actset.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthDtos {

    public record Agreements(boolean terms, boolean privacy, boolean marketing) {
    }

    public record SignupRequest(
            @Email @NotBlank String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            Agreements agreements,
            String terms_version
    ) {
    }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) {
    }

    public record AccountResponse(String id, String email, String role, int credit_balance) {
    }
}
