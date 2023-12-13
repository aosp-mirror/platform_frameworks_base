/**
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

package com.android.internal.config.sysui;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Build;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Provides a central definition of debug SystemUI's SystemProperties flags, and their defaults.
 *
 * The main feature of this class is that it encodes a system-wide default for each flag which can
 *  be updated by engineers with a single-line CL.
 *
 * NOTE: Because flag values returned by this class are not cached, it is important that developers
 *  understand the intricacies of changing values and how that applies to their own code.
 *  Generally, the best practice is to set the property, and then restart the device so that any
 *  processes with stale state can be updated.  However, if your code has no state derived from the
 *  flag value and queries it any time behavior is relevant, then it may be safe to change the flag
 *  and not immediately reboot.
 *
 * To enable flags in debuggable builds, use the following commands:
 *
 * $ adb shell setprop persist.sysui.whatever_the_flag true
 * $ adb reboot
 *
 * @hide
 */
public class SystemUiSystemPropertiesFlags {

    /** The teamfood flag allows multiple features to be opted into at once. */
    public static final Flag TEAMFOOD = devFlag("persist.sysui.teamfood");

    /**
     * Flags related to notification features
     */
    public static final class NotificationFlags {

        /** Gating the logging of DND state change events. */
        public static final Flag LOG_DND_STATE_EVENTS =
                releasedFlag("persist.sysui.notification.log_dnd_state_events");

        /** Gating storing NotificationRankingUpdate ranking map in shared memory. */
        public static final Flag RANKING_UPDATE_ASHMEM = devFlag(
                "persist.sysui.notification.ranking_update_ashmem");

        public static final Flag PROPAGATE_CHANNEL_UPDATES_TO_CONVERSATIONS = releasedFlag(
                "persist.sysui.notification.propagate_channel_updates_to_conversations");

        // TODO b/291899544: for released flags, use resource config values
        /** Value used by polite notif. feature */
        public static final Flag NOTIF_COOLDOWN_T1 = devFlag(
                "persist.debug.sysui.notification.notif_cooldown_t1", 60000);
        /** Value used by polite notif. feature */
        public static final Flag NOTIF_COOLDOWN_T2 = devFlag(
                "persist.debug.sysui.notification.notif_cooldown_t2", 5000);
        /** Value used by polite notif. feature */
        public static final Flag NOTIF_VOLUME1 = devFlag(
                "persist.debug.sysui.notification.notif_volume1", 30);
        public static final Flag NOTIF_VOLUME2 = devFlag(
                "persist.debug.sysui.notification.notif_volume2", 0);
        /** Value used by polite notif. feature. -1 to ignore the counter */
        public static final Flag NOTIF_COOLDOWN_COUNTER_RESET = devFlag(
                "persist.debug.sysui.notification.notif_cooldown_counter_reset", 10);

        /** b/303716154: For debugging only: use short bitmap duration. */
        public static final Flag DEBUG_SHORT_BITMAP_DURATION = devFlag(
                "persist.sysui.notification.debug_short_bitmap_duration");
    }

    //// == End of flags.  Everything below this line is the implementation. == ////

    /** The interface used for resolving SystemUI SystemProperties Flags to booleans. */
    public interface FlagResolver {
        /** Is the flag enabled? */
        boolean isEnabled(Flag flag);
        /** Get the flag value (integer) */
        int getIntValue(Flag flag);
        /** Get the flag value (string) */
        String getStringValue(Flag flag);
    }

    /** The primary, immutable resolver returned by getResolver() */
    private static final FlagResolver
            MAIN_RESOLVER =
            Build.IS_DEBUGGABLE ? new DebugResolver() : new ProdResolver();

    /**
     * On debuggable builds, this can be set to override the resolver returned by getResolver().
     * This can be useful to override flags when testing components that do not allow injecting the
     * SystemUiPropertiesFlags resolver they use.
     * Always set this to null when tests tear down.
     */
    @VisibleForTesting
    public static FlagResolver TEST_RESOLVER = null;

    /** Get the resolver for this device configuration. */
    public static FlagResolver getResolver() {
        if (Build.IS_DEBUGGABLE && TEST_RESOLVER != null) {
            Log.i("SystemUiSystemPropertiesFlags", "Returning debug resolver " + TEST_RESOLVER);
            return TEST_RESOLVER;
        }
        return MAIN_RESOLVER;
    }

    /**
     * Creates a flag that is disabled by default in debuggable builds.
     * It can be enabled by setting this flag's SystemProperty to 1.
     *
     * This flag is ALWAYS disabled in release builds.
     */
    @VisibleForTesting
    public static Flag devFlag(String name) {
        return new Flag(name, false, null);
    }

