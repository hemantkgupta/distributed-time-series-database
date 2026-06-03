package com.hkg.tsdb.tenant;

/**
 * Raised when an ingest would create a new active series above tenant quota.
 */
public final class CardinalityLimitExceededException extends RuntimeException {

    public CardinalityLimitExceededException(String message) {
        super(message);
    }
}
