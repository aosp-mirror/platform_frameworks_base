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

    CompatConfigBuilder addTargetSdkChangeWithId(int sdk, long id) {
        mChanges.add(new CompatChange(id, "", sdk, false, false, ""));
        return this;
    }

    CompatConfigBuilder addTargetSdkDisabledChangeWithId(int sdk, long id) {
        mChanges.add(new CompatChange(id, "", sdk, true, false, ""));
        return this;
    }

    CompatConfigBuilder addTargetSdkChangeWithIdAndName(int sdk, long id, String name) {
        mChanges.add(new CompatChange(id, name, sdk, false, false, ""));
        return this;
    }

    CompatConfigBuilder addTargetSdkChangeWithIdAndDescription(int sdk, long id,
            String description) {
        mChanges.add(new CompatChange(id, "", sdk, false, false, description));
        return this;
    }

    CompatConfigBuilder addEnabledChangeWithId(long id) {
        mChanges.add(new CompatChange(id, "", -1, false, false, ""));
        return this;
    }

    CompatConfigBuilder addEnabledChangeWithIdAndName(long id, String name) {
        mChanges.add(new CompatChange(id, name, -1, false, false, ""));
        return this;
    }
    CompatConfigBuilder addEnabledChangeWithIdAndDescription(long id, String description) {
        mChanges.add(new CompatChange(id, "", -1, false, false, description));
        return this;
    }

    CompatConfigBuilder addDisabledChangeWithId(long id) {
        mChanges.add(new CompatChange(id, "", -1, true, false, ""));
        return this;
    }

    CompatConfigBuilder addDisabledChangeWithIdAndName(long id, String name) {
        mChanges.add(new CompatChange(id, name, -1, true, false, ""));
        return this;
    }

    CompatConfigBuilder addDisabledChangeWithIdAndDescription(long id, String description) {
        mChanges.add(new CompatChange(id, "", -1, true, false, description));
        return this;
    }

    CompatConfigBuilder addLoggingOnlyChangeWithId(long id) {
        mChanges.add(new CompatChange(id, "", -1, false, true, ""));
        return this;
    }

    CompatConfig build() {
        CompatConfig config = new CompatConfig(mBuildClassifier, mContext);
        for (CompatChange change : mChanges) {
            config.addChange(change);
        }
        return config;
    }
}
