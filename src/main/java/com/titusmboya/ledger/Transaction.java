package com.titusmboya.ledger;

/** An immutable record of a completed transfer. Amounts are in minor units (cents). */
public record Transaction(String id, String from, String to, long amountCents, String createdAt) { }
