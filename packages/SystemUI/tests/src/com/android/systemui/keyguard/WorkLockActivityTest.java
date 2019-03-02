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

import static android.app.ActivityManager.TaskDescription;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.when;

import android.annotation.ColorInt;
import android.annotation.UserIdInt;
import android.app.KeyguardManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Looper;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.WorkLockActivity;

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
    private static final String TASK_LABEL = "task label";

    private @Mock DevicePolicyManager mDevicePolicyManager;
    private @Mock KeyguardManager mKeyguardManager;
    private @Mock Context mContext;

    private WorkLockActivity mActivity;

    private static class WorkLockActivityTestable extends WorkLockActivity {
        WorkLockActivityTestable(Context baseContext) {
            super();
            attachBaseContext(baseContext);
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mContext.getSystemService(eq(Context.DEVICE_POLICY_SERVICE)))
                .thenReturn(mDevicePolicyManager);
        when(mContext.getSystemService(eq(Context.KEYGUARD_SERVICE)))
                .thenReturn(mKeyguardManager);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }
        mActivity = new WorkLockActivityTestable(mContext);
    }

    @Test
    public void testBackgroundAlwaysOpaque() throws Exception {
        final @ColorInt int orgColor = Color.rgb(250, 199, 67);
        when(mDevicePolicyManager.getOrganizationColorForUser(eq(USER_ID))).thenReturn(orgColor);

        final @ColorInt int opaqueColor= Color.rgb(164, 198, 57);
        final @ColorInt int transparentColor = Color.argb(0, 0, 0, 0);
        TaskDescription opaque = new TaskDescription(null, null, opaqueColor);
        TaskDescription transparent = new TaskDescription(null, null, transparentColor);

        // When a task description is provided with a suitable (opaque) primaryColor, it should be
        // used as the scrim's background color.
        mActivity.setIntent(new Intent()
                .putExtra(Intent.EXTRA_USER_ID, USER_ID)
                .putExtra(WorkLockActivity.EXTRA_TASK_DESCRIPTION, opaque));
        assertEquals(opaqueColor, mActivity.getPrimaryColor());

        // When a task description is provided but has no primaryColor / the primaryColor is
        // transparent, the organization color should be used instead.
        mActivity.setIntent(new Intent()
                .putExtra(Intent.EXTRA_USER_ID, USER_ID)
                .putExtra(WorkLockActivity.EXTRA_TASK_DESCRIPTION, transparent));
        assertEquals(orgColor, mActivity.getPrimaryColor());

        // When no task description is provided at all, it should be treated like a transparent
        // description and the organization color shown instead.
        mActivity.setIntent(new Intent()
                .putExtra(Intent.EXTRA_USER_ID, USER_ID));
        assertEquals(orgColor, mActivity.getPrimaryColor());
    }
}
