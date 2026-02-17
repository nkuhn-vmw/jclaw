package com.jclaw.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityConfigTest {

    private final RbacService rbacService = new RbacService();

    private Authentication authWith(String... scopes) {
        Authentication auth = mock(Authentication.class);
        Collection<GrantedAuthority> authorities = new java.util.ArrayList<>();
        for (String scope : scopes) {
            authorities.add(new SimpleGrantedAuthority(scope));
        }
        doReturn(authorities).when(auth).getAuthorities();
        return auth;
    }

    @Test
    void adminHasAllPermissions() {
        Authentication auth = authWith("SCOPE_jclaw.admin");
        assertTrue(rbacService.isAdmin(auth));
        assertTrue(rbacService.isOperator(auth));
        assertTrue(rbacService.isUser(auth));
    }

    @Test
    void operatorCannotAccessAdmin() {
        Authentication auth = authWith("SCOPE_jclaw.operator");
        assertFalse(rbacService.isAdmin(auth));
        assertTrue(rbacService.isOperator(auth));
        assertTrue(rbacService.isUser(auth));
    }

    @Test
    void userHasLimitedPermissions() {
        Authentication auth = authWith("SCOPE_jclaw.user");
        assertFalse(rbacService.isAdmin(auth));
        assertFalse(rbacService.isOperator(auth));
        assertTrue(rbacService.isUser(auth));
    }

    @Test
    void serviceAccountIdentified() {
        Authentication auth = authWith("SCOPE_jclaw.service");
        assertTrue(rbacService.isService(auth));
        assertFalse(rbacService.isAdmin(auth));
    }

    @Test
    void getScopesReturnsJclawScopes() {
        Authentication auth = authWith("SCOPE_jclaw.admin", "SCOPE_jclaw.user", "SCOPE_openid");
        Set<String> scopes = rbacService.getScopes(auth);
        assertEquals(2, scopes.size());
        assertTrue(scopes.contains("SCOPE_jclaw.admin"));
        assertTrue(scopes.contains("SCOPE_jclaw.user"));
    }
}
