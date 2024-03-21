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

package android.credentials.selection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;

/**
 * Result of creating a Credential Manager UI intent.
 *
 * @hide
 */
public final class IntentCreationResult {
    @NonNull
    private final Intent mIntent;
    @Nullable
    private final String mFallbackUiPackageName;
    @Nullable
    private final String mOemUiPackageName;
    @NonNull
    private final OemUiUsageStatus mOemUiUsageStatus;

    private IntentCreationResult(@NonNull Intent intent, @Nullable String fallbackUiPackageName,
            @Nullable String oemUiPackageName, OemUiUsageStatus oemUiUsageStatus) {
        mIntent = intent;
        mFallbackUiPackageName = fallbackUiPackageName;
        mOemUiPackageName = oemUiPackageName;
        mOemUiUsageStatus = oemUiUsageStatus;
    }

    /** Returns the UI intent. */
    @NonNull
    public Intent getIntent() {
        return mIntent;
    }

    /**
     * Returns the result of attempting to use the config_oemCredentialManagerDialogComponent
     * as the Credential Manager UI.
     */
    @NonNull
    public OemUiUsageStatus getOemUiUsageStatus() {
        return mOemUiUsageStatus;
    }

    /**
     * Returns the package name of the ui component specified in
     * config_fallbackCredentialManagerDialogComponent, or null if unspecified / not parsable
     * successfully.
     */
    @Nullable
    public String getFallbackUiPackageName() {
        return mFallbackUiPackageName;
    }

    /**
     * Returns the package name of the oem ui component specified in
     * config_oemCredentialManagerDialogComponent, or null if unspecified / not parsable.
     */
    @Nullable
    public String getOemUiPackageName() {
        return mOemUiPackageName;
    }

    /**
     * Result of attempting to use the config_oemCredentialManagerDialogComponent as the Credential
     * Manager UI.
     */
    public enum OemUiUsageStatus {
        UNKNOWN,
        // Success: the UI specified in config_oemCredentialManagerDialogComponent was used to
        // fulfill the request.
        SUCCESS,
        // The config value was not specified (e.g. left empty).
        OEM_UI_CONFIG_NOT_SPECIFIED,
        // The config value component was specified but not found (e.g. component doesn't exist or
        // component isn't a system app).
        OEM_UI_CONFIG_SPECIFIED_BUT_NOT_FOUND,
        // The config value component was found but not enabled.
        OEM_UI_CONFIG_SPECIFIED_FOUND_BUT_NOT_ENABLED,
    }

    /**
     * Builder for {@link IntentCreationResult}.
     *
     * @hide
     */
    public static final class Builder {
        @NonNull
        private Intent mIntent;
        @Nullable
        private String mFallbackUiPackageName = null;
        @Nullable
        private String mOemUiPackageName = null;
        @NonNull
        private OemUiUsageStatus mOemUiUsageStatus = OemUiUsageStatus.UNKNOWN;

        public Builder(Intent intent) {
            mIntent = intent;
        }

        /**
         * Sets the package name of the ui component specified in
         * config_fallbackCredentialManagerDialogComponent, or null if unspecified / not parsable
         * successfully.
         */
        @NonNull
        public Builder setFallbackUiPackageName(@Nullable String fallbackUiPackageName) {
            mFallbackUiPackageName = fallbackUiPackageName;
            return this;
        }

        /**
         * Sets the package name of the oem ui component specified in
         * config_oemCredentialManagerDialogComponent, or null if unspecified / not parsable.
         */
        @NonNull
        public Builder setOemUiPackageName(@Nullable String oemUiPackageName) {
            mOemUiPackageName = oemUiPackageName;
            return this;
        }

        /**
         * Sets the result of attempting to use the config_oemCredentialManagerDialogComponent
         * as the Credential Manager UI.
         */
        @NonNull
        public Builder setOemUiUsageStatus(OemUiUsageStatus oemUiUsageStatus) {
            mOemUiUsageStatus = oemUiUsageStatus;
            return this;
        }

        /** Builds a {@link IntentCreationResult}. */
        @NonNull
        public IntentCreationResult build() {
            return new IntentCreationResult(mIntent, mFallbackUiPackageName, mOemUiPackageName,
                    mOemUiUsageStatus);
        }
    }
}
