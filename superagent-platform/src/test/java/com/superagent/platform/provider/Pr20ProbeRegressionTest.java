package com.superagent.platform.provider;

import com.superagent.core.chat.RunCredential;
import com.superagent.runtime.cli.CliPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PR #20 review regressions: version≠key-OK, explicit CLI errors must fail the probe. */
class Pr20ProbeRegressionTest {

    @TempDir
    Path dir;

    ProviderInferenceProbe fake(String text) throws Exception {
        Path script = dir.resolve("fake-cli");
        Files.writeString(script, "#!/bin/sh\n"
                + "if [ \"$1\" = \"--version\" ]; then echo 1.0.0; exit 0; fi\n"
                + "printf '%s\\n' '" + text + "'\n");
        script.toFile().setExecutable(true);
        return new ProviderInferenceProbe(new ProviderProbe(new CliPaths(dir.toString())) {
            @Override
            public Optional<Path> findBinary(String c) {
                return Optional.of(script);
            }
        });
    }

    @Test
    void versionAloneMustNotValidateBadKey() throws Exception {
        var probe = fake("{}");
        assertThatThrownBy(() -> probe.checkKey("codebuddy", null, new RunCredential("c", null, "invalid", false)))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> probe.checkKey("gemini", null, new RunCredential("c", null, "invalid", false)))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> probe.checkKey("minimax", null, new RunCredential("c", null, "invalid", false)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void codebuddyExplicitErrorMustFailProbe() throws Exception {
        var probe = fake("{\"type\":\"result\",\"is_error\":true,\"result\":\"Unauthorized: invalid key\"}");
        assertThatThrownBy(() -> probe.check("codebuddy", "auto", new RunCredential("c", null, "invalid", false)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unauthorized");
    }

    @Test
    void codebuddySuccessStillPasses() throws Exception {
        var probe = fake("{\"type\":\"result\",\"is_error\":false,\"result\":\"ok\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}");
        assertThatCode(() -> probe.check("codebuddy", "auto", new RunCredential("c", null, "sk", false)))
                .doesNotThrowAnyException();
    }

    @Test
    void geminiErrorObjectMustFailProbe() throws Exception {
        var probe = fake("{\"type\":\"result\",\"status\":\"error\",\"error\":{\"message\":\"API key not valid\"}}");
        assertThatThrownBy(() -> probe.check("gemini", "gemini-2.5-flash", new RunCredential("c", null, "bad", false)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("API key not valid");
    }

    @Test
    void geminiUnreadableOutputMustFailProbe() throws Exception {
        var probe = fake("not-json-at-all");
        assertThatThrownBy(() -> probe.check("gemini", "gemini-2.5-flash", new RunCredential("c", null, "bad", false)))
                .isInstanceOf(IOException.class);
    }
}
