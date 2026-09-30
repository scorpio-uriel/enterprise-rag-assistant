package com.acme.rag.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class DataSeederTest {

  private final UserRepository repository = mock(UserRepository.class);
  private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
  private final DataSeeder seeder =
      new DataSeeder(repository, encoder, new SeedProperties("admin-secret", "user-secret"));

  @Test
  void storesBcryptHashNeverPlainPassword() { // AC1.4
    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);

    seeder.run(null);

    verify(repository, times(2)).save(saved.capture());
    List<User> users = saved.getAllValues();
    assertThat(users)
        .extracting(User::getEmail, User::getRole)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("admin@acme.local", Role.ADMIN),
            org.assertj.core.groups.Tuple.tuple("user@acme.local", Role.USER));

    User user = users.get(1);
    assertThat(user.getPasswordHash()).matches("^\\$2[ab]\\$.*").isNotEqualTo("user-secret");
    assertThat(encoder.matches("user-secret", user.getPasswordHash())).isTrue();
  }

  @Test
  void doesNothingWhenAccountsAlreadyExist() {
    when(repository.existsByEmail(any())).thenReturn(true);

    seeder.run(null);

    verify(repository, never()).save(any());
  }
}
