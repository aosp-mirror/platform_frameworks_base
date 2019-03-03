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

package com.android.systemui.statusbar.phone;

import static android.provider.Settings.Secure.NOTIFICATION_NEW_INTERRUPTION_MODEL;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationMediaManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationIconAreaControllerTest extends SysuiTestCase {

    @Mock
    private NotificationListener mListener;
    @Mock
    StatusBar mStatusBar;
    @Mock
    StatusBarStateController mStatusBarStateController;
    @Mock
    private NotificationMediaManager mMediaManager;
    private NotificationIconAreaController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mController = new NotificationIconAreaController(mContext, mStatusBar,
                mStatusBarStateController, mListener, mMediaManager);
    }

    @Test
    public void testNotificationIcons_featureOff() {
        Settings.Secure.putInt(
                mContext.getContentResolver(), NOTIFICATION_NEW_INTERRUPTION_MODEL, 0);
        assertTrue(mController.shouldShouldLowPriorityIcons());
    }

    @Test
    public void testNotificationIcons_featureOn_settingHideIcons() {
        Settings.Secure.putInt(
                mContext.getContentResolver(), NOTIFICATION_NEW_INTERRUPTION_MODEL, 1);
        mController.mSettingsListener.onStatusBarIconsBehaviorChanged(true);

        assertFalse(mController.shouldShouldLowPriorityIcons());
    }

    @Test
    public void testNotificationIcons_featureOn_settingShowIcons() {
        Settings.Secure.putInt(
                mContext.getContentResolver(), NOTIFICATION_NEW_INTERRUPTION_MODEL, 1);
        mController.mSettingsListener.onStatusBarIconsBehaviorChanged(false);

        assertTrue(mController.shouldShouldLowPriorityIcons());
    }
}
