package com.uniton.backend.support;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public final class FakeRuntimeSecretFileAdapter {

    private final List<String> resolvedNames = new ArrayList<>();

    public ResolvedSecret resolve(String name, Mount mount, EnvironmentCredential environmentCredential) {
        if (environmentCredential.present()) {
            throw new IllegalStateException("environment credential is forbidden: " + name);
        }
        if (!mount.exists()) {
            throw new IllegalStateException("fake secret mount is missing: " + name);
        }
        if (mount.writable()) {
            throw new IllegalStateException("fake secret mount is writable: " + name);
        }
        if (!mount.rootReadable()) {
            throw new IllegalStateException("fake secret mount is not root-readable: " + name);
        }
        resolvedNames.add(name);
        return new ResolvedSecret(mount.value());
    }

    public List<String> resolvedNames() {
        return List.copyOf(resolvedNames);
    }

    public record ResolvedSecret(String value) {}

    public record EnvironmentCredential(boolean present, Supplier<String> value) {

        public static EnvironmentCredential absent(Supplier<String> value) {
            return new EnvironmentCredential(false, value);
        }

        public static EnvironmentCredential present(Supplier<String> value) {
            return new EnvironmentCredential(true, value);
        }
    }

    public record Mount(boolean exists, boolean rootReadable, boolean writable, String value) {

        public static Mount rootReadableReadOnly(String value) {
            return new Mount(true, true, false, value);
        }

        public static Mount missing() {
            return new Mount(false, false, false, "");
        }

        public static Mount writable(String value) {
            return new Mount(true, true, true, value);
        }

        public static Mount nonRootReadable(String value) {
            return new Mount(true, false, false, value);
        }
    }
}
