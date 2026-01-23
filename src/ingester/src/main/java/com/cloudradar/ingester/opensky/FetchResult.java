package com.cloudradar.ingester.opensky;

import java.util.List;

public record FetchResult(List<FlightState> states, Integer remainingCredits) {}
