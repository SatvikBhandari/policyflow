package com.policyflow.policyflow.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Stops the application from starting in production with an unsafe configuration. */
@Component
@Profile("prod")
public class ProductionSafetyCheck implements InitializingBean {

    private static final Set<String> SCHEMA_GENERATING_MODES = Set.of("create", "create-drop", "update");

    private final boolean demoPasswordsEnabled;
    private final boolean secureSessionCookie;
    private final String schemaMode;

    public ProductionSafetyCheck(
            @Value("${policyflow.dev.seed-passwords:false}") boolean demoPasswordsEnabled,
            @Value("${server.servlet.session.cookie.secure:false}") boolean secureSessionCookie,
            @Value("${spring.jpa.hibernate.ddl-auto:none}") String schemaMode) {
        this.demoPasswordsEnabled = demoPasswordsEnabled;
        this.secureSessionCookie = secureSessionCookie;
        this.schemaMode = schemaMode;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Unsafe production configuration: " + String.join("; ", problems));
        }
    }

    /** Everything that is wrong with the configuration, or an empty list when it is safe. */
    List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (demoPasswordsEnabled) {
            problems.add("policyflow.dev.seed-passwords must be false (demo passwords are for development only)");
        }
        if (!secureSessionCookie) {
            problems.add("server.servlet.session.cookie.secure must be true");
        }
        String mode = schemaMode == null ? "" : schemaMode.trim().toLowerCase(Locale.ROOT);
        if (SCHEMA_GENERATING_MODES.contains(mode)) {
            problems.add("spring.jpa.hibernate.ddl-auto must be validate or none because Flyway manages the schema (it is "
                    + mode + ")");
        }
        return problems;
    }
}