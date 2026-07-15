package com.soma.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudModelAvailabilityTest {
    @Test
    fun `missing or retired models are recognized`() {
        assertTrue(cloudModelUnavailable(404, ""))
        assertTrue(cloudModelUnavailable(400, """{"error":{"code":"model_decommissioned"}}"""))
        assertTrue(cloudModelUnavailable(403, """{"error":{"code":"model_terms_required"}}"""))
    }

    @Test
    fun `account level failures are not model unavailability`() {
        assertFalse(cloudModelUnavailable(401, """{"error":{"code":"invalid_api_key"}}"""))
        assertFalse(cloudModelUnavailable(402, """{"error":{"code":"quota_exceeded"}}"""))
        assertFalse(cloudModelUnavailable(429, """{"error":{"code":"rate_limit_exceeded"}}"""))
        assertFalse(cloudModelUnavailable(503, ""))
    }
}
