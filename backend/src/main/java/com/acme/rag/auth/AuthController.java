package com.acme.rag.auth;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @PostMapping("/auth/login")
  public LoginResponse login(@Valid @RequestBody LoginRequest request) {
    return authService.login(request);
  }

  /** {@code @AuthenticationPrincipal} injecte le JWT déjà validé par le Resource Server. */
  @GetMapping("/me")
  public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
    List<String> roles = jwt.getClaimAsStringList(TokenService.ROLES_CLAIM);
    return new MeResponse(jwt.getSubject(), Role.valueOf(roles.getFirst()));
  }
}
