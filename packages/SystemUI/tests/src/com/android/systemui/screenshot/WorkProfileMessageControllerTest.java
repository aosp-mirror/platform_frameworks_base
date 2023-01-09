/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.screenshot;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.util.FakeSharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


@SmallTest
@RunWith(AndroidTestingRunner.class)
public class WorkProfileMessageControllerTest {
    private static final String DEFAULT_LABEL = "default label";
    private static final String BADGED_DEFAULT_LABEL = "badged default label";
    private static final String APP_LABEL = "app label";
    private static final String BADGED_APP_LABEL = "badged app label";
    private static final UserHandle NON_WORK_USER = UserHandle.of(0);
    private static final UserHandle WORK_USER = UserHandle.of(10);

    @Mock
    private UserManager mUserManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private Context mContext;
    @Mock
    private WorkProfileMessageController.WorkProfileMessageDisplay mMessageDisplay;
    @Mock
    private Drawable mActivityIcon;
    @Mock
    private Drawable mBadgedActivityIcon;
    @Mock
    private ActivityInfo mActivityInfo;
    @Captor
    private ArgumentCaptor<Runnable> mRunnableArgumentCaptor;

    private FakeSharedPreferences mSharedPreferences = new FakeSharedPreferences();

    private WorkProfileMessageController mMessageController;

    @Before
    public void setup() throws PackageManager.NameNotFoundException {
        MockitoAnnotations.initMocks(this);

        when(mUserManager.isManagedProfile(eq(WORK_USER.getIdentifier()))).thenReturn(true);
        when(mContext.getSharedPreferences(
                eq(WorkProfileMessageController.SHARED_PREFERENCES_NAME),
                eq(Context.MODE_PRIVATE))).thenReturn(mSharedPreferences);
        when(mContext.getString(ArgumentMatchers.anyInt())).thenReturn(DEFAULT_LABEL);
        when(mPackageManager.getUserBadgedLabel(eq(DEFAULT_LABEL), any()))
                .thenReturn(BADGED_DEFAULT_LABEL);
        when(mPackageManager.getUserBadgedLabel(eq(APP_LABEL), any()))
                .thenReturn(BADGED_APP_LABEL);
        when(mPackageManager.getActivityIcon(any(ComponentName.class)))
                .thenReturn(mActivityIcon);
        when(mPackageManager.getUserBadgedIcon(
                any(), any())).thenReturn(mBadgedActivityIcon);
        when(mPackageManager.getActivityInfo(any(),
                any(PackageManager.ComponentInfoFlags.class))).thenReturn(mActivityInfo);
        when(mActivityInfo.loadLabel(eq(mPackageManager))).thenReturn(APP_LABEL);

        mSharedPreferences.edit().putBoolean(
                WorkProfileMessageController.PREFERENCE_KEY, false).apply();

        mMessageController = new WorkProfileMessageController(mContext, mUserManager,
                mPackageManager);
    }

    @Test
    public void testOnScreenshotTaken_notManaged() {
        mMessageController.onScreenshotTaken(NON_WORK_USER, mMessageDisplay);

        verify(mMessageDisplay, never())
                .showWorkProfileMessage(any(), nullable(Drawable.class), any());
    }

    @Test
    public void testOnScreenshotTaken_alreadyDismissed() {
        mSharedPreferences.edit().putBoolean(
                WorkProfileMessageController.PREFERENCE_KEY, true).apply();

        mMessageController.onScreenshotTaken(WORK_USER, mMessageDisplay);

        verify(mMessageDisplay, never())
                .showWorkProfileMessage(any(), nullable(Drawable.class), any());
    }

    @Test
    public void testOnScreenshotTaken_packageNotFound()
            throws PackageManager.NameNotFoundException {
        when(mPackageManager.getActivityInfo(any(),
                any(PackageManager.ComponentInfoFlags.class))).thenThrow(
                new PackageManager.NameNotFoundException());

        mMessageController.onScreenshotTaken(WORK_USER, mMessageDisplay);

        verify(mMessageDisplay).showWorkProfileMessage(
                eq(BADGED_DEFAULT_LABEL), eq(null), any());
    }

    @Test
    public void testOnScreenshotTaken() {
        mMessageController.onScreenshotTaken(WORK_USER, mMessageDisplay);

        verify(mMessageDisplay).showWorkProfileMessage(
                eq(BADGED_APP_LABEL), eq(mBadgedActivityIcon), mRunnableArgumentCaptor.capture());

        // Dismiss hasn't been tapped, preference untouched.
        assertFalse(
                mSharedPreferences.getBoolean(WorkProfileMessageController.PREFERENCE_KEY, false));

        mRunnableArgumentCaptor.getValue().run();

        // After dismiss has been tapped, the setting should be updated.
        assertTrue(
                mSharedPreferences.getBoolean(WorkProfileMessageController.PREFERENCE_KEY, false));
    }
}

