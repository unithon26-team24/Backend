package com.uniton.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles({"api", "worker"})
class BackendApplicationTest {

    @Value("${uniton.runtime.api-enabled}")
    private boolean apiEnabled;

    @Value("${uniton.runtime.worker-enabled}")
    private boolean workerEnabled;

    @Test
    void contextLoadsApiAndWorkerProfilesFromOneApplication() {
        // Given: API and worker profiles are active for the same application context.

        // When: Spring resolves both profile-specific runtime flags.

        // Then: both process identities are available from one application build.
        assertThat(apiEnabled).isTrue();
        assertThat(workerEnabled).isTrue();
    }
}
