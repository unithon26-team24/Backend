package com.uniton.backend.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class FakeRuntimeSecretFileAdapterTest {

    @Test
    void resolvesRootReadableReadOnlyFakeMountWithoutEnvironmentFallback() {
        // Given
        var adapter = new FakeRuntimeSecretFileAdapter();
        var environmentRead = new AtomicBoolean();
        var mount = FakeRuntimeSecretFileAdapter.Mount.rootReadableReadOnly("fixture-value");

        var environment = FakeRuntimeSecretFileAdapter.EnvironmentCredential.absent(() -> {
            environmentRead.set(true);
            return "forbidden-environment-value";
        });

        // When
        var secret = adapter.resolve("notion-token", mount, environment);

        // Then
        assertThat(secret.value()).isEqualTo("fixture-value");
        assertThat(environmentRead).isFalse();
        assertThat(adapter.resolvedNames()).containsExactly("notion-token");
    }

    @Test
    void rejectsMissingWritableAndNonRootReadableMountsWithoutEnvironmentRead() {
        // Given
        var environmentRead = new AtomicBoolean();
        var environment = FakeRuntimeSecretFileAdapter.EnvironmentCredential.absent(() -> {
            environmentRead.set(true);
            return "forbidden-environment-value";
        });
        var invalidMounts = new FakeRuntimeSecretFileAdapter.Mount[] {
            FakeRuntimeSecretFileAdapter.Mount.missing(),
            FakeRuntimeSecretFileAdapter.Mount.writable("fixture-value"),
            FakeRuntimeSecretFileAdapter.Mount.nonRootReadable("fixture-value")
        };

        // When / Then
        for (var mount : invalidMounts) {
            var adapter = new FakeRuntimeSecretFileAdapter();
            assertThatThrownBy(() -> adapter.resolve("notion-token", mount, environment))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(adapter.resolvedNames()).isEmpty();
        }
        assertThat(environmentRead).isFalse();
    }

    @Test
    void rejectsEnvironmentCredentialPresenceWithoutReadingItsValue() {
        // Given
        var adapter = new FakeRuntimeSecretFileAdapter();
        var environmentRead = new AtomicBoolean();
        var environment = FakeRuntimeSecretFileAdapter.EnvironmentCredential.present(() -> {
            environmentRead.set(true);
            return "forbidden-environment-value";
        });

        // When / Then
        assertThatThrownBy(() -> adapter.resolve(
                        "notion-token",
                        FakeRuntimeSecretFileAdapter.Mount.rootReadableReadOnly("fixture-value"),
                        environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("environment credential is forbidden");
        assertThat(environmentRead).isFalse();
        assertThat(adapter.resolvedNames()).isEmpty();
    }
}
