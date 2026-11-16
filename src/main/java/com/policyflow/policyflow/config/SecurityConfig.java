package com.policyflow.policyflow.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.security.RoleBasedSuccessHandler;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Roles that may raise and track their own requests. ADMIN is deliberately not included. */
    private static final String[] REQUESTER_ROLES = {
            Role.EMPLOYEE.name(),
            Role.MANAGER.name(),
            Role.FINANCE_OFFICER.name(),
            Role.DEPARTMENT_HEAD.name()
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   RoleBasedSuccessHandler successHandler) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                // Public
                .requestMatchers("/css/**", "/js/**", "/images/**", "/favicon.ico").permitAll()
                .requestMatchers("/login", "/error").permitAll()

                // Employee-facing request pages (ownership is enforced again in RequestService)
                .requestMatchers("/requests", "/requests/**").hasAnyRole(REQUESTER_ROLES)

                // Read-only policies page for everyone who raises or decides requests
                .requestMatchers("/policies", "/policies/**").hasAnyRole(REQUESTER_ROLES)
                
                // Role areas: each is open to exactly one role
                .requestMatchers("/manager", "/manager/**").hasRole(Role.MANAGER.name())
                .requestMatchers("/finance", "/finance/**").hasRole(Role.FINANCE_OFFICER.name())
                .requestMatchers("/department", "/department/**").hasRole(Role.DEPARTMENT_HEAD.name())
                .requestMatchers("/admin", "/admin/**").hasRole(Role.ADMIN.name())

                // Everything else needs a login
                .anyRequest().authenticated())
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("email")
                .passwordParameter("password")
                .successHandler(successHandler)
                .failureUrl("/login?error")
                .permitAll())
            .logout(logout -> logout
                .logoutUrl("/logout") // POST only, CSRF-protected
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID"));

        return http.build();
    }
}