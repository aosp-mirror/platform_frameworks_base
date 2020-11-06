/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.test.nullhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayList;
import java.util.List;

/*
 * Check if NullHome/SystemUserHome activity does not exist/is disabled.
 *
 * SystemUserHome is only enabled in bootable CSI (csi_x86, csi_arm64)
 * products and should not be enabled in other products.
 *
 * Shell's NullHome is empty and caused issues in sevaral manual GUI tests
 * that try to select/use it, and should be removed.
 *
 * Settings' FallbackHome is fine because it's specially handled by Settings.
 *
 */

@RunWith(JUnit4.class)
public class NullHomeTest {
    private static final String TAG = "NullHomeTest";
    private Context mContext;
    private PackageManager mPm;

    @Before
    public void before() {
        Log.d(TAG, "beforeClass()");
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mPm = mContext.getPackageManager();
    }

    @Test
    public void checkNullHome() {
        final List<ResolveInfo> homeActivities = new ArrayList<>();

        mPm.getHomeActivities(homeActivities);
        for (ResolveInfo activity : homeActivities) {
            Log.d(TAG, "Home activity: " + activity.activityInfo.packageName);
            Assert.assertNotEquals(activity.activityInfo.packageName,
                    "com.android.internal.app.SystemUserHomeActivity");
            Assert.assertNotEquals(activity.activityInfo.packageName,
                    "com.android.shell");
        }
    }
}
