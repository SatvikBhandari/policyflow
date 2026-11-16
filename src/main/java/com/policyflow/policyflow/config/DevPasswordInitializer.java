package com.policyflow.policyflow.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.repository.UserRepository;

/**
 * DEVELOPMENT ONLY. Gives demo users a BCrypt-hashed password so you can log in.
 * Only fills accounts whose password is still empty; never overwrites an existing hash.
 * Disable it by removing policyflow.dev.seed-passwords from application.properties.
 */
@Component
@ConditionalOnProperty(name = "policyflow.dev.seed-passwords", havingValue = "true")
@Profile("!prod")
public class DevPasswordInitializer {

    private static final Logger log = LoggerFactory.getLogger(DevPasswordInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String demoPassword;

    public DevPasswordInitializer(UserRepository userRepository,
                                  PasswordEncoder passwordEncoder,
                                  @Value("${policyflow.dev.demo-password}") String demoPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.demoPassword = demoPassword;
    }

    // ApplicationReadyEvent fires after every CommandLineRunner (including DataSeeder) has finished.
    @EventListener(ApplicationReadyEvent.class)
    public void fillMissingPasswords() {
        int updated = 0;
        for (User user : userRepository.findAll()) {
            if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
                user.setPasswordHash(passwordEncoder.encode(demoPassword));
                userRepository.save(user);
                updated++;
            }
        }
        if (updated > 0) {
            log.warn("DEV ONLY: set a demo password for {} user(s) without one. "
                    + "Disable policyflow.dev.seed-passwords before any real deployment.", updated);
        }
    }
}