/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.locales;

import android.annotation.Nullable;
import android.os.LocaleList;

import com.android.server.wm.ActivityTaskManagerInternal.PackageConfigurationUpdater;

/**
 * Test double for the {@link PackageConfigurationUpdater}. For use in
 * {@link LocaleManagerServiceTest}s to stub out storage and check for state-based changes.
 */
class FakePackageConfigurationUpdater implements PackageConfigurationUpdater {

    FakePackageConfigurationUpdater() {}

    LocaleList mLocales = null;

    @Override
    public PackageConfigurationUpdater setNightMode(int nightMode) {
        return this;
    }

    @Override
    public PackageConfigurationUpdater setLocales(LocaleList locales) {
        mLocales = locales;
        return this;
    }

    @Override
    public boolean commit() {
        return mLocales != null;
    }

    /**
     * Returns the locales that were stored during the test run. Returns {@code null} if no locales
     * were set.
     */
    @Nullable
    LocaleList getStoredLocales() {
        return mLocales;
    }

}
