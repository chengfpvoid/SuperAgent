package com.superagent.runtime.cli;

import com.superagent.core.chat.AgentRunRequest;
import com.superagent.core.chat.RunCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PR #20 review regressions: CodeBuddy allowlist, Gemini home/settings, delta stream, stream stats. */
class Pr20RegressionTest {

    @TempDir
    Path dir;

    AgentRunRequest req() {
        return new AgentRunRequest("s", null, dir.toString(), "hi", "m", List.of(), false,
                "{\"mcpServers\":{\"superagent\":{\"type\":\"http\",\"url\":\"http://localhost/mcp\"}}}", null)
                .withCredential(new RunCredential("c", null, "fake", false));
    }

    @Test
    void codebuddyEmptyAllowlistMustNotSkipPermissions() throws Exception {
        var clean = new ArrayList<Path>();
        try {
            List<String> cmd = new CodeBuddyCliAgentRunner("codebuddy", 5).buildCommand(req(), null, clean);
            assertThat(cmd).doesNotContain("-y", "--dangerously-skip-permissions");
            assertThat(cmd).containsSequence("--tools", "")
                    .containsSequence("--permission-mode", "dontAsk")
                    .containsSequence("--setting-sources", "");
        } finally {
            for (var p : clean) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void codebuddySkipPermissionsStillUsesBypass() throws Exception {
        AgentRunRequest skip = new AgentRunRequest("s", null, dir.toString(), "hi", "m", List.of(), true, null, null)
                .withCredential(new RunCredential("c", null, "fake", false));
        List<String> cmd = new CodeBuddyCliAgentRunner("codebuddy", 5).buildCommand(skip, null, new ArrayList<>());
        assertThat(cmd).contains("-y");
        assertThat(cmd).doesNotContain("--tools", "--setting-sources");
    }

    @Test
    void codebuddyScopedRulesKeepFullAllowedToolsButBareNamesForToolsFlag() throws Exception {
        assertThat(CodeBuddyCliAgentRunner.builtInToolNames(
                List.of("Bash(git:*)", "Read(/src/**)", "Edit", "mcp__superagent__file_read", "Bash(git:*)")))
                .isEqualTo("Bash,Read,Edit");

        AgentRunRequest scoped = new AgentRunRequest("s", null, dir.toString(), "hi", "m",
                List.of("Bash(git:*)", "Read(/src/**)", "mcp__corp"), false, null, null)
                .withCredential(new RunCredential("c", null, "fake", false));
        List<String> cmd = new CodeBuddyCliAgentRunner("codebuddy", 5).buildCommand(scoped, null, new ArrayList<>());
        assertThat(cmd).containsSequence("--allowedTools", "Bash(git:*) Read(/src/**) mcp__corp")
                .containsSequence("--tools", "Bash,Read")
                .doesNotContain("-y");
    }

    @Test
    void codebuddyMcpOnlyAllowlistDisablesBuiltIns() {
        assertThat(CodeBuddyCliAgentRunner.builtInToolNames(List.of("mcp__superagent", "mcp__corp__list")))
                .isEmpty();
    }

    @Test
    void codebuddyPathWithDoubleUnderscoreIsStillBuiltIn() throws Exception {
        assertThat(CodeBuddyCliAgentRunner.builtInToolNames(
                List.of("Read(./src/__tests__/**)", "Bash(git:*)", "mcp__superagent__file_read")))
                .isEqualTo("Read,Bash");

        AgentRunRequest scoped = new AgentRunRequest("s", null, dir.toString(), "hi", "m",
                List.of("Read(./src/__tests__/**)"), false, null, null)
                .withCredential(new RunCredential("c", null, "fake", false));
        List<String> cmd = new CodeBuddyCliAgentRunner("codebuddy", 5).buildCommand(scoped, null, new ArrayList<>());
        assertThat(cmd).containsSequence("--allowedTools", "Read(./src/__tests__/**)")
                .containsSequence("--tools", "Read")
                .doesNotContain("-y");
    }

    @Test
    void geminiSettingsMustBeWrittenUnderHomeDotGemini() throws Exception {
        var r = new GeminiCliAgentRunner("gemini", 5);
        r.buildCommand(req(), null, new ArrayList<>());
        Path home = Path.of(r.environment(req()).get("HOME"));
        assertThat(r.environment(req())).containsEntry("GEMINI_CLI_HOME", home.toString())
                .doesNotContainKey("GEMINI_CONFIG_DIR");
        assertThat(Files.exists(home.resolve(".gemini/settings.json"))).isTrue();
        assertThat(Files.readString(home.resolve(".gemini/settings.json"))).contains("httpUrl");
    }

    @Test
    void geminiMustAccumulateRealDeltaMessages() {
        var p = new GeminiStreamParser();
        p.parse("{\"type\":\"message\",\"role\":\"assistant\",\"delta\":true,\"content\":\"Hello \"}");
        p.parse("{\"type\":\"message\",\"role\":\"assistant\",\"delta\":true,\"content\":\"world\"}");
        assertThat(p.parse("{\"type\":\"result\",\"status\":\"success\"}").get(0).payload())
                .isEqualTo("Hello world");
    }

    @Test
    void geminiRepeatedDeltaChunksAreKept() {
        var p = new GeminiStreamParser();
        p.parse("{\"type\":\"message\",\"role\":\"assistant\",\"delta\":true,\"content\":\"ha\"}");
        p.parse("{\"type\":\"message\",\"role\":\"assistant\",\"delta\":true,\"content\":\"ha\"}");
        assertThat(p.parse("{\"type\":\"result\",\"status\":\"success\"}").get(0).payload()).isEqualTo("haha");
    }

    @Test
    void geminiMustReadRealStreamStats() {
        var p = new GeminiStreamParser();
        p.parse("{\"type\":\"result\",\"status\":\"success\",\"stats\":{\"input_tokens\":100,\"output_tokens\":20,"
                + "\"cached\":30,\"models\":{\"m\":{\"input_tokens\":100,\"output_tokens\":20,\"cached\":30}}}}");
        assertThat(p.tokens("m")).singleElement().satisfies(u -> {
            assertThat(u.input()).isEqualTo(70);
            assertThat(u.cacheRead()).isEqualTo(30);
            assertThat(u.output()).isEqualTo(20);
        });
    }

    @Test
    void geminiFlatStatsWithoutModels() {
        var p = new GeminiStreamParser();
        p.parse("{\"type\":\"result\",\"status\":\"success\",\"stats\":{\"input_tokens\":50,\"output_tokens\":5,"
                + "\"cached\":10}}");
        assertThat(p.tokens("gemini-2.5-pro")).singleElement().satisfies(u -> {
            assertThat(u.input()).isEqualTo(40);
            assertThat(u.cacheRead()).isEqualTo(10);
            assertThat(u.output()).isEqualTo(5);
        });
    }
}
