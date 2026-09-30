package com.acme.rag.auth;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

  private final AuthenticationManager authenticationManager;
  private final TokenService tokenService;

  public AuthService(AuthenticationManager authenticationManager, TokenService tokenService) {
    this.authenticationManager = authenticationManager;
    this.tokenService = tokenService;
  }

  /**
   * Vérifie email et mot de passe, puis émet un JWT. En cas d'échec, une {@code
   * BadCredentialsException} est levée, que l'email existe ou non (AC1.2).
   */
  public LoginResponse login(LoginRequest request) {
    Authentication authentication =
        authenticationManager.authenticate(
            UsernamePasswordAuthenticationToken.unauthenticated(
                request.email(), request.password()));
    Role role = roleOf(authentication);
    IssuedToken issued = tokenService.issue(authentication.getName(), role);
    return new LoginResponse(issued.token(), role, issued.expiresAt());
  }

  private static Role roleOf(Authentication authentication) {
    return authentication.getAuthorities().stream()
        .map(a -> a.getAuthority())
        .filter(a -> a.startsWith("ROLE_"))
        .map(a -> Role.valueOf(a.substring("ROLE_".length())))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Utilisateur sans rôle"));
  }
}
