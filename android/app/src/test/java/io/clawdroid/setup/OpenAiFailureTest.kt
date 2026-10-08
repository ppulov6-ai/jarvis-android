package io.clawdroid.setup

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OpenAiFailureTest {
    @Test fun `local auth differs from upstream key rejection`() {
        assertEquals("local_gateway_auth", OpenAiFailure.from(null, 401).code)
        assertEquals("authentication", OpenAiFailure.from("authentication", 401).code)
    }
    @Test fun `quota rate region permissions are actionable distinct categories`() {
        val codes = listOf("quota", "rate_limit", "region", "permission", "model", "network")
        assertEquals(codes.size, codes.map { OpenAiFailure.from(it, 403).message }.toSet().size)
        codes.forEach { assertEquals(it, OpenAiFailure.from(it, 403).code) }
    }
    @Test fun `unknown server content cannot become user message or event code`() {
        val failure = OpenAiFailure.from("sk-secret-user-content", 503)
        assertEquals("upstream", failure.code)
        assertFalse(failure.message.contains("secret"))
    }
}
