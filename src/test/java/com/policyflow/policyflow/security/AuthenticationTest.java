package com.policyflow.policyflow.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.policyflow.policyflow.entity.Department;
import com.policyflow.policyflow.entity.User;
import com.policyflow.policyflow.enums.Role;
import com.policyflow.policyflow.repository.DepartmentRepository;
import com.policyflow.policyflow.repository.UserRepository;

@SpringBootTest
@Transactional // test users are rolled back, so nothing stays in MySQL
class AuthenticationTest {

    private static final String EMAIL = "auth-test@policyflow.local";
    private static final String PASSWORD = "Test@12345";

    @Autowired private AuthenticationManager authenticationManager;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;

    private User user;

    @BeforeEach
    void createUser() {
        Department department = departmentRepository.save(new Department("Auth Test", "AUTHTST"));
        user = new User("Auth Tester", EMAIL, Role.MANAGER, department);
        user.setPasswordHash(passwordEncoder.encode(PASSWORD));
        user = userRepository.save(user);
    }

    private Authentication attempt(String email, String password) {
        return authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(email, password));
    }

    @Test
    void validPasswordAuthenticatesAndCarriesRoleAndUserId() {
        Authentication result = attempt(EMAIL, PASSWORD);

        assertTrue(result.isAuthenticated());
        AuthenticatedUser principal = (AuthenticatedUser) result.getPrincipal();
        assertEquals(user.getId(), principal.getId());
        assertTrue(result.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_MANAGER")));
    }

    @Test
    void passwordIsStoredAsBcryptHashNotPlaintext() {
        String stored = userRepository.findByEmail(EMAIL).orElseThrow().getPasswordHash();

        assertNotEquals(PASSWORD, stored);
        assertTrue(stored.startsWith("$2"), "expected a BCrypt hash");
        assertEquals(60, stored.length());
        assertTrue(passwordEncoder.matches(PASSWORD, stored));
    }

    @Test
    void wrongPasswordIsRejected() {
        assertThrows(BadCredentialsException.class, () -> attempt(EMAIL, "wrong-password"));
    }

    @Test
    void unknownEmailIsRejectedWithTheSameError() {
        assertThrows(BadCredentialsException.class, () -> attempt("nobody@policyflow.local", PASSWORD));
    }

    @Test
    void deactivatedUserCannotLogIn() {
        user.setActive(false);
        userRepository.save(user);

        assertThrows(AuthenticationException.class, () -> attempt(EMAIL, PASSWORD));
    }

    @Test
    void userWithoutPasswordCannotLogIn() {
        user.setPasswordHash(null);
        userRepository.save(user);

        assertThrows(AuthenticationException.class, () -> attempt(EMAIL, PASSWORD));
    }
}