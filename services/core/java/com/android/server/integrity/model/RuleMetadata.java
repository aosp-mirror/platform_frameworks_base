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

package com.android.server.integrity.model;

import android.annotation.Nullable;

/** Data class containing relevant metadata associated with a rule set. */
public class RuleMetadata {

    private final String mRuleProvider;
    private final String mVersion;

    public RuleMetadata(String ruleProvider, String version) {
        mRuleProvider = ruleProvider;
        mVersion = version;
    }

    @Nullable
    public String getRuleProvider() {
        return mRuleProvider;
    }

    @Nullable
    public String getVersion() {
        return mVersion;
    }
}
