package com.rag2agent.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class TokenCostPropertiesTest {

    @Test
    void resolvesWeekdayPeakPrice() {
        TokenCostProperties.Price price = deepSeekPrice();

        var resolved = price.resolve(Instant.parse("2026-09-01T06:30:00Z"));

        assertEquals(0.44, resolved.promptPerMillion());
        assertEquals(1.32, resolved.completionPerMillion());
        assertEquals("official-peak", resolved.version());
    }

    @Test
    void resolvesWeekdayOffPeakPrice() {
        TokenCostProperties.Price price = deepSeekPrice();

        var resolved = price.resolve(Instant.parse("2026-09-01T11:30:00Z"));

        assertEquals(0.22, resolved.promptPerMillion());
        assertEquals(0.66, resolved.completionPerMillion());
        assertEquals("official-off-peak", resolved.version());
    }

    @Test
    void weekendsRemainOffPeak() {
        TokenCostProperties.Price price = deepSeekPrice();

        var resolved = price.resolve(Instant.parse("2026-09-05T06:30:00Z"));

        assertEquals(0.22, resolved.promptPerMillion());
        assertEquals("official-off-peak", resolved.version());
    }

    private TokenCostProperties.Price deepSeekPrice() {
        TokenCostProperties.Price price = new TokenCostProperties.Price();
        price.setVersion("official");
        price.setPromptPerMillion(0.22);
        price.setCompletionPerMillion(0.66);
        price.setCacheReadPerMillion(0.007);
        price.setPeakMultiplier(2);
        price.setPeakWindowsUtc("01:00-04:00,06:00-10:00");
        price.setPeakWeekdaysOnly(true);
        return price;
    }
}
