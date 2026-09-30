package com.acme.rag.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code ApplicationRunner} : exécuté une fois, juste après le démarrage du contexte. Crée les
 * comptes de démo s'ils n'existent pas encore (idempotent).
 */
@Component
public class DataSeeder implements ApplicationRunner {

  static final String ADMIN_EMAIL = "admin@acme.local";
  static final String USER_EMAIL = "user@acme.local";

  private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final SeedProperties seed;

  public DataSeeder(
      UserRepository userRepository, PasswordEncoder passwordEncoder, SeedProperties seed) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.seed = seed;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    createIfMissing(ADMIN_EMAIL, seed.adminPassword(), Role.ADMIN);
    createIfMissing(USER_EMAIL, seed.userPassword(), Role.USER);
  }

  private void createIfMissing(String email, String rawPassword, Role role) {
    if (userRepository.existsByEmail(email)) {
      return;
    }
    userRepository.save(new User(email, passwordEncoder.encode(rawPassword), role));
    log.info("Compte de démo créé : {} ({})", email, role);
  }
}
