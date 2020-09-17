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

package com.android.server.signedconfig;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.util.FrameworkStatsLog;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

class GlobalSettingsConfigApplicator {

    private static final String TAG = "SignedConfig";

    private static final Set<String> ALLOWED_KEYS = Collections.unmodifiableSet(new ArraySet<>(
            Arrays.asList(
                    Settings.Global.HIDDEN_API_POLICY,
                    Settings.Global.HIDDEN_API_BLACKLIST_EXEMPTIONS
            )));

    private static final Map<String, String> HIDDEN_API_POLICY_KEY_MAP = makeMap(
            "DEFAULT", String.valueOf(ApplicationInfo.HIDDEN_API_ENFORCEMENT_DEFAULT),
            "DISABLED", String.valueOf(ApplicationInfo.HIDDEN_API_ENFORCEMENT_DISABLED),
            "JUST_WARN", String.valueOf(ApplicationInfo.HIDDEN_API_ENFORCEMENT_JUST_WARN),
            "ENABLED", String.valueOf(ApplicationInfo.HIDDEN_API_ENFORCEMENT_ENABLED)
    );

    private static final Map<String, Map<String, String>> KEY_VALUE_MAPPERS = makeMap(
            Settings.Global.HIDDEN_API_POLICY, HIDDEN_API_POLICY_KEY_MAP
    );

    private static <K, V> Map<K, V> makeMap(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException();
        }
        final int len = keyValuePairs.length / 2;
        ArrayMap<K, V> m = new ArrayMap<>(len);
        for (int i = 0; i < len;  ++i) {
            m.put((K) keyValuePairs[i * 2], (V) keyValuePairs[(i * 2) + 1]);
        }
        return Collections.unmodifiableMap(m);

    }

    private final Context mContext;
    private final String mSourcePackage;
    private final SignedConfigEvent mEvent;
    private final SignatureVerifier mVerifier;

    GlobalSettingsConfigApplicator(Context context, String sourcePackage, SignedConfigEvent event) {
        mContext = context;
        mSourcePackage = sourcePackage;
        mEvent = event;
        mVerifier = new SignatureVerifier(mEvent);
    }

    private boolean checkSignature(String data, String signature) {
        try {
            return mVerifier.verifySignature(data, signature);
        } catch (GeneralSecurityException e) {
            Slog.e(TAG, "Failed to verify signature", e);
            mEvent.status = FrameworkStatsLog.SIGNED_CONFIG_REPORTED__STATUS__SECURITY_EXCEPTION;
            return false;
        }
    }

    private int getCurrentConfigVersion() {
        return Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.SIGNED_CONFIG_VERSION, 0);
    }

    private void updateCurrentConfig(int version, Map<String, String> values) {
        for (Map.Entry<String, String> e: values.entrySet()) {
            Settings.Global.putString(
                    mContext.getContentResolver(),
                    e.getKey(),
                    e.getValue());
        }
        Settings.Global.putInt(
                mContext.getContentResolver(), Settings.Global.SIGNED_CONFIG_VERSION, version);
    }


    void applyConfig(String configStr, String signature) {
        if (!checkSignature(configStr, signature)) {
            Slog.e(TAG, "Signature check on global settings in package " + mSourcePackage
                    + " failed; ignoring");
            return;
        }
        SignedConfig config;
        try {
            config = SignedConfig.parse(configStr, ALLOWED_KEYS, KEY_VALUE_MAPPERS);
            mEvent.version = config.version;
        } catch (InvalidConfigException e) {
            Slog.e(TAG, "Failed to parse global settings from package " + mSourcePackage, e);
            mEvent.status = FrameworkStatsLog.SIGNED_CONFIG_REPORTED__STATUS__INVALID_CONFIG;
            return;
        }
        int currentVersion = getCurrentConfigVersion();
        if (currentVersion >= config.version) {
            Slog.i(TAG, "Global settings from package " + mSourcePackage
                    + " is older than existing: " + config.version + "<=" + currentVersion);
            mEvent.status = FrameworkStatsLog.SIGNED_CONFIG_REPORTED__STATUS__OLD_CONFIG;
            return;
        }
        // We have new config!
        Slog.i(TAG, "Got new global settings from package " + mSourcePackage + ": version "
                + config.version + " replacing existing version " + currentVersion);
        SignedConfig.PerSdkConfig matchedConfig =
                config.getMatchingConfig(Build.VERSION.SDK_INT);
        if (matchedConfig == null) {
            Slog.i(TAG, "Settings is not applicable to current SDK version; ignoring");
            mEvent.status = FrameworkStatsLog.SIGNED_CONFIG_REPORTED__STATUS__NOT_APPLICABLE;
            return;
        }

        Slog.i(TAG, "Updating global settings to version " + config.version);
        updateCurrentConfig(config.version, matchedConfig.values);
        mEvent.status = FrameworkStatsLog.SIGNED_CONFIG_REPORTED__STATUS__APPLIED;
    }
}
