package com.banking.cardservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts Card Service, which keeps the debit cards of the bank and the bank accounts each one
 * charges.
 */
@SpringBootApplication
public class CardServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(CardServiceApplication.class, args);
  }
}
