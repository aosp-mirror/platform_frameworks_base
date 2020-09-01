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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.UiThreadTest;
import android.util.ArraySet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@UiThreadTest
public class AppOpsInfoTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final int TEST_UID = 1;

    private AppOpsInfo mAppOpsInfo;
    private final PackageManager mMockPackageManager = mock(PackageManager.class);
    private final NotificationGuts mGutsParent = mock(NotificationGuts.class);
    private StatusBarNotification mSbn;
    private UiEventLoggerFake mUiEventLogger = new UiEventLoggerFake();

    @Before
    public void setUp() throws Exception {
        // Inflate the layout
        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        mAppOpsInfo = (AppOpsInfo) layoutInflater.inflate(R.layout.app_ops_info, null);
        mAppOpsInfo.setGutsParent(mGutsParent);

        // PackageManager must return a packageInfo and applicationInfo.
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = TEST_PACKAGE_NAME;
        when(mMockPackageManager.getPackageInfo(eq(TEST_PACKAGE_NAME), anyInt()))
                .thenReturn(packageInfo);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = TEST_UID;  // non-zero
        when(mMockPackageManager.getApplicationInfo(anyString(), anyInt())).thenReturn(
                applicationInfo);

        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                new Notification(), UserHandle.CURRENT, null, 0);
    }

    @Test
    public void testBindNotification_SetsTextApplicationName() {
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mAppOpsInfo.bindGuts(mMockPackageManager, null, mSbn, mUiEventLogger, new ArraySet<>());
        final TextView textView = mAppOpsInfo.findViewById(R.id.pkgname);
        assertTrue(textView.getText().toString().contains("App Name"));
    }

    @Test
    public void testBindNotification_SetsPackageIcon() {
        final Drawable iconDrawable = mock(Drawable.class);
        when(mMockPackageManager.getApplicationIcon(any(ApplicationInfo.class)))
                .thenReturn(iconDrawable);
        mAppOpsInfo.bindGuts(mMockPackageManager, null, mSbn, mUiEventLogger, new ArraySet<>());
        final ImageView iconView = mAppOpsInfo.findViewById(R.id.pkgicon);
        assertEquals(iconDrawable, iconView.getDrawable());
    }

    @Test
    public void testBindNotification_SetsOnClickListenerForSettings() throws Exception {
        ArraySet<Integer> expectedOps = new ArraySet<>();
        expectedOps.add(OP_CAMERA);
        final CountDownLatch latch = new CountDownLatch(1);
        mAppOpsInfo.bindGuts(mMockPackageManager, (View v, String pkg, int uid,
                ArraySet<Integer> ops) -> {
            assertEquals(TEST_PACKAGE_NAME, pkg);
            assertEquals(expectedOps, ops);
            assertEquals(TEST_UID, uid);
            latch.countDown();
        }, mSbn, mUiEventLogger, expectedOps);

        final View settingsButton = mAppOpsInfo.findViewById(R.id.settings);
        settingsButton.performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testBindNotification_LogsOpen() throws Exception {
        mAppOpsInfo.bindGuts(mMockPackageManager, null, mSbn, mUiEventLogger, new ArraySet<>());
        assertEquals(1, mUiEventLogger.numLogs());
        assertEquals(NotificationAppOpsEvent.NOTIFICATION_APP_OPS_OPEN.getId(),
                mUiEventLogger.eventId(0));
    }

    @Test
    public void testOk() {
        ArraySet<Integer> expectedOps = new ArraySet<>();
        expectedOps.add(OP_CAMERA);
        final CountDownLatch latch = new CountDownLatch(1);
        mAppOpsInfo.bindGuts(mMockPackageManager, (View v, String pkg, int uid,
                ArraySet<Integer> ops) -> {
            assertEquals(TEST_PACKAGE_NAME, pkg);
            assertEquals(expectedOps, ops);
            assertEquals(TEST_UID, uid);
            latch.countDown();
        }, mSbn, mUiEventLogger, expectedOps);

        final View okButton = mAppOpsInfo.findViewById(R.id.ok);
        okButton.performClick();
        assertEquals(1, latch.getCount());
        verify(mGutsParent, times(1)).closeControls(eq(okButton), anyBoolean());
    }

    @Test
    public void testPrompt_camera() {
        ArraySet<Integer> expectedOps = new ArraySet<>();
        expectedOps.add(OP_CAMERA);
        mAppOpsInfo.bindGuts(mMockPackageManager, null, mSbn, mUiEventLogger, expectedOps);
        TextView prompt = mAppOpsInfo.findViewById(R.id.prompt);
        assertEquals("This app is using the camera.", prompt.getText());
    }

    @Test
    public void testPrompt_mic() {
        ArraySet<Integer> expectedOps = new ArraySet<>();
        expectedOps.add(OP_RECORD_AUDIO);
        mAppOpsInfo.bindGuts(mMockPackageManager, null, mSbn, mUiEventLogger, expectedOps);
        TextView prompt = mAppOpsInfo.findViewById(R.id.prompt);
        assertEquals("This app is using the microphone.", prompt.getText());
    }

    @Test
    public void testPrompt_overlay() {
        ArraySet<Integer> expectedOps = new ArraySet<>();
        expectedOps.add(OP_SYSTEM_ALERT_WINDOW);
        mAppOpsInfo.bindGuts(mMockPackageManager, null, mSbn, mUiEventLogger, expectedOps);
        TextView prompt = mAppOpsInfo.findViewById(R.id.prompt);
        assertEquals("This app is displaying over other apps on your screen.", prompt.getText());
    }

    @Test
    public void testPrompt_camera_mic() {
        ArraySet<Integer> expectedOps = new ArraySet<>();
        expectedOps.add(OP_CAMERA);
        expectedOps.add(OP_RECORD_AUDIO);
        mAppOpsInfo.bindGuts(mMockPackageManager, null, mSbn, mUiEventLogger, expectedOps);
        TextView prompt = mAppOpsInfo.findViewById(R.id.prompt);
        assertEquals("This app is using the microphone and camera.", prompt.getText());
    }

    @Test
    public void testPrompt_camera_mic_overlay() {
        ArraySet<Integer> expectedOps = new ArraySet<>();
        expectedOps.add(OP_CAMERA);
        expectedOps.add(OP_RECORD_AUDIO);
        expectedOps.add(OP_SYSTEM_ALERT_WINDOW);
        mAppOpsInfo.bindGuts(mMockPackageManager, null, mSbn, mUiEventLogger, expectedOps);
        TextView prompt = mAppOpsInfo.findViewById(R.id.prompt);
        assertEquals("This app is displaying over other apps on your screen and using"
                + " the microphone and camera.", prompt.getText());
    }

    @Test
    public void testPrompt_camera_overlay() {
        ArraySet<Integer> expectedOps = new ArraySet<>();
        expectedOps.add(OP_CAMERA);
        expectedOps.add(OP_SYSTEM_ALERT_WINDOW);
        mAppOpsInfo.bindGuts(mMockPackageManager, null, mSbn, mUiEventLogger, expectedOps);
        TextView prompt = mAppOpsInfo.findViewById(R.id.prompt);
        assertEquals("This app is displaying over other apps on your screen and using"
                + " the camera.", prompt.getText());
    }

    @Test
    public void testPrompt_mic_overlay() {
        ArraySet<Integer> expectedOps = new ArraySet<>();
        expectedOps.add(OP_RECORD_AUDIO);
        expectedOps.add(OP_SYSTEM_ALERT_WINDOW);
        mAppOpsInfo.bindGuts(mMockPackageManager, null, mSbn, mUiEventLogger, expectedOps);
        TextView prompt = mAppOpsInfo.findViewById(R.id.prompt);
        assertEquals("This app is displaying over other apps on your screen and using"
                + " the microphone.", prompt.getText());
    }
}
