/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.accessibility.utils;

import static com.android.internal.accessibility.common.ShortcutConstants.SERVICES_SEPARATOR;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;

import com.android.systemui.util.settings.SecureSettings;

import java.util.Set;
import java.util.StringJoiner;
import java.util.function.BooleanSupplier;

public class TestUtils {
    private static final ComponentName TEST_COMPONENT_A = new ComponentName("pkg", "A");
    private static final ComponentName TEST_COMPONENT_B = new ComponentName("pkg", "B");
    public static final String[] TEST_BUTTON_TARGETS = {
            TEST_COMPONENT_A.flattenToString(), TEST_COMPONENT_B.flattenToString()};
    public static long DEFAULT_CONDITION_DURATION = 5_000;

    /**
     * Waits an amount of time specified by {@link TestUtils#DEFAULT_CONDITION_DURATION}
     * for a condition to become true.
     * On failure, throws a {@link RuntimeException} with a custom message.
     *
     * @param c Condition which must return true to proceed.
     * @param message Message to print on failure.
     */
    public static void waitForCondition(BooleanSupplier condition, String message) {
        waitForCondition(condition, message, DEFAULT_CONDITION_DURATION);
    }

    /**
     * Waits up to a specified amount of time for a condition to become true.
     * On failure, throws a {@link RuntimeException} with a custom message.
     *
     * @param c Condition which must return true to proceed.
     * @param message Message to print on failure.
     * @param duration Amount of time permitted to wait.
     */
    public static void waitForCondition(BooleanSupplier condition, String message, long duration) {
        long deadline = SystemClock.uptimeMillis() + duration;
        long sleepMs = 50;
        while (!condition.getAsBoolean()) {
            if (SystemClock.uptimeMillis() > deadline) {
                throw new RuntimeException(message);
            }
            // Reduce frequency of checks as more checks occur
            sleepMs *= 2;
            SystemClock.sleep(sleepMs);
        }
    }

    /**
     * Returns a mock secure settings configured to return information needed for tests.
     * Currently, this only includes button targets.
     */
    public static SecureSettings mockSecureSettings() {
        SecureSettings secureSettings = mock(SecureSettings.class);

        final String targets = getShortcutTargets(
                Set.of(TEST_COMPONENT_A, TEST_COMPONENT_B));
        when(secureSettings.getStringForUser(
                Settings.Secure.ACCESSIBILITY_BUTTON_TARGETS,
                UserHandle.USER_CURRENT)).thenReturn(targets);

        return secureSettings;
    }

    private static String getShortcutTargets(Set<ComponentName> components) {
        final StringJoiner stringJoiner = new StringJoiner(String.valueOf(SERVICES_SEPARATOR));
        for (ComponentName target : components) {
            stringJoiner.add(target.flattenToString());
        }
        return stringJoiner.toString();
    }
}
