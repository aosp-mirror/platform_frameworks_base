/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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

import static com.google.common.truth.Truth.assertThat;

import android.app.admin.SecurityLog.SecurityEvent;
import android.os.Parcel;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.EventLog.Event;

import androidx.test.runner.AndroidJUnit4;

import junit.framework.AssertionFailedError;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the DeviceOwner object that saves & loads device and policy owner information.
 *
 * <p>Run this test with:
 *
 * {@code atest FrameworksServicesTests:com.android.server.devicepolicy.SecurityEventTest}
 *
 */
@RunWith(AndroidJUnit4.class)
public class SecurityEventTest extends DpmTestBase {

    @Test
    public void testSecurityEventId() throws Exception {
        SecurityEvent event = createEvent(() -> {
            EventLog.writeEvent(TAG_ADB_SHELL_CMD, 0);
        }, TAG_ADB_SHELL_CMD);
        event.setId(20);
        assertThat(event.getId()).isEqualTo(20);
    }

    @Test
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
        assertThat(unparceledEvent.getTag()).isEqualTo(event.getTag());
        assertThat(unparceledEvent.getData()).isEqualTo(event.getData());
        assertThat(unparceledEvent.getTimeNanos()).isEqualTo(event.getTimeNanos());
        assertThat(unparceledEvent.getId()).isEqualTo(event.getId());
    }

    @Test
    public void testSecurityEventRedaction() throws Exception {
        SecurityEvent event;

        // TAG_ADB_SHELL_CMD will has the command redacted
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_ADB_SHELL_CMD, "command");
        }, TAG_ADB_SHELL_CMD);
        assertThat(TextUtils.isEmpty((String) event.getData())).isFalse();

        // TAG_MEDIA_MOUNT will have the volume label redacted (second data)
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_MEDIA_MOUNT, new Object[] {"path", "label"});
        }, TAG_MEDIA_MOUNT);
        assertThat(TextUtils.isEmpty(event.getStringData(1))).isFalse();
        assertThat(TextUtils.isEmpty(event.redact(0).getStringData(1))).isTrue();

        // TAG_MEDIA_UNMOUNT will have the volume label redacted (second data)
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_MEDIA_UNMOUNT, new Object[] {"path", "label"});
        }, TAG_MEDIA_UNMOUNT);
        assertThat(TextUtils.isEmpty(event.getStringData(1))).isFalse();
        assertThat(TextUtils.isEmpty(event.redact(0).getStringData(1))).isTrue();

        // TAG_APP_PROCESS_START will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_APP_PROCESS_START, new Object[] {"process", 12345L,
                    UserHandle.getUid(10, 123), 456, "seinfo", "hash"});
        }, TAG_APP_PROCESS_START);
        assertThat(event.redact(10)).isNotNull();
        assertThat(event.redact(11)).isNull();

        // TAG_CERT_AUTHORITY_INSTALLED will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_CERT_AUTHORITY_INSTALLED, new Object[] {1, "subject", 10});
        }, TAG_CERT_AUTHORITY_INSTALLED);
        assertThat(event.redact(10)).isNotNull();
        assertThat(event.redact(11)).isNull();

        // TAG_CERT_AUTHORITY_REMOVED will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_CERT_AUTHORITY_REMOVED, new Object[] {1, "subject", 20});
        }, TAG_CERT_AUTHORITY_REMOVED);
        assertThat(event.redact(20)).isNotNull();
        assertThat(event.redact(0)).isNull();

        // TAG_KEY_GENERATED will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_KEY_GENERATED,
                    new Object[] {1, "alias", UserHandle.getUid(0, 123)});
        }, TAG_KEY_GENERATED);
        assertThat(event.redact(0)).isNotNull();
        assertThat(event.redact(10)).isNull();

        // TAG_KEY_IMPORT will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_KEY_IMPORT,
                    new Object[] {1, "alias", UserHandle.getUid(1, 123)});
        }, TAG_KEY_IMPORT);
        assertThat(event.redact(1)).isNotNull();
        assertThat(event.redact(10)).isNull();

        // TAG_KEY_DESTRUCTION will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_KEY_DESTRUCTION,
                    new Object[] {1, "alias", UserHandle.getUid(2, 123)});
        }, TAG_KEY_DESTRUCTION);
        assertThat(event.redact(2)).isNotNull();
        assertThat(event.redact(10)).isNull();

        // TAG_KEY_INTEGRITY_VIOLATION will be fully redacted if user does not match
        event = createEvent(() -> {
            EventLog.writeEvent(TAG_KEY_INTEGRITY_VIOLATION,
                    new Object[] {"alias", UserHandle.getUid(2, 123)});
        }, TAG_KEY_INTEGRITY_VIOLATION);
        assertThat(event.redact(2)).isNotNull();
        assertThat(event.redact(10)).isNull();

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
