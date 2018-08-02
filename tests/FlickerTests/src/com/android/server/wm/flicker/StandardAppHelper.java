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

package com.android.server.wm.flicker;

import android.app.Instrumentation;
import android.platform.helpers.AbstractStandardAppHelper;

/**
 * Class to take advantage of {@code IAppHelper} interface so the same test can be run against
 * first party and third party apps.
 */
public class StandardAppHelper extends AbstractStandardAppHelper {
    private final String mPackageName;
    private final String mLauncherName;

    public StandardAppHelper(Instrumentation instr, String packageName, String launcherName) {
        super(instr);
        mPackageName = packageName;
        mLauncherName = launcherName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getPackage() {
        return mPackageName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getLauncherName() {
        return mLauncherName;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void dismissInitialDialogs() {

    }
}
