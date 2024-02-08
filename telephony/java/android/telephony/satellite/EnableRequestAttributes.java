/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.telephony.satellite;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;

import com.android.internal.telephony.flags.Flags;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * EnableRequestAttributes is used to store the attributes of the request
 * {@link SatelliteManager#requestEnabled(EnableRequestAttributes, Executor, Consumer)}
 * @hide
 */
@SystemApi
@FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
public class EnableRequestAttributes {
    /** {@code true} to enable satellite and {@code false} to disable satellite */
    private boolean mIsEnabled;
    /**
     * {@code true} to enable demo mode and {@code false} to disable. When disabling satellite,
     * {@code mIsDemoMode} is always considered as {@code false} by Telephony.
     */
    private boolean mIsDemoMode;
    /**
     * {@code true} means satellite is enabled for emergency mode, {@code false} otherwise. When
     * disabling satellite, {@code isEmergencyMode} is always considered as {@code false} by
     * Telephony.
     */
    private boolean mIsEmergencyMode;

    /**
     * Constructor from builder.
     *
     * @param builder Builder of {@link EnableRequestAttributes}.
     */
    private EnableRequestAttributes(@NonNull Builder builder) {
        this.mIsEnabled = builder.mIsEnabled;
        this.mIsDemoMode = builder.mIsDemoMode;
        this.mIsEmergencyMode = builder.mIsEmergencyMode;
    }

    /**
     * @return Whether satellite is to be enabled
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public boolean isEnabled() {
        return mIsEnabled;
    }

    /**
     * @return Whether demo mode is to be enabled
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public boolean isDemoMode() {
        return mIsDemoMode;
    }

    /**
     * @return Whether satellite is to be enabled for emergency mode
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public boolean isEmergencyMode() {
        return mIsEmergencyMode;
    }

    /**
     * The builder class of {@link EnableRequestAttributes}
     */
    @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
    public static final class Builder {
        private boolean mIsEnabled;
        private boolean mIsDemoMode = false;
        private boolean mIsEmergencyMode = false;

        @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
        public Builder(boolean isEnabled) {
            mIsEnabled = isEnabled;
        }

        /**
         * Set demo mode
         *
         * @param isDemoMode {@code true} to enable demo mode and {@code false} to disable. When
         *                   disabling satellite, {@code isDemoMode} is always considered as
         *                   {@code false} by Telephony.
         * @return The builder object
         */
        @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
        @NonNull
        public Builder setDemoMode(boolean isDemoMode) {
            if (mIsEnabled) {
                mIsDemoMode = isDemoMode;
            }
            return this;
        }

        /**
         * Set emergency mode
         *
         * @param isEmergencyMode {@code true} means satellite is enabled for emergency mode,
         *                        {@code false} otherwise. When disabling satellite,
         *                        {@code isEmergencyMode} is always considered as {@code false} by
         *                        Telephony.
         * @return The builder object
         */
        @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
        @NonNull
        public Builder setEmergencyMode(boolean isEmergencyMode) {
            if (mIsEnabled) {
                mIsEmergencyMode = isEmergencyMode;
            }
            return this;
        }

        /**
         * Build the {@link EnableRequestAttributes}
         *
         * @return The {@link EnableRequestAttributes} instance.
         */
        @FlaggedApi(Flags.FLAG_OEM_ENABLED_SATELLITE_FLAG)
        @NonNull
        public EnableRequestAttributes build() {
            return new EnableRequestAttributes(this);
        }
    }
}
