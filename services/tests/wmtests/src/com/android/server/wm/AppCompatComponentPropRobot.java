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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

/**
 * Robot class for optIn/optOut properties.
 */
class AppCompatComponentPropRobot {
    @NonNull
    private final WindowManagerService mWm;

    AppCompatComponentPropRobot(@NonNull WindowManagerService wm) {
        mWm = wm;
    }

    void enable(@NonNull String propertyName) {
        setPropertyValue(propertyName, /* enabled */ true);
    }

    void disable(@NonNull String propertyName) {
        setPropertyValue(propertyName, /* enabled */ false);
    }

    private void setPropertyValue(@NonNull String propertyName, boolean enabled) {
        final PackageManager.Property property = new PackageManager.Property(propertyName,
                /* value */ enabled, /* packageName */ "", /* className */ "");
        final PackageManager pm = mWm.mContext.getPackageManager();
        spyOn(pm);
        try {
            doReturn(property).when(pm).getProperty(eq(propertyName), anyString());
        } catch (PackageManager.NameNotFoundException e) {
            fail(e.getLocalizedMessage());
        }
    }
}
