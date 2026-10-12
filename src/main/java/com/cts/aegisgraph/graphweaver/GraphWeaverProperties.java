package com.cts.aegisgraph.graphweaver;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("aegis.graphweaver")
public record GraphWeaverProperties(
        @DefaultValue("48h") Duration temporaryRetention,
        @DefaultValue("100000") double permanentAmountThreshold,
        @DefaultValue("60m") Duration window,            // rolling detection window
        @DefaultValue("10m") Duration fanInWindow,       // max lag between hop 1 and hop 2
        @DefaultValue("20") int minIntermediaries,
        @DefaultValue("10000") double structuringCeiling, // per-transfer amount must stay below
        @DefaultValue("0.1") double amountTolerance,     // max value lost per hop (fees/skim)
        @DefaultValue("3") int minCycleHops,
        @DefaultValue("6") int maxCycleHops) {}
