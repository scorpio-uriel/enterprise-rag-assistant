package com.acme.rag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan // enregistre les records @ConfigurationProperties
public class RagApplication {

  public static void main(String[] args) {
    SpringApplication.run(RagApplication.class, args);
  }
}
