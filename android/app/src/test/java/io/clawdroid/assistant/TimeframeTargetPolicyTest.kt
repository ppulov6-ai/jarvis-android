package io.clawdroid.assistant

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TimeframeTargetPolicyTest {
    @Test fun `only bounded exact interval labels are recognized`() {
        listOf("1m", "5m", "15m", "1h", "4h", "1d", "1w", "1M", "15 мин", "4 ч", "1 мес.").forEach {
            assertNotNull(TimeframeTargetPolicy.canonical(it), it)
        }
        listOf("Buy", "Sell", "Send", "15", "100m", "1234h", "Buy 1m", "1m order", "0m", "-1m", "15 minutes", "").forEach {
            assertNull(TimeframeTargetPolicy.canonical(it), it)
        }
        assertEquals("1m", TimeframeTargetPolicy.canonical("1m"))
        assertEquals("1mo", TimeframeTargetPolicy.canonical("1M"))
        assertNull(TimeframeTargetPolicy.labels("1m", "Buy"))
        assertNull(TimeframeTargetPolicy.labels("1m", "1h"))
    }
    @Test fun `quote tolerance excludes integer identifiers dates phones and labelled values`() {
        listOf("100.01", "-0.25%", "+1.25%", "$100.50", "100,01").forEach { assertTrue(isLiveNumericQuote(it), it) }
        listOf("100", "Account 100.50", "BTC 100.50", "09.10.2026", "+7 999 123 45 67", "79001234567", "1.2.3", "").forEach {
            assertFalse(isLiveNumericQuote(it), it)
        }
    }
}
