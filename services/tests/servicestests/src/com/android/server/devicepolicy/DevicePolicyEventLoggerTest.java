/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.app.admin.DevicePolicyEventLogger;
import android.content.ComponentName;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link DevicePolicyEventLogger}.
 * <p/>
 * Run with <code>atest DevicePolicyEventLoggerTest</code>.
 */
@RunWith(AndroidJUnit4.class)
public class DevicePolicyEventLoggerTest {
    @Test
    public void testAllFields() {
        final DevicePolicyEventLogger eventLogger = DevicePolicyEventLogger
                .createEvent(5)
                .setBoolean(true)
                .setStrings("string1", "string2", "string3")
                .setAdmin(new ComponentName("com.test.package", ".TestAdmin"))
                .setInt(4321)
                .setTimePeriod(1234L);
        assertThat(eventLogger.getEventId()).isEqualTo(5);
        assertThat(eventLogger.getBoolean()).isTrue();
        assertThat(eventLogger.getStringArray()).asList()
            .containsExactly("string1", "string2", "string3");
        assertThat(eventLogger.getAdminPackageName()).isEqualTo("com.test.package");
        assertThat(eventLogger.getInt()).isEqualTo(4321);
        assertThat(eventLogger.getTimePeriod()).isEqualTo(1234L);
    }

    @Test
    public void testStrings() {
        assertThat(DevicePolicyEventLogger
                .createEvent(0)
                .setStrings("string1", "string2", "string3").getStringArray()).asList()
                        .containsExactly("string1", "string2", "string3").inOrder();

        assertThat(DevicePolicyEventLogger
                .createEvent(0)
                .setStrings("string1", new String[] {"string2", "string3"}).getStringArray())
                        .asList().containsExactly("string1", "string2", "string3").inOrder();

        assertThat(DevicePolicyEventLogger
                .createEvent(0)
                .setStrings("string1", "string2", new String[] {"string3"}).getStringArray())
                        .asList().containsExactly("string1", "string2", "string3").inOrder();
        assertThat(DevicePolicyEventLogger
                .createEvent(0)
                .setStrings((String) null).getStringArray()).asList()
                        .containsExactly((String) null);

        assertThat(DevicePolicyEventLogger
                .createEvent(0)
                .setStrings((String[]) null).getStringArray())
                .isEqualTo(null);

        assertThrows(NullPointerException.class, () -> DevicePolicyEventLogger
                .createEvent(0)
                .setStrings("string1", "string2", null));
    }

    @Test
    public void testAdmins() {
        assertThat(DevicePolicyEventLogger
                .createEvent(0)
                .setAdmin("com.package.name")
                .getAdminPackageName())
                .isEqualTo("com.package.name");

        assertThat(DevicePolicyEventLogger
                .createEvent(0)
                .setAdmin(new ComponentName("com.package.name", ".TestAdmin"))
                .getAdminPackageName())
                .isEqualTo("com.package.name");
    }

    @Test
    public void testDefaultValues() {
        final DevicePolicyEventLogger eventLogger = DevicePolicyEventLogger
                .createEvent(0);
        assertThat(eventLogger.getEventId()).isEqualTo(0);
        assertThat(eventLogger.getBoolean()).isFalse();
        assertThat(eventLogger.getStringArray()).isNull();
        assertThat(eventLogger.getAdminPackageName()).isNull();
        assertThat(eventLogger.getInt()).isEqualTo(0);
        assertThat(eventLogger.getTimePeriod()).isEqualTo(0L);
    }
}
