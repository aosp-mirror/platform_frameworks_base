package com.android.server.devicepolicy;

import static android.app.admin.SecurityLog.TAG_ADB_SHELL_CMD;
import static android.app.admin.SecurityLog.TAG_APP_PROCESS_START;
import static android.app.admin.SecurityLog.TAG_CERT_AUTHORITY_INSTALLED;
import static android.app.admin.SecurityLog.TAG_CERT_AUTHORITY_REMOVED;
import static android.app.admin.SecurityLog.TAG_KEY_DESTRUCTION;
import static android.app.admin.SecurityLog.TAG_KEY_GENERATED;
import static android.app.admin.SecurityLog.TAG_KEY_IMPORT;
import static android.app.admin.SecurityLog.TAG_KEY_INTEGRITY_VIOLATION;
import static android.app.admin.SecurityLog.TAG_MEDIA_MOUNT;
import static android.app.admin.SecurityLog.TAG_MEDIA_UNMOUNT;

import android.app.admin.SecurityLog.SecurityEvent;
import android.os.Parcel;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.EventLog.Event;

import junit.framework.AssertionFailedError;

import java.util.ArrayList;
import java.util.List;

public class SecurityEventTest extends DpmTestBase {

    public void testSecurityEventId() throws Exception {
        SecurityEvent event = createEvent(() -> {
            EventLog.writeEvent(TAG_ADB_SHELL_CMD, 0);
        }, TAG_ADB_SHELL_CMD);
        event.setId(20);
        assertEquals(20, event.getId());
    }

    public void testSecurityEventParceling() throws Exception {
        // GIVEN an event.
        SecurityEvent event = createEvent(() -> {
            EventLog.writeEvent(TAG_ADB_SHELL_CMD, "test");
        }, TAG_ADB_SHELL_CMD);
        // WHEN parceling the event.
        Parcel p = Parcel.obtain();
        p.writeParcelable(event, 0);
        p.setDataPosition(0);
        SecurityEvent unparceledEvent = p.readParcelable(SecurityEventTest.class.getClassLoader());
        p.recycle();
        // THEN the event state is preserved.
        assertEquals(event.getTag(), unparceledEvent.getTag());
        assertEquals(event.getData(), unparceledEvent.getData());
        assertEquals(event.getTimeNanos(), unparceledEvent.getTimeNanos());
        assertEquals(event.getId(), unparceledEvent.getId());
    }

    public void testSecurityEventRedaction() throws Exception {
        SecurityEvent event;

        // TAG_ADB_SHELL_CMD will has the command redacted
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_ADB_SHELL_CMD, "command");
        }, TAG_ADB_SHELL_CMD);
        assertFalse(TextUtils.isEmpty((String) event.getData()));

        // TAG_MEDIA_MOUNT will have the volume label redacted (second data)
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_MEDIA_MOUNT, new Object[] {"path", "label"});
        }, TAG_MEDIA_MOUNT);
        assertFalse(TextUtils.isEmpty(event.getStringData(1)));
        assertTrue(TextUtils.isEmpty(event.redact(0).getStringData(1)));

        // TAG_MEDIA_UNMOUNT will have the volume label redacted (second data)
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_MEDIA_UNMOUNT, new Object[] {"path", "label"});
        }, TAG_MEDIA_UNMOUNT);
        assertFalse(TextUtils.isEmpty(event.getStringData(1)));
        assertTrue(TextUtils.isEmpty(event.redact(0).getStringData(1)));

        // TAG_APP_PROCESS_START will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_APP_PROCESS_START, new Object[] {"process", 12345L,
                    UserHandle.getUid(10, 123), 456, "seinfo", "hash"});
        }, TAG_APP_PROCESS_START);
        assertNotNull(event.redact(10));
        assertNull(event.redact(11));

        // TAG_CERT_AUTHORITY_INSTALLED will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_CERT_AUTHORITY_INSTALLED, new Object[] {1, "subject", 10});
        }, TAG_CERT_AUTHORITY_INSTALLED);
        assertNotNull(event.redact(10));
        assertNull(event.redact(11));

        // TAG_CERT_AUTHORITY_REMOVED will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_CERT_AUTHORITY_REMOVED, new Object[] {1, "subject", 20});
        }, TAG_CERT_AUTHORITY_REMOVED);
        assertNotNull(event.redact(20));
        assertNull(event.redact(0));

        // TAG_KEY_GENERATED will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_KEY_GENERATED,
                    new Object[] {1, "alias", UserHandle.getUid(0, 123)});
        }, TAG_KEY_GENERATED);
        assertNotNull(event.redact(0));
        assertNull(event.redact(10));

        // TAG_KEY_IMPORT will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_KEY_IMPORT,
                    new Object[] {1, "alias", UserHandle.getUid(1, 123)});
        }, TAG_KEY_IMPORT);
        assertNotNull(event.redact(1));
        assertNull(event.redact(10));

        // TAG_KEY_DESTRUCTION will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_KEY_DESTRUCTION,
                    new Object[] {1, "alias", UserHandle.getUid(2, 123)});
        }, TAG_KEY_DESTRUCTION);
        assertNotNull(event.redact(2));
        assertNull(event.redact(10));

        // TAG_KEY_INTEGRITY_VIOLATION will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_KEY_INTEGRITY_VIOLATION,
                    new Object[] {"alias", UserHandle.getUid(2, 123)});
        }, TAG_KEY_INTEGRITY_VIOLATION);
        assertNotNull(event.redact(2));
        assertNull(event.redact(10));

    }

    /**
     * Creates an Event object. Only the native code has the serialization and deserialization logic
     * so need to actually emit a real log in order to generate the object.
     */
    private SecurityEvent createEvent(Runnable generator, int expectedTag) throws Exception {
        Long markerData = System.currentTimeMillis();
        EventLog.writeEvent(expectedTag, markerData);
        generator.run();

        List<Event> events = new ArrayList<>();
        // Give the message some time to show up in the log
        Thread.sleep(20);
        EventLog.readEvents(new int[] {expectedTag}, events);

        for (int i = 0; i < events.size() - 1; i++) {
            if (markerData.equals(events.get(i).getData())) {
                return new SecurityEvent(0, events.get(i + 1).getBytes());
            }
        }
        throw new AssertionFailedError("Unable to locate marker event");
    }
}
