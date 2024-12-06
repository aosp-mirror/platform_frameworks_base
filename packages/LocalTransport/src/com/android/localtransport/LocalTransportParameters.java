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
 * limitations under the License
 */

package com.android.localtransport;

import android.content.ContentResolver;
import android.os.Handler;
import android.provider.Settings;
import android.util.KeyValueListParser;
import android.util.KeyValueSettingObserver;

import java.util.Arrays;
import java.util.List;

public class LocalTransportParameters extends KeyValueSettingObserver {
    private static final String SETTING = Settings.Secure.BACKUP_LOCAL_TRANSPORT_PARAMETERS;
    private static final String KEY_FAKE_ENCRYPTION_FLAG = "fake_encryption_flag";
    private static final String KEY_NON_INCREMENTAL_ONLY = "non_incremental_only";
    private static final String KEY_IS_DEVICE_TRANSFER = "is_device_transfer";
    private static final String KEY_IS_ENCRYPTED = "is_encrypted";
    private static final String KEY_LOG_AGENT_RESULTS = "log_agent_results";
    // This needs to be a list of package names separated by semicolons. For example:
    // "com.package1;com.package2;com.package3". We can't use commas because the base class uses
    // commas to split Key/Value pairs.
    private static final String KEY_NO_RESTRICTED_MODE_PACKAGES = "no_restricted_mode_packages";

    private boolean mFakeEncryptionFlag;
    private boolean mIsNonIncrementalOnly;
    private boolean mIsDeviceTransfer;
    private boolean mIsEncrypted;
    private boolean mLogAgentResults;
    private String mNoRestrictedModePackages;

    public LocalTransportParameters(Handler handler, ContentResolver resolver) {
        super(handler, resolver, Settings.Secure.getUriFor(SETTING));
    }

    boolean isFakeEncryptionFlag() {
        return mFakeEncryptionFlag;
    }

    boolean isNonIncrementalOnly() {
        return mIsNonIncrementalOnly;
    }

    boolean isDeviceTransfer() {
        return mIsDeviceTransfer;
    }

    boolean isEncrypted() {
        return mIsEncrypted;
    }

    boolean logAgentResults() {
        return mLogAgentResults;
    }

    List<String> noRestrictedModePackages() {
        if (mNoRestrictedModePackages == null) {
            return List.of();
        }
        return Arrays.stream(mNoRestrictedModePackages.split(";")).toList();
    }

    public String getSettingValue(ContentResolver resolver) {
        return Settings.Secure.getString(resolver, SETTING);
    }

    public void update(KeyValueListParser parser) {
        mFakeEncryptionFlag = parser.getBoolean(KEY_FAKE_ENCRYPTION_FLAG, false);
        mIsNonIncrementalOnly = parser.getBoolean(KEY_NON_INCREMENTAL_ONLY, false);
        mIsDeviceTransfer = parser.getBoolean(KEY_IS_DEVICE_TRANSFER, false);
        mIsEncrypted = parser.getBoolean(KEY_IS_ENCRYPTED, false);
        mLogAgentResults = parser.getBoolean(KEY_LOG_AGENT_RESULTS, /* def */ false);
        mNoRestrictedModePackages = parser.getString(KEY_NO_RESTRICTED_MODE_PACKAGES, /* def */ "");
    }
}
