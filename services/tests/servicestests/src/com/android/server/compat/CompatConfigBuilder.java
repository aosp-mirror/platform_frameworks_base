/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.server.compat;

import android.content.Context;

import com.android.internal.compat.AndroidBuildClassifier;

import java.util.ArrayList;

/**
 * Helper class for creating a CompatConfig.
 */
class CompatConfigBuilder {
    private ArrayList<CompatChange> mChanges;
    private AndroidBuildClassifier mBuildClassifier;
    private Context mContext;

    private CompatConfigBuilder(AndroidBuildClassifier buildClassifier, Context context) {
        mChanges = new ArrayList<>();
        mBuildClassifier = buildClassifier;
        mContext = context;
    }

    static CompatConfigBuilder create(AndroidBuildClassifier buildClassifier, Context context) {
        return new CompatConfigBuilder(buildClassifier, context);
    }

    CompatConfigBuilder addEnableAfterSdkChangeWithId(int sdk, long id) {
        mChanges.add(new CompatChange(id, "", sdk, -1, false, false, "", false));
        return this;
    }

    CompatConfigBuilder addEnableAfterSdkChangeWithIdAndName(int sdk, long id, String name) {
        mChanges.add(new CompatChange(id, name, sdk, -1, false, false, "", false));
        return this;
    }

    CompatConfigBuilder addEnableAfterSdkChangeWithIdDefaultDisabled(int sdk, long id) {
        mChanges.add(new CompatChange(id, "", sdk, -1, true, false, "", false));
        return this;
    }

    CompatConfigBuilder addEnableAfterSdkChangeWithIdAndDescription(int sdk, long id,
            String description) {
        mChanges.add(new CompatChange(id, "", sdk, -1, false, false, description, false));
        return this;
    }

    CompatConfigBuilder addEnableSinceSdkChangeWithId(int sdk, long id) {
        mChanges.add(new CompatChange(id, "", -1, sdk, false, false, "", false));
        return this;
    }

    CompatConfigBuilder addEnableSinceSdkChangeWithIdAndName(int sdk, long id, String name) {
        mChanges.add(new CompatChange(id, name, -1, sdk, false, false, "", false));
        return this;
    }

    CompatConfigBuilder addEnableSinceSdkChangeWithIdDefaultDisabled(int sdk, long id) {
        mChanges.add(new CompatChange(id, "", -1, sdk, true, false, "", false));
        return this;
    }

    CompatConfigBuilder addEnableSinceSdkChangeWithIdAndDescription(int sdk, long id,
            String description) {
        mChanges.add(new CompatChange(id, "", -1, sdk, false, false, description, false));
        return this;
    }

    CompatConfigBuilder addEnabledChangeWithId(long id) {
        mChanges.add(new CompatChange(id, "", -1, -1, false, false, "", false));
        return this;
    }

    CompatConfigBuilder addEnabledChangeWithIdAndName(long id, String name) {
        mChanges.add(new CompatChange(id, name, -1, -1, false, false, "", false));
        return this;
    }
    CompatConfigBuilder addEnabledChangeWithIdAndDescription(long id, String description) {
        mChanges.add(new CompatChange(id, "", -1, -1, false, false, description, false));
        return this;
    }

    CompatConfigBuilder addDisabledChangeWithId(long id) {
        mChanges.add(new CompatChange(id, "", -1, -1, true, false, "", false));
        return this;
    }

    CompatConfigBuilder addDisabledChangeWithIdAndName(long id, String name) {
        mChanges.add(new CompatChange(id, name, -1, -1, true, false, "", false));
        return this;
    }

    CompatConfigBuilder addDisabledChangeWithIdAndDescription(long id, String description) {
        mChanges.add(new CompatChange(id, "", -1, -1, true, false, description, false));
        return this;
    }

    CompatConfigBuilder addLoggingOnlyChangeWithId(long id) {
        mChanges.add(new CompatChange(id, "", -1, -1, false, true, "", false));
        return this;
    }

    CompatConfigBuilder addEnabledOverridableChangeWithId(long id) {
        mChanges.add(new CompatChange(id, "", -1, -1, false, false, "", true));
        return this;
    }

    CompatConfigBuilder addDisabledOverridableChangeWithId(long id) {
        mChanges.add(new CompatChange(id, "", -1, -1, true, false, "", true));
        return this;
    }

    CompatConfigBuilder addEnabledSinceApexChangeWithId(int sdk, long id) {
        mChanges.add(new CompatChange(id, "", -1, sdk, false, false, "", false));
        return this;
    }

    CompatConfig build() {
        CompatConfig config = new CompatConfig(mBuildClassifier, mContext);
        config.forceNonDebuggableFinalForTest(false);
        for (CompatChange change : mChanges) {
            config.addChange(change);
        }
        return config;
    }
}
