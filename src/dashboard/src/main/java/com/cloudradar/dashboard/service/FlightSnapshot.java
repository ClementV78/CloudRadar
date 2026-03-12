package com.cloudradar.dashboard.service;

import com.cloudradar.dashboard.model.PositionEvent;

record FlightSnapshot(
    String icao24,
    PositionEvent event,
    String category,
    String country,
    String typecode,
    Boolean militaryHint,
    String airframeType,
    String ownerOperator) {}
