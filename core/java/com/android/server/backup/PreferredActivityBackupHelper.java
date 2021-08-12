/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.backup;

import android.annotation.StringDef;
import android.annotation.UserIdInt;
import android.app.AppGlobals;
import android.app.backup.BlobBackupHelper;
import android.content.pm.IPackageManager;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.util.Slog;

public class PreferredActivityBackupHelper extends BlobBackupHelper {
    private static final String TAG = "PreferredBackup";
    private static final boolean DEBUG = false;

    // current schema of the backup state blob
    private static final int STATE_VERSION = 4;

    // key under which the preferred-activity state blob is committed to backup
    private static final String KEY_PREFERRED = "preferred-activity";

    // key for default-browser [etc] state
    private static final String KEY_DEFAULT_APPS = "default-apps";

    /**
     * Intent-filter verification state
     * @deprecated Replaced by {@link #KEY_DOMAIN_VERIFICATION}, retained to ensure the key is
     * never reused.
     */
    @Deprecated
    private static final String KEY_INTENT_VERIFICATION = "intent-verification";

    /**
     * State for {@link DomainVerificationManager}.
     */
    private static final String KEY_DOMAIN_VERIFICATION = "domain-verification";

    private static final String[] KEYS = new String[] {
            KEY_PREFERRED,
            KEY_DEFAULT_APPS,
            KEY_INTENT_VERIFICATION,
            KEY_DOMAIN_VERIFICATION
    };

    @StringDef(value = {
            KEY_PREFERRED,
            KEY_DEFAULT_APPS,
            KEY_INTENT_VERIFICATION,
            KEY_DOMAIN_VERIFICATION
    })
    private @interface Key {
    }

    @UserIdInt
    private final int mUserId;

    public PreferredActivityBackupHelper(@UserIdInt int userId) {
        super(STATE_VERSION, KEYS);
        mUserId = userId;
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        IPackageManager pm = AppGlobals.getPackageManager();
        if (DEBUG) {
            Slog.d(TAG, "Handling backup of " + key);
        }
        try {
            switch (key) {
                case KEY_PREFERRED:
                    return pm.getPreferredActivityBackup(mUserId);
                case KEY_DEFAULT_APPS:
                    return pm.getDefaultAppsBackup(mUserId);
                case KEY_INTENT_VERIFICATION:
                    // Deprecated
                    return null;
                case KEY_DOMAIN_VERIFICATION:
                    return pm.getDomainVerificationBackup(mUserId);
                default:
                    Slog.w(TAG, "Unexpected backup key " + key);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unable to store payload " + key, e);
        }
        return null;
    }

    @Override
    protected void applyRestoredPayload(@Key String key, byte[] payload) {
        IPackageManager pm = AppGlobals.getPackageManager();
        if (DEBUG) {
            Slog.d(TAG, "Handling restore of " + key);
        }
        try {
            switch (key) {
                case KEY_PREFERRED:
                    pm.restorePreferredActivities(payload, mUserId);
                    break;
                case KEY_DEFAULT_APPS:
                    pm.restoreDefaultApps(payload, mUserId);
                    break;
                case KEY_INTENT_VERIFICATION:
                    // Deprecated
                    break;
                case KEY_DOMAIN_VERIFICATION:
                    pm.restoreDomainVerification(payload, mUserId);
                    break;
                default:
                    Slog.w(TAG, "Unexpected restore key " + key);
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unable to restore key " + key, e);
        }
    }
}
