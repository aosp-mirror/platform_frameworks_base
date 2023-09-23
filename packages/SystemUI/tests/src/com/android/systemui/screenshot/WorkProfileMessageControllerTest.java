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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.testing.AndroidTestingRunner;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.systemui.res.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.FakeSharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import kotlin.Unit;


@SmallTest
@RunWith(AndroidTestingRunner.class)
public class WorkProfileMessageControllerTest extends SysuiTestCase {
    private static final String FILES_APP_COMPONENT = "com.android.test/.FilesComponent";
    private static final String FILES_APP_LABEL = "Custom Files App";
    private static final String DEFAULT_FILES_APP_LABEL = "Files";
    private static final UserHandle NON_WORK_USER = UserHandle.of(0);
    private static final UserHandle WORK_USER = UserHandle.of(10);

    @Mock
    private UserManager mUserManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private Context mMockContext;
    @Mock
    private Drawable mActivityIcon;
    @Mock
    private Drawable mBadgedActivityIcon;
    @Mock
    private ActivityInfo mActivityInfo;

    private FakeSharedPreferences mSharedPreferences = new FakeSharedPreferences();

    private WorkProfileMessageController mMessageController;

    @Before
    public void setup() throws PackageManager.NameNotFoundException {
        MockitoAnnotations.initMocks(this);

        when(mUserManager.isManagedProfile(eq(WORK_USER.getIdentifier()))).thenReturn(true);
        when(mMockContext.getSharedPreferences(
                eq(WorkProfileMessageController.SHARED_PREFERENCES_NAME),
                eq(Context.MODE_PRIVATE))).thenReturn(mSharedPreferences);
        when(mMockContext.getString(R.string.config_sceenshotWorkProfileFilesApp))
                .thenReturn(FILES_APP_COMPONENT);
        when(mMockContext.getString(R.string.screenshot_default_files_app_name))
                .thenReturn(DEFAULT_FILES_APP_LABEL);
        when(mPackageManager.getActivityIcon(
                eq(ComponentName.unflattenFromString(FILES_APP_COMPONENT))))
                .thenReturn(mActivityIcon);
        when(mPackageManager.getUserBadgedIcon(any(), any()))
                .thenReturn(mBadgedActivityIcon);
        when(mPackageManager.getActivityInfo(
                eq(ComponentName.unflattenFromString(FILES_APP_COMPONENT)),
                any(PackageManager.ComponentInfoFlags.class)))
                .thenReturn(mActivityInfo);
        when(mActivityInfo.loadLabel(eq(mPackageManager)))
                .thenReturn(FILES_APP_LABEL);

        mSharedPreferences.edit().putBoolean(
                WorkProfileMessageController.PREFERENCE_KEY, false).apply();

        mMessageController = new WorkProfileMessageController(mMockContext, mUserManager,
                mPackageManager);
    }

    @Test
    public void testOnScreenshotTaken_notManaged() {
        assertNull(mMessageController.onScreenshotTaken(NON_WORK_USER));
    }

    @Test
    public void testOnScreenshotTaken_alreadyDismissed() {
        mSharedPreferences.edit().putBoolean(
                WorkProfileMessageController.PREFERENCE_KEY, true).apply();

        assertNull(mMessageController.onScreenshotTaken(WORK_USER));
    }

    @Test
    public void testOnScreenshotTaken_packageNotFound()
            throws PackageManager.NameNotFoundException {
        when(mPackageManager.getActivityInfo(
                eq(ComponentName.unflattenFromString(FILES_APP_COMPONENT)),
                any(PackageManager.ComponentInfoFlags.class))).thenThrow(
                new PackageManager.NameNotFoundException());

        WorkProfileMessageController.WorkProfileFirstRunData data =
                mMessageController.onScreenshotTaken(WORK_USER);

        assertEquals(DEFAULT_FILES_APP_LABEL, data.getAppName());
        assertNull(data.getIcon());
    }

    @Test
    public void testOnScreenshotTaken() {
        WorkProfileMessageController.WorkProfileFirstRunData data =
                mMessageController.onScreenshotTaken(WORK_USER);

        assertEquals(FILES_APP_LABEL, data.getAppName());
        assertEquals(mBadgedActivityIcon, data.getIcon());
    }

    @Test
    public void testOnScreenshotTaken_noFilesAppComponentDefined() {
        when(mMockContext.getString(R.string.config_sceenshotWorkProfileFilesApp))
                .thenReturn("");

        WorkProfileMessageController.WorkProfileFirstRunData data =
                mMessageController.onScreenshotTaken(WORK_USER);

        assertEquals(DEFAULT_FILES_APP_LABEL, data.getAppName());
        assertNull(data.getIcon());
    }

    @Test
    public void testPopulateView() throws InterruptedException {
        ViewGroup layout = (ViewGroup) LayoutInflater.from(mContext).inflate(
                R.layout.screenshot_work_profile_first_run, null);
        WorkProfileMessageController.WorkProfileFirstRunData data =
                new WorkProfileMessageController.WorkProfileFirstRunData(FILES_APP_LABEL,
                        mBadgedActivityIcon);
        final CountDownLatch countdown = new CountDownLatch(1);
        mMessageController.populateView(layout, data, () -> {
            countdown.countDown();
            return Unit.INSTANCE;
        });

        ImageView image = layout.findViewById(R.id.screenshot_message_icon);
        assertEquals(mBadgedActivityIcon, image.getDrawable());
        TextView text = layout.findViewById(R.id.screenshot_message_content);
        // The app name is used in a template, but at least validate that it was inserted.
        assertTrue(text.getText().toString().contains(FILES_APP_LABEL));

        // Validate that clicking the dismiss button calls back properly.
        assertEquals(1, countdown.getCount());
        layout.findViewById(R.id.message_dismiss_button).callOnClick();
        countdown.await(1000, TimeUnit.MILLISECONDS);
    }
}

