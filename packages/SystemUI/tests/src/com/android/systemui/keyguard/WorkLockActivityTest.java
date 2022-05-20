/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.keyguard;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.UserHandle;
import android.os.UserManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * runtest systemui -c com.android.systemui.keyguard.WorkLockActivityTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class WorkLockActivityTest extends SysuiTestCase {
    private static final @UserIdInt int USER_ID = 270;
    private static final String CALLING_PACKAGE_NAME = "com.android.test";

    private @Mock UserManager mUserManager;
    private @Mock PackageManager mPackageManager;
    private @Mock Context mContext;
    private @Mock BroadcastDispatcher mBroadcastDispatcher;
    private @Mock Drawable mDrawable;
    private @Mock Drawable mBadgedDrawable;

    private WorkLockActivity mActivity;

    private static class WorkLockActivityTestable extends WorkLockActivity {
        WorkLockActivityTestable(Context baseContext, BroadcastDispatcher broadcastDispatcher,
                UserManager userManager, PackageManager packageManager) {
            super(broadcastDispatcher, userManager, packageManager);
            attachBaseContext(baseContext);
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        mActivity = new WorkLockActivityTestable(mContext, mBroadcastDispatcher, mUserManager,
                mPackageManager);
    }

    @Test
    public void testGetBadgedIcon() throws Exception {
        ApplicationInfo info = new ApplicationInfo();
        when(mPackageManager.getApplicationInfoAsUser(eq(CALLING_PACKAGE_NAME), any(),
                eq(USER_ID))).thenReturn(info);
        when(mPackageManager.getApplicationIcon(eq(info))).thenReturn(mDrawable);
        when(mUserManager.getBadgedIconForUser(any(), eq(UserHandle.of(USER_ID)))).thenReturn(
                mBadgedDrawable);
        mActivity.setIntent(new Intent()
                .putExtra(Intent.EXTRA_USER_ID, USER_ID)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, CALLING_PACKAGE_NAME));

        assertEquals(mBadgedDrawable, mActivity.getBadgedIcon());
    }

    @Test
    public void testUnregisteredFromDispatcher() {
        mActivity.unregisterBroadcastReceiver();
        verify(mBroadcastDispatcher).unregisterReceiver(any());
        verify(mContext, never()).unregisterReceiver(any());
    }
}
