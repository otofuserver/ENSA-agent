package com.edamame.agent.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogEntryTest {

    @Test
    void constructorShouldApplySafeDefaultsWhenNullsAreProvided() {
        LogEntry entry = new LogEntry(
            null,
            null,
            null,
            200,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            false
        );

        assertEquals("", entry.clientIp());
        assertEquals("", entry.timestamp());
        assertEquals("", entry.request());
        assertEquals("-", entry.responseSize());
        assertEquals("", entry.referer());
        assertEquals("", entry.userAgent());
        assertEquals("", entry.sourcePath());
        assertEquals("", entry.serverName());
        assertEquals("nginx", entry.logTag());
        assertNotNull(entry.collectedAt());
    }

    @Test
    void requestHelpersShouldExtractMethodUrlAndVersion() {
        LogEntry entry = new LogEntry(
            "127.0.0.1",
            "01/Jan/2026:00:00:00 +0900",
            "POST /api/v1/login HTTP/2",
            403,
            "123",
            "-",
            "JUnit",
            "access.log",
            "main-nginx",
            "nginx",
            "2026-01-01T00:00:00",
            true
        );

        assertEquals("POST", entry.getHttpMethod());
        assertEquals("/api/v1/login", entry.getRequestUrl());
        assertEquals("HTTP/2", entry.getHttpVersion());
        assertTrue(entry.isErrorResponse());
        assertTrue(entry.isClientError());
        assertFalse(entry.isServerError());
        assertEquals(123L, entry.getResponseSizeBytes());
        assertTrue(entry.getSummary().contains("POST /api/v1/login"));
    }

    @Test
    void responseSizeShouldBeZeroForInvalidOrDashValue() {
        LogEntry dashEntry = new LogEntry(
            "127.0.0.1",
            "",
            "GET / HTTP/1.1",
            200,
            "-",
            "",
            "",
            "",
            "",
            "nginx",
            "2026-01-01T00:00:00",
            false
        );
        LogEntry invalidEntry = new LogEntry(
            "127.0.0.1",
            "",
            "GET / HTTP/1.1",
            200,
            "abc",
            "",
            "",
            "",
            "",
            "nginx",
            "2026-01-01T00:00:00",
            false
        );

        assertEquals(0L, dashEntry.getResponseSizeBytes());
        assertEquals(0L, invalidEntry.getResponseSizeBytes());
    }

    @Test
    void factoryForModSecurityShouldSetBlockedFlagAndTag() {
        LogEntry entry = LogEntry.createModSecurityBlocked(
            "192.168.0.10",
            "01/Jan/2026:00:00:00 +0900",
            "GET /blocked HTTP/1.1",
            403,
            "0",
            "-",
            "JUnit",
            "/var/log/nginx/access.log",
            "main-nginx"
        );

        assertTrue(entry.blockedByModSec());
        assertEquals("nginx", entry.logTag());
        assertNotNull(entry.collectedAt());
    }
}
