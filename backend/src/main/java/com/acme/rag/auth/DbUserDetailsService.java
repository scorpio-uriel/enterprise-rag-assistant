package com.acme.rag.auth;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Pont entre Spring Security et notre table : le {@code DaoAuthenticationProvider} appelle cette
 * méthode au login, puis compare lui-même le mot de passe avec le hash BCrypt.
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

  private final UserRepository userRepository;

  public DbUserDetailsService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  public UserDetails loadUserByUsername(String email) {
    return userRepository
        .findByEmail(email)
        .map(
            user ->
                org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                    .password(user.getPasswordHash())
                    .roles(user.getRole().name())
                    .build())
        .orElseThrow(() -> new UsernameNotFoundException("Utilisateur inconnu"));
  }
}
