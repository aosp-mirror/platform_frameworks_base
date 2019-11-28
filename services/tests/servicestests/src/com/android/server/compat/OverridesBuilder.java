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
import android.os.RemoteException;

import com.android.internal.compat.CompatibilityChangeConfig;

import java.util.HashSet;
import java.util.Set;

class OverridesBuilder {
    private Set<Long> mEnabled;
    private Set<Long> mDisabled;
    private String mPackageName;

    private OverridesBuilder() {
        mEnabled = new HashSet<>();
        mDisabled = new HashSet<>();
    }

    static OverridesBuilder create() {
        return new OverridesBuilder();
    }

    OverridesBuilder enable(Long id) {
        mEnabled.add(id);
        return this;
    }

    OverridesBuilder disable(Long id) {
        mDisabled.add(id);
        return this;
    }

    OverridesBuilder toPackage(String packageName) {
        mPackageName = packageName;
        return this;
    }

    void override(CompatConfig config) throws RemoteException {
        config.addOverrides(
                new CompatibilityChangeConfig(
                        new Compatibility.ChangeConfig(mEnabled, mDisabled)), mPackageName);
    }

    void override(PlatformCompat platformCompat) throws RemoteException {
        platformCompat.setOverrides(
                new CompatibilityChangeConfig(
                        new Compatibility.ChangeConfig(mEnabled, mDisabled)), mPackageName);
    }

    void clear(PlatformCompat platformCompat) throws RemoteException {
        platformCompat.clearOverrides(mPackageName);
    }
}
