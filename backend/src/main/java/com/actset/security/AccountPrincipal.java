package com.actset.security;

import com.actset.domain.Account;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** 세션에 담기는 인증 주체. accountId를 바로 꺼낼 수 있게 Account를 감싼다. */
public class AccountPrincipal implements UserDetails {

    private final Account account;

    public AccountPrincipal(Account account) {
        this.account = account;
    }

    public UUID getAccountId() {
        return account.getId();
    }

    public Account getAccount() {
        return account;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().toUpperCase()));
    }

    @Override
    public String getPassword() {
        return account.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return account.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return account.isActive();
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return account.isActive();
    }
}
