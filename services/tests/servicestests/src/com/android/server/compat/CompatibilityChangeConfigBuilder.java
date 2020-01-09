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

import android.compat.Compatibility;

import com.android.internal.compat.CompatibilityChangeConfig;

import java.util.HashSet;
import java.util.Set;

class CompatibilityChangeConfigBuilder {
    private Set<Long> mEnabled;
    private Set<Long> mDisabled;

    private CompatibilityChangeConfigBuilder() {
        mEnabled = new HashSet<>();
        mDisabled = new HashSet<>();
    }

    static CompatibilityChangeConfigBuilder create() {
        return new CompatibilityChangeConfigBuilder();
    }

    CompatibilityChangeConfigBuilder enable(Long id) {
        mEnabled.add(id);
        return this;
    }

    CompatibilityChangeConfigBuilder disable(Long id) {
        mDisabled.add(id);
        return this;
    }

    CompatibilityChangeConfig build() {
        return new CompatibilityChangeConfig(new Compatibility.ChangeConfig(mEnabled, mDisabled));
    }
}
