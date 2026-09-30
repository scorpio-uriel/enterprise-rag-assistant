package com.acme.rag.auth;

import java.time.Instant;

public record LoginResponse(String token, Role role, Instant expiresAt) {}
