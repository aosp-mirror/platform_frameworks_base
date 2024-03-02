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
package com.android.server.notification;

import static android.app.NotificationChannel.ALLOW_BUBBLE_OFF;
import static android.app.NotificationChannel.ALLOW_BUBBLE_ON;
import static android.app.NotificationChannel.DEFAULT_ALLOW_BUBBLE;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_ALL;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_NONE;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_SELECTED;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationChannel;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ShortcutInfo;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BubbleExtractorTest extends UiServiceTestCase {

    private static final String CHANNEL_ID = "bubbleExtractorChannelId";
    private static final String SHORTCUT_ID = "shortcut";
    private static final String PKG = "com.android.server.notification";
    private static final String TAG = null;
    private static final int ID = 1001;
    private static final int UID = 1000;
    private static final int PID = 2000;
    UserHandle mUser = UserHandle.of(ActivityManager.getCurrentUser());

    BubbleExtractor mBubbleExtractor;
    NotificationChannel mChannel;

    @Mock
    RankingConfig mConfig;
    @Mock
    Notification.BubbleMetadata mBubbleMetadata;
    @Mock
    PendingIntent mPendingIntent;
    @Mock
    Intent mIntent;
    @Mock
    ShortcutInfo mShortcutInfo;
    @Mock
    ShortcutHelper mShortcutHelper;
    @Mock
    ActivityManager mActivityManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mBubbleExtractor = new BubbleExtractor();
        mBubbleExtractor.initialize(mContext, mock(NotificationUsageStats.class));
        mBubbleExtractor.setConfig(mConfig);
        mBubbleExtractor.setShortcutHelper(mShortcutHelper);
        mBubbleExtractor.setActivityManager(mActivityManager);

        mChannel = new NotificationChannel(CHANNEL_ID, CHANNEL_ID, IMPORTANCE_DEFAULT);
        when(mConfig.getNotificationChannel(PKG, UID, CHANNEL_ID, false)).thenReturn(mChannel);
        when(mShortcutInfo.getId()).thenReturn(SHORTCUT_ID);
    }

    /* NotificationRecord that fulfills conversation requirements (message style + shortcut) */
    private NotificationRecord getNotificationRecord(boolean addBubble) {
        final Builder builder = new Builder(getContext())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_SOUND);
        Person person = new Person.Builder()
                .setName("bubblebot")
                .build();
        builder.setShortcutId(SHORTCUT_ID);
        builder.setStyle(new Notification.MessagingStyle(person)
                .setConversationTitle("Bubble Chat")
                .addMessage("Hello?",
                        SystemClock.currentThreadTimeMillis() - 300000, person)
                .addMessage("Is it me you're looking for?",
                        SystemClock.currentThreadTimeMillis(), person));
        Notification n = builder.build();
        if (addBubble) {
            n.setBubbleMetadata(mBubbleMetadata);
        }
        StatusBarNotification sbn = new StatusBarNotification(PKG, PKG, ID, TAG, UID,
                PID, n, mUser, null, System.currentTimeMillis());
        NotificationRecord r = new NotificationRecord(getContext(), sbn, mChannel);
        r.setShortcutInfo(mShortcutInfo);
        return r;
    }

    void setUpIntentBubble(boolean isValid) {
        when(mPendingIntent.getIntent()).thenReturn(mIntent);
        when(mBubbleMetadata.getIntent()).thenReturn(mPendingIntent);
        when(mBubbleMetadata.getShortcutId()).thenReturn(null);

        when(mPendingIntent.getIntent()).thenReturn(mIntent);
        ActivityInfo info = new ActivityInfo();
        info.resizeMode = isValid
                ? RESIZE_MODE_RESIZEABLE
                : RESIZE_MODE_UNRESIZEABLE;
        when(mIntent.resolveActivityInfo(any(), anyInt())).thenReturn(info);
    }

    void setUpShortcutBubble(boolean isValid) {
        when(mBubbleMetadata.getShortcutId()).thenReturn(SHORTCUT_ID);
        when(mBubbleMetadata.getIntent()).thenReturn(null);
        ShortcutInfo answer = isValid ? mShortcutInfo : null;
        when(mShortcutHelper.getValidShortcutInfo(SHORTCUT_ID, PKG, mUser)).thenReturn(answer);
    }

    void setUpBubblesEnabled(boolean feature, int app, int channel) {
        when(mConfig.bubblesEnabled(mUser)).thenReturn(feature);
        when(mConfig.getBubblePreference(anyString(), anyInt())).thenReturn(app);
        mChannel.setAllowBubbles(channel);
    }

    //
    // Tests for the record being allowed to bubble.
    //

    @Test
    public void testAppYesChannelNo() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                ALLOW_BUBBLE_OFF /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpShortcutBubble(true /* isValid */);
        NotificationRecord r = getNotificationRecord(true /* bubble */);
        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testAppYesChannelDefault() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpShortcutBubble(true /* isValid */);
        NotificationRecord r = getNotificationRecord(true /* bubble */);

        mBubbleExtractor.process(r);

        assertTrue(r.canBubble());
    }

    @Test
    public void testAppYesChannelYes() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                ALLOW_BUBBLE_ON /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpShortcutBubble(true /* isValid */);
        NotificationRecord r = getNotificationRecord(true /* bubble */);

        mBubbleExtractor.process(r);

        assertTrue(r.canBubble());
    }

    @Test
    public void testAppYesChannelYesFeatureNo() {
        setUpBubblesEnabled(false /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                ALLOW_BUBBLE_ON /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpShortcutBubble(true /* isValid */);
        NotificationRecord r = getNotificationRecord(true /* bubble */);

        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testAppNoChannelYes() throws Exception {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_NONE /* app */,
                ALLOW_BUBBLE_ON /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpShortcutBubble(true /* isValid */);
        NotificationRecord r = getNotificationRecord(true /* bubble */);

        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testAppNoChannelDefault() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_NONE /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpShortcutBubble(true /* isValid */);
        NotificationRecord r = getNotificationRecord(true /* bubble */);

        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testAppSelectedChannelDefault() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_SELECTED /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpShortcutBubble(true /* isValid */);
        NotificationRecord r = getNotificationRecord(true /* bubble */);

        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testAppSelectedChannelNo() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_SELECTED /* app */,
                ALLOW_BUBBLE_OFF /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpShortcutBubble(true /* isValid */);
        NotificationRecord r = getNotificationRecord(true /* bubble */);

        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testAppSeletedChannelYes() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_SELECTED /* app */,
                ALLOW_BUBBLE_ON /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpShortcutBubble(true /* isValid */);

        NotificationRecord r = getNotificationRecord(true /* bubble */);

        mBubbleExtractor.process(r);

        assertTrue(r.canBubble());
    }

    @Test
    public void testAppSeletedChannelYesFeatureNo() {
        setUpBubblesEnabled(false /* feature */,
                BUBBLE_PREFERENCE_SELECTED /* app */,
                ALLOW_BUBBLE_ON /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpShortcutBubble(true /* isValid */);

        NotificationRecord r = getNotificationRecord(true /* bubble */);

        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    //
    // Tests for flagging it as a bubble.
    //

    @Test
    public void testFlagBubble_false_previouslyRemoved() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpShortcutBubble(true /* isValid */);

        NotificationRecord r = getNotificationRecord(true /* bubble */);
        r.setFlagBubbleRemoved(true);

        mBubbleExtractor.process(r);

        assertTrue(r.canBubble());
        assertNotNull(r.getNotification().getBubbleMetadata());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_true_shortcutBubble() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpShortcutBubble(true /* isValid */);

        NotificationRecord r = getNotificationRecord(true /* bubble */);
        mBubbleExtractor.process(r);

        assertTrue(r.canBubble());
        assertNotNull(r.getNotification().getBubbleMetadata());
        assertTrue(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_true_intentBubble() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpIntentBubble(true /* isValid */);

        NotificationRecord r = getNotificationRecord(true /* bubble */);
        mBubbleExtractor.process(r);

        assertTrue(r.canBubble());
        assertNotNull(r.getNotification().getBubbleMetadata());
        assertTrue(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_false_noIntentInvalidShortcut() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpShortcutBubble(false /* isValid */);

        NotificationRecord r = getNotificationRecord(true /* bubble */);
        r.setShortcutInfo(null);
        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertNull(r.getNotification().getBubbleMetadata());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_false_invalidIntentNoShortcut() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpIntentBubble(false /* isValid */);

        NotificationRecord r = getNotificationRecord(true /* bubble */);
        r.setShortcutInfo(null);
        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertNull(r.getNotification().getBubbleMetadata());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_false_noIntentNoShortcut() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);

        // Shortcut here is for the notification not the bubble
        NotificationRecord r = getNotificationRecord(true /* bubble */);
        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertNull(r.getNotification().getBubbleMetadata());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_false_noMetadata() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);

        NotificationRecord r = getNotificationRecord(false /* bubble */);
        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertNull(r.getNotification().getBubbleMetadata());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_false_noShortcut() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpIntentBubble(true /* isValid */);

        NotificationRecord r = getNotificationRecord(true /* bubble */);
        r.setShortcutInfo(null);
        r.getNotification().extras.putString(Notification.EXTRA_TEMPLATE, null);

        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertNull(r.getNotification().getBubbleMetadata());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_false_notConversation() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(false);
        setUpIntentBubble(true /* isValid */);

        NotificationRecord r = getNotificationRecord(true /* bubble */);
        r.userDemotedAppFromConvoSpace(true);
        r.getNotification().extras.putString(Notification.EXTRA_TEMPLATE, null);

        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertNull(r.getNotification().getBubbleMetadata());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_false_lowRamDevice() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        setUpIntentBubble(true /* isValid */);

        NotificationRecord r = getNotificationRecord(true /* bubble */);
        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertNull(r.getNotification().getBubbleMetadata());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_false_noIntent() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        setUpIntentBubble(true /* isValid */);
        when(mPendingIntent.getIntent()).thenReturn(null);

        NotificationRecord r = getNotificationRecord(true /* bubble */);
        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertNull(r.getNotification().getBubbleMetadata());
        assertFalse(r.getNotification().isBubbleNotification());
    }

    @Test
    public void testFlagBubble_false_noActivityInfo() {
        setUpBubblesEnabled(true /* feature */,
                BUBBLE_PREFERENCE_ALL /* app */,
                DEFAULT_ALLOW_BUBBLE /* channel */);
        when(mActivityManager.isLowRamDevice()).thenReturn(true);
        setUpIntentBubble(true /* isValid */);
        when(mPendingIntent.getIntent()).thenReturn(mIntent);
        when(mIntent.resolveActivityInfo(any(), anyInt())).thenReturn(null);

        NotificationRecord r = getNotificationRecord(true /* bubble */);
        mBubbleExtractor.process(r);

        assertFalse(r.canBubble());
        assertNull(r.getNotification().getBubbleMetadata());
        assertFalse(r.getNotification().isBubbleNotification());
    }
}
