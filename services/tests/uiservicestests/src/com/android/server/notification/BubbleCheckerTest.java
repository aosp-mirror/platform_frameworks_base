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

package com.android.server.notification;

import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;

import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ShortcutInfo;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BubbleCheckerTest extends UiServiceTestCase {

    private static final String SHORTCUT_ID = "shortcut";
    private static final String PKG = "pkg";
    private static final String KEY = "key";
    private static final int USER_ID = 1;

    @Mock
    ActivityManager mActivityManager;
    @Mock
    RankingConfig mRankingConfig;
    @Mock
    ShortcutHelper mShortcutHelper;

    @Mock
    NotificationRecord mNr;
    @Mock
    UserHandle mUserHandle;
    @Mock
    Notification mNotif;
    @Mock
    StatusBarNotification mSbn;
    @Mock
    NotificationChannel mChannel;
    @Mock
    Notification.BubbleMetadata mBubbleMetadata;
    @Mock
    PendingIntent mPendingIntent;
    @Mock
    Intent mIntent;

    BubbleExtractor.BubbleChecker mBubbleChecker;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mNr.getKey()).thenReturn(KEY);
        when(mNr.getSbn()).thenReturn(mSbn);
        when(mNr.getUser()).thenReturn(mUserHandle);
        when(mUserHandle.getIdentifier()).thenReturn(USER_ID);
        when(mNr.getChannel()).thenReturn(mChannel);
        when(mSbn.getPackageName()).thenReturn(PKG);
        when(mSbn.getUser()).thenReturn(mUserHandle);
        when(mNr.getNotification()).thenReturn(mNotif);
        when(mNotif.getBubbleMetadata()).thenReturn(mBubbleMetadata);

        mBubbleChecker = new BubbleExtractor.BubbleChecker(mContext,
                mShortcutHelper,
                mRankingConfig,
                mActivityManager);
    }

    void setUpIntentBubble() {
        when(mPendingIntent.getIntent()).thenReturn(mIntent);
        when(mBubbleMetadata.getIntent()).thenReturn(mPendingIntent);
        when(mBubbleMetadata.getShortcutId()).thenReturn(null);
    }

    void setUpShortcutBubble(boolean isValid) {
        when(mBubbleMetadata.getShortcutId()).thenReturn(SHORTCUT_ID);
        ShortcutInfo info = mock(ShortcutInfo.class);
        when(info.getId()).thenReturn(SHORTCUT_ID);
        when(mShortcutHelper.getValidShortcutInfo(SHORTCUT_ID, PKG, mUserHandle))
                .thenReturn(isValid ? info : null);
        when(mBubbleMetadata.getIntent()).thenReturn(null);
    }

    void setUpBubblesEnabled(boolean feature, boolean app, boolean channel) {
        when(mRankingConfig.bubblesEnabled()).thenReturn(feature);
        when(mRankingConfig.areBubblesAllowed(PKG, USER_ID)).thenReturn(app);
        when(mChannel.canBubble()).thenReturn(channel);
    }

    void setUpActivityIntent(boolean isResizable) {
        when(mPendingIntent.getIntent()).thenReturn(mIntent);
        ActivityInfo info = new ActivityInfo();
        info.resizeMode = isResizable
                ? RESIZE_MODE_RESIZEABLE
                : RESIZE_MODE_UNRESIZEABLE;
        when(mIntent.resolveActivityInfo(any(), anyInt())).thenReturn(info);
    }

    //
    // canBubble
    //

    @Test
    public void testCanBubble_true_intentBubble() {
        setUpBubblesEnabled(true /* feature */, true /* app */, true /* channel */);
        setUpIntentBubble();
        setUpActivityIntent(true /* isResizable */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        assertTrue(mBubbleChecker.canBubble(mNr, PKG, USER_ID));
    }

    @Test
    public void testCanBubble_true_shortcutBubble() {
        setUpBubblesEnabled(true /* feature */, true /* app */, true /* channel */);
        setUpShortcutBubble(true /* isValid */);
        assertTrue(mBubbleChecker.canBubble(mNr, PKG, USER_ID));
    }

    @Test
    public void testCanBubble_false_noIntentInvalidShortcut() {
        setUpBubblesEnabled(true /* feature */, true /* app */, true /* channel */);
        setUpShortcutBubble(false /* isValid */);
        assertFalse(mBubbleChecker.canBubble(mNr, PKG, USER_ID));
    }

    @Test
    public void testCanBubble_false_noIntentNoShortcut() {
        setUpBubblesEnabled(true /* feature */, true /* app */, true /* channel */);
        when(mBubbleMetadata.getIntent()).thenReturn(null);
        when(mBubbleMetadata.getShortcutId()).thenReturn(null);
        assertFalse(mBubbleChecker.canBubble(mNr, PKG, USER_ID));
    }

    @Test
    public void testCanBubbble_false_noMetadata() {
        setUpBubblesEnabled(true/* feature */, true /* app */, true /* channel */);
        when(mNotif.getBubbleMetadata()).thenReturn(null);
        assertFalse(mBubbleChecker.canBubble(mNr, PKG, USER_ID));
    }

    @Test
    public void testCanBubble_false_bubblesNotEnabled() {
        setUpBubblesEnabled(false /* feature */, true /* app */, true /* channel */);
        assertFalse(mBubbleChecker.canBubble(mNr, PKG, USER_ID));
    }

    @Test
    public void testCanBubble_false_packageNotAllowed() {
        setUpBubblesEnabled(true /* feature */, false /* app */, true /* channel */);
        assertFalse(mBubbleChecker.canBubble(mNr, PKG, USER_ID));
    }

    @Test
    public void testCanBubble_false_channelNotAllowed() {
        setUpBubblesEnabled(true /* feature */, true /* app */, false /* channel */);
        assertFalse(mBubbleChecker.canBubble(mNr, PKG, USER_ID));
    }

    //
    // canLaunchInActivityView
    //

    @Test
    public void testCanLaunchInActivityView_true() {
        setUpActivityIntent(true /* resizable */);
        assertTrue(mBubbleChecker.canLaunchInActivityView(mContext, mPendingIntent, PKG));
    }

    @Test
    public void testCanLaunchInActivityView_false_noIntent() {
        when(mPendingIntent.getIntent()).thenReturn(null);
        assertFalse(mBubbleChecker.canLaunchInActivityView(mContext, mPendingIntent, PKG));
    }

    @Test
    public void testCanLaunchInActivityView_false_noInfo() {
        when(mPendingIntent.getIntent()).thenReturn(mIntent);
        when(mIntent.resolveActivityInfo(any(), anyInt())).thenReturn(null);
        assertFalse(mBubbleChecker.canLaunchInActivityView(mContext, mPendingIntent, PKG));
    }

    @Test
    public void testCanLaunchInActivityView_false_notResizable() {
        setUpActivityIntent(false  /* resizable */);
        assertFalse(mBubbleChecker.canLaunchInActivityView(mContext, mPendingIntent, PKG));
    }

    //
    // isNotificationAppropriateToBubble
    //

    @Test
    public void testIsNotifAppropriateToBubble_true() {
        setUpBubblesEnabled(true /* feature */, true /* app */, true /* channel */);
        setUpIntentBubble();
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpActivityIntent(true /* resizable */);
        doReturn(Notification.MessagingStyle.class).when(mNotif).getNotificationStyle();

        assertTrue(mBubbleChecker.isNotificationAppropriateToBubble(mNr));
    }

    @Test
    public void testIsNotifAppropriateToBubble_false_lowRam() {
        setUpBubblesEnabled(true /* feature */, true /* app */, true /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        setUpActivityIntent(true /* resizable */);
        doReturn(Notification.MessagingStyle.class).when(mNotif).getNotificationStyle();

        assertFalse(mBubbleChecker.isNotificationAppropriateToBubble(mNr));
    }

    @Test
    public void testIsNotifAppropriateToBubble_false_notMessageStyle() {
        setUpBubblesEnabled(true /* feature */, true /* app */, true /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpActivityIntent(true /* resizable */);
        doReturn(Notification.BigPictureStyle.class).when(mNotif).getNotificationStyle();

        assertFalse(mBubbleChecker.isNotificationAppropriateToBubble(mNr));
    }

}
