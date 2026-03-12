package com.cloudradar.ingester.opensky;

record OpenSkyRateLimitHeaders(
    Integer remainingCredits,
    Integer creditLimit,
    Long resetAtEpochSeconds) {}
