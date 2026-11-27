package com.banking.cardservice.application.port.out;

import com.banking.cardservice.domain.model.vo.CardNumber;

/** Source of numbers for new cards. */
@FunctionalInterface
public interface CardNumberGenerator {

  /** A new, valid card number. It may already be taken: the store says so. */
  CardNumber next();
}
