/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.IntDef;
import android.os.Build;
import android.os.SystemProperties;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A utility class hosting code configuring shared UID migration behavior.
 */
public final class SharedUidMigration {

    /**
     * The system property key used to configure the shared UID migration strategy.
     */
    public static final String PROPERTY_KEY = "persist.debug.pm.shared_uid_migration_strategy";

    /**
     * Only leave shared UID for newly installed packages.
     */
    public static final int NEW_INSTALL_ONLY = 1;
    /**
     * Opportunistically migrate any single-package shared UID group.
     */
    public static final int BEST_EFFORT = 2;
    /**
     * Run appId transitions when the device reboots.
     */
    public static final int TRANSITION_AT_BOOT = 3;
    /**
     * Run appId transitions as soon as the upgrade is installed.
     */
    public static final int LIVE_TRANSITION = 4;

    @IntDef({
            NEW_INSTALL_ONLY,
            BEST_EFFORT,
            TRANSITION_AT_BOOT,
            LIVE_TRANSITION,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Strategy {}

    private static final int DEFAULT = BEST_EFFORT;

    /**
     * All shared UID migration is disabled.
     * This is not a strategy that can be set with system properties.
     * To disable shared UID migration, change {@link #DEFAULT} to this value.
     */
    private static final int DISABLED = 0;

    /**
     * Whether shared UID migration is fully disabled. Disabled means the sharedUserMaxSdkVersion
     * attribute will be directly ignored in the parsing phase.
     */
    @SuppressWarnings("ConstantConditions")
    public static boolean isDisabled() {
        return DEFAULT == DISABLED;
    }

    /**
     * If the system is userdebug, returns the strategy to use by getting the system property
     * {@link #PROPERTY_KEY}, or else returns the default strategy enabled in the build.
     */
    public static int getCurrentStrategy() {
        if (!Build.IS_USERDEBUG) {
            return DEFAULT;
        }

        final int s = SystemProperties.getInt(PROPERTY_KEY, DEFAULT);
        // No transition strategies can be used (http://b/221088088)
        if (s > BEST_EFFORT || s <= DISABLED) {
            return DEFAULT;
        }
        return s;
    }

    /**
     * Should the strategy be used based on the current active strategy.
     */
    public static boolean applyStrategy(@Strategy int strategy) {
        return !isDisabled() && getCurrentStrategy() >= strategy;
    }

    private SharedUidMigration() {}
}
