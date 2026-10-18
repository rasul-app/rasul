package app.rasul

import app.rasul.bridge.nextApprovalExpiryDelayMs
import org.junit.Assert.*
import org.junit.Test

/**
 * The banner/badge ticker re-reads the clock when the soonest pending approval expires so the
 * counts clear themselves (RasulNav approvalsNowSec). This tests its delay calculation.
 */
class ApprovalsTickerTest {
    private val now = 1_900_000_000L

    @Test fun nothingPendingRestsTheTicker() {
        assertNull(nextApprovalExpiryDelayMs(emptyList(), now))
        assertNull(nextApprovalExpiryDelayMs(listOf(now - 10.0, now - 1.0), now))
    }

    @Test fun wakesAtTheSoonestPendingExpiry() {
        // Expired entries and far-future ones are ignored; the earliest pending drives the delay.
        assertEquals(1_100L, nextApprovalExpiryDelayMs(listOf(now - 5.0, now + 30.0, now + 1.0), now))
    }

    @Test fun delayIsExpiryPlusAMinuteBoundaryGuard() {
        // expiry at now+1 -> 1000 ms + 100 ms guard so the tick lands just past the boundary.
        assertEquals(1_100L, nextApprovalExpiryDelayMs(listOf(now + 1.0), now))
        assertEquals(30_100L, nextApprovalExpiryDelayMs(listOf(now + 30.0), now))
    }

    @Test fun subSecondExpiryStillWaitsAMinimum() {
        // A request expiring inside the same second still waits the floor, not 0 or negative.
        assertEquals(300L, nextApprovalExpiryDelayMs(listOf(now + 0.05), now))
        assertEquals(300L, nextApprovalExpiryDelayMs(listOf(now + 0.199), now))
    }
}
