package com.policyflow.policyflow.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.policyflow.policyflow.enums.NotificationType;
import com.policyflow.policyflow.enums.Role;

class NotificationTest {

    private static User recipient() {
        return new User("Recipient", "recipient@policyflow.local", Role.EMPLOYEE, new Department("Dept", "DEPT"));
    }

    private static Notification notification(String link, String message) {
        return new Notification(recipient(), NotificationType.STAGE_APPROVED, 1L, link, message);
    }

    @Test
    void aNewNotificationIsUnread() {
        Notification notification = notification("/requests/1", "Hello");

        assertFalse(notification.isRead());
        assertNull(notification.getReadAt());
    }

    @Test
    void markReadRecordsTheTimeOnlyOnce() {
        Notification notification = notification("/requests/1", "Hello");

        notification.markRead();
        LocalDateTime first = notification.getReadAt();
        assertTrue(notification.isRead());
        assertNotNull(first);

        notification.markRead();
        assertSame(first, notification.getReadAt());
    }

    @Test
    void theMessageIsTrimmedAndRequired() {
        assertEquals("Hello", notification(null, "  Hello  ").getMessage());

        assertThrows(IllegalArgumentException.class, () -> notification(null, "   "));
        assertThrows(IllegalArgumentException.class, () -> notification(null, null));
    }

    @Test
    void longMessagesAreShortenedToTheColumnSize() {
        Notification notification = notification(null, "x".repeat(500));

        assertEquals(Notification.MAX_MESSAGE_LENGTH, notification.getMessage().length());
        assertTrue(notification.getMessage().endsWith("\u2026"));
    }

    @Test
    void recipientAndTypeAreRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new Notification(null, NotificationType.STAGE_APPROVED, 1L, null, "Hello"));
        assertThrows(IllegalArgumentException.class,
                () -> new Notification(recipient(), null, 1L, null, "Hello"));
    }

    @Test
    void onlyLocalLinksAreAccepted() {
        assertNull(notification(null, "Hello").getLink());
        assertEquals("/requests/5", notification("/requests/5", "Hello").getLink());
        assertEquals("/manager/requests/5", notification("/manager/requests/5", "Hello").getLink());

        for (String bad : new String[] {"https://evil.example", "//evil.example", "requests/5",
                "/\\evil.example", "/" + "a".repeat(200)}) {
            assertThrows(IllegalArgumentException.class, () -> notification(bad, "Hello"), bad);
        }
    }
}