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

        /**
         * FOR DEVELOPMENT / TESTING ONLY!!!
         * Forcibly demote *ALL* FSI notifications as if no apps have the app op permission.
         * NOTE: enabling this implies SHOW_STICKY_HUN_FOR_DENIED_FSI in SystemUI
         */
        public static final Flag FSI_FORCE_DEMOTE =
                devFlag("persist.sysui.notification.fsi_force_demote");

        /** Gating the feature which shows FSI-denied notifications as Sticky HUNs */
        public static final Flag SHOW_STICKY_HUN_FOR_DENIED_FSI =
                releasedFlag("persist.sysui.notification.show_sticky_hun_for_denied_fsi");

        /** Gating the ability for users to dismiss ongoing event notifications */
        public static final Flag ALLOW_DISMISS_ONGOING =
                releasedFlag("persist.sysui.notification.ongoing_dismissal");

        /** Gating the redaction of OTP notifications on the lockscreen */
        public static final Flag OTP_REDACTION =
                devFlag("persist.sysui.notification.otp_redaction");

        /** Gating the removal of sorting-notifications-by-interruptiveness. */
        public static final Flag NO_SORT_BY_INTERRUPTIVENESS =
                releasedFlag("persist.sysui.notification.no_sort_by_interruptiveness");

        /** Gating the logging of DND state change events. */
        public static final Flag LOG_DND_STATE_EVENTS =
                releasedFlag("persist.sysui.notification.log_dnd_state_events");

        /** Gating the holding of WakeLocks until NLSes are told about a new notification. */
        public static final Flag WAKE_LOCK_FOR_POSTING_NOTIFICATION =
                devFlag("persist.sysui.notification.wake_lock_for_posting_notification");
    }

    //// == End of flags.  Everything below this line is the implementation. == ////

    /** The interface used for resolving SystemUI SystemProperties Flags to booleans. */
    public interface FlagResolver {
        /** Is the flag enabled? */
        boolean isEnabled(Flag flag);
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
        @Nullable
        public final Flag mDebugDefault;

        /** constructs a new flag.  only visible for testing the class */
        @VisibleForTesting
        public Flag(@NonNull String sysPropKey, boolean defaultValue, @Nullable Flag debugDefault) {
            mSysPropKey = sysPropKey;
            mDefaultValue = defaultValue;
            mDebugDefault = debugDefault;
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
    }
}