    /**
     * Creates a flag that with a default integer value in debuggable builds.
     */
    @VisibleForTesting
    public static Flag devFlag(String name, int defaultValue) {
        return new Flag(name, defaultValue, null);
    }

    /**
     * Creates a flag that with a default string value in debuggable builds.
     */
    @VisibleForTesting
    public static Flag devFlag(String name, String defaultValue) {
        return new Flag(name, defaultValue, null);
    }

    /**
     * Creates a flag that is disabled by default in debuggable builds.
     * It can be enabled or force-disabled by setting this flag's SystemProperty to 1 or 0.
     * If this flag's SystemProperty is not set, the flag can be enabled by setting the
     * TEAMFOOD flag's SystemProperty to 1.
     *
     * This flag is ALWAYS disabled in release builds.
     */
    @VisibleForTesting
    public static Flag teamfoodFlag(String name) {
        return new Flag(name, false, TEAMFOOD);
    }

    /**
     * Creates a flag that is enabled by default in debuggable builds.
     * It can be enabled by setting this flag's SystemProperty to 0.
     *
     * This flag is ALWAYS enabled in release builds.
     */
    @VisibleForTesting
    public static Flag releasedFlag(String name) {
        return new Flag(name, true, null);
    }

    /** Represents a developer-switchable gate for a feature. */
    public static final class Flag {
        public final String mSysPropKey;
        public final boolean mDefaultValue;
        public final int mDefaultIntValue;
        public final String mDefaultStringValue;
        @Nullable
        public final Flag mDebugDefault;

        /** constructs a new flag.  only visible for testing the class */
        @VisibleForTesting
        public Flag(@NonNull String sysPropKey, boolean defaultValue, @Nullable Flag debugDefault) {
            mSysPropKey = sysPropKey;
            mDefaultValue = defaultValue;
            mDebugDefault = debugDefault;
            mDefaultIntValue = 0;
            mDefaultStringValue = null;
        }

        public Flag(@NonNull String sysPropKey, int defaultValue, @Nullable Flag debugDefault) {
            mSysPropKey = sysPropKey;
            mDefaultIntValue = defaultValue;
            mDebugDefault = debugDefault;
            mDefaultValue = false;
            mDefaultStringValue = null;
        }

        public Flag(@NonNull String sysPropKey, String defaultValue, @Nullable Flag debugDefault) {
            mSysPropKey = sysPropKey;
            mDefaultStringValue = defaultValue;
            mDebugDefault = debugDefault;
            mDefaultValue = false;
            mDefaultIntValue = 0;
        }
    }

    /** Implementation of the interface used in release builds. */
    @VisibleForTesting
    public static final class ProdResolver implements
            FlagResolver {
        @Override
        public boolean isEnabled(Flag flag) {
            return flag.mDefaultValue;
        }

        @Override
        public int getIntValue(Flag flag) {
            return flag.mDefaultIntValue;
        }

        @Override
        public String getStringValue(Flag flag) {
            return flag.mDefaultStringValue;
        }
    }

    /** Implementation of the interface used in debuggable builds. */
    @VisibleForTesting
    public static class DebugResolver implements FlagResolver {
        @Override
        public final boolean isEnabled(Flag flag) {
            if (flag.mDebugDefault == null) {
                return getBoolean(flag.mSysPropKey, flag.mDefaultValue);
            }
            return getBoolean(flag.mSysPropKey, isEnabled(flag.mDebugDefault));
        }

        /** Look up the value; overridable for tests to avoid needing to set SystemProperties */
        @VisibleForTesting
        public boolean getBoolean(String key, boolean defaultValue) {
            return SystemProperties.getBoolean(key, defaultValue);
        }

        /** Look up the value; overridable for tests to avoid needing to set SystemProperties */
        @VisibleForTesting
        public int getIntValue(Flag flag) {
            if (flag.mDebugDefault == null) {
                return SystemProperties.getInt(flag.mSysPropKey, flag.mDefaultIntValue);
            }
            return SystemProperties.getInt(flag.mSysPropKey, getIntValue(flag.mDebugDefault));
        }

        /** Look up the value; overridable for tests to avoid needing to set SystemProperties */
        @VisibleForTesting
        public String getStringValue(Flag flag) {
            if (flag.mDebugDefault == null) {
                return SystemProperties.get(flag.mSysPropKey, flag.mDefaultStringValue);
            }
            return SystemProperties.get(flag.mSysPropKey, getStringValue(flag.mDebugDefault));
        }
    }
}
