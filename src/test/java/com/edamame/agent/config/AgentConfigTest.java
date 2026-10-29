package com.edamame.agent.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void loadShouldParseServerMappingsAndIgnoreComments() throws IOException {
        Path configFile = tempDir.resolve("agent-config.json");
        String log1 = tempDir.resolve("access.log").toString().replace("\\", "\\\\");
        String log2 = tempDir.resolve("error.log").toString().replace("\\", "\\\\");

        Files.writeString(configFile, """
            {
              "agent": {
                "name": "agent-test",
                "description": "Agent test"
              },
              "logging": {
                "servers": [
                  "# コメント行",
                  "main-nginx,%s",
                  "",
                  "api-server,%s",
                  "invalid-no-comma"
                ],
                "collectionInterval": 15,
                "format": "combined",
                "maxBatchSize": 250
              }
            }
            """.formatted(log1, log2));

        AgentConfig config = new AgentConfig(configFile.toString());
        assertTrue(config.load());

        List<String> paths = config.getNginxLogPaths();
        Map<String, List<String>> mappings = config.getServerLogMappings();

        assertEquals(2, paths.size());
        assertTrue(paths.contains(tempDir.resolve("access.log").toString()));
        assertTrue(paths.contains(tempDir.resolve("error.log").toString()));
        assertEquals(List.of(tempDir.resolve("access.log").toString()), mappings.get("main-nginx"));
        assertEquals(List.of(tempDir.resolve("error.log").toString()), mappings.get("api-server"));
        assertEquals("main-nginx", config.getServerNameByLogPath(tempDir.resolve("access.log").toString()));
        assertEquals("main-nginx", config.getServerNameByLogPath("unknown-path.log"));
        assertEquals(15, config.getLogCollectionInterval());
        assertEquals(250, config.getMaxBatchSize());
    }

    @Test
    void loadShouldFallbackAgentNameToLegacyServerName() throws IOException {
        Path configFile = tempDir.resolve("legacy-config.json");
        Files.writeString(configFile, """
            {
              "server": {
                "name": "legacy-server-01"
              }
            }
            """);

        AgentConfig config = new AgentConfig(configFile.toString());
        assertTrue(config.load());
        assertEquals("legacy-server-01", config.getAgentId());
    }

    @Test
    void loadShouldAcceptDefenderSectionAsIptablesCompatibility() throws IOException {
        Path configFile = tempDir.resolve("windows-config.json");
        Files.writeString(configFile, """
            {
              "defender": {
                "enabled": false,
                "checkInterval": 45,
                "chain": "WINDOWS_FIREWALL_INBOUND",
                "blockDuration": 7200
              }
            }
            """);

        AgentConfig config = new AgentConfig(configFile.toString());
        assertTrue(config.load());
        assertFalse(config.isEnableIptables());
        assertEquals(45, config.getIptablesCheckInterval());
        assertEquals("WINDOWS_FIREWALL_INBOUND", config.getIptablesChain());
    }
}
