package com.jclaw.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RbacService {

    public boolean isAdmin(Authentication auth) {
        return hasScope(auth, "SCOPE_jclaw.admin");
    }

    public boolean isOperator(Authentication auth) {
        return hasScope(auth, "SCOPE_jclaw.operator") || isAdmin(auth);
    }

    public boolean isUser(Authentication auth) {
        return hasScope(auth, "SCOPE_jclaw.user") || isOperator(auth);
    }

    public boolean isService(Authentication auth) {
        return hasScope(auth, "SCOPE_jclaw.service");
    }

    public Set<String> getScopes(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("SCOPE_jclaw."))
                .collect(Collectors.toSet());
    }

    private boolean hasScope(Authentication auth, String scope) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals(scope));
    }
}
