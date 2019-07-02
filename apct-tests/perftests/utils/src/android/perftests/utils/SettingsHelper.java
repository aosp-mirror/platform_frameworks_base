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

package android.perftests.utils;

import android.content.Context;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * Provides utilities to interact with the device's {@link Settings}.
 */
public final class SettingsHelper {

    public static final String NAMESPACE_SECURE = "secure";
    public static final String NAMESPACE_GLOBAL = "global";

    private static int DEFAULT_TIMEOUT_MS = 5000;

    /**
     * Uses a Shell command to "asynchronously" set the given preference, returning right away.
     */
    public static void set(@NonNull String namespace, @NonNull String key, @Nullable String value) {
        if (value == null) {
            delete(namespace, key);
            return;
        }
        ShellHelper.runShellCommand("settings put %s %s %s default", namespace, key, value);
    }

    /**
     * Uses a Shell command to "synchronously" set the given preference by registering a listener
     * and wait until it's set.
     */
    public static void syncSet(@NonNull Context context, @NonNull String namespace,
            @NonNull String key, @Nullable String value) {
        if (value == null) {
            syncDelete(context, namespace, key);
            return;
        }

        String currentValue = get(namespace, key);
        if (value.equals(currentValue)) {
            // Already set, ignore
            return;
        }

        OneTimeSettingsListener observer = new OneTimeSettingsListener(context, namespace, key,
                DEFAULT_TIMEOUT_MS);
        set(namespace, key, value);
        observer.assertCalled();
        assertNewValue(namespace, key, value);
    }

    /**
     * Uses a Shell command to "asynchronously" delete the given preference, returning right away.
     */
    public static void delete(@NonNull String namespace, @NonNull String key) {
        ShellHelper.runShellCommand("settings delete %s %s", namespace, key);
    }

    /**
     * Uses a Shell command to "synchronously" delete the given preference by registering a listener
     * and wait until it's called.
     */
    public static void syncDelete(@NonNull Context context, @NonNull String namespace,
            @NonNull String key) {
        String currentValue = get(namespace, key);
        if (currentValue == null || currentValue.equals("null")) {
            // Already set, ignore
            return;
        }

        OneTimeSettingsListener observer = new OneTimeSettingsListener(context, namespace, key,
                DEFAULT_TIMEOUT_MS);
        delete(namespace, key);
        observer.assertCalled();
        assertNewValue(namespace, key, "null");
    }

    /**
     * Gets the value of a given preference using Shell command.
     */
    @NonNull
    public static String get(@NonNull String namespace, @NonNull String key) {
        return ShellHelper.runShellCommand("settings get %s %s", namespace, key);
    }

    private static void assertNewValue(@NonNull String namespace, @NonNull String key,
            @Nullable String expectedValue) {
        String actualValue = get(namespace, key);
        if (!Objects.equals(actualValue, expectedValue)) {
            throw new AssertionError("invalid value for " + namespace + ":" + key + ": expected '"
                    + actualValue + "' , got '" + expectedValue + "'");
        }
    }

    private SettingsHelper() {
        throw new UnsupportedOperationException("contain static methods only");
    }
}
