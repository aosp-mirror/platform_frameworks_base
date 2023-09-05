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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import static android.app.NotificationManager.BUBBLE_PREFERENCE_ALL;
import static android.app.NotificationManager.BUBBLE_PREFERENCE_SELECTED;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.IMPORTANCE_LOW;
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_ANYONE;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS;
import static android.print.PrintManager.PRINT_SPOOLER_PACKAGE_NAME;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.notification.StatusBarNotification;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.logging.MetricsLogger;
import com.android.settingslib.notification.ConversationIconFactory;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.people.widget.PeopleSpaceWidgetManager;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.SbnBuilder;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.wmshell.BubblesManager;
import com.android.systemui.wmshell.BubblesTestActivity;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationConversationInfoTest extends SysuiTestCase {
    private static final String TEST_PACKAGE_NAME = "test_package";
    private static final String TEST_SYSTEM_PACKAGE_NAME = PRINT_SPOOLER_PACKAGE_NAME;
    private static final int TEST_UID = 1;
    private static final String TEST_CHANNEL = "test_channel";
    private static final String TEST_CHANNEL_NAME = "TEST CHANNEL NAME";
    private static final String CONVERSATION_ID = "convo";

    private TestableLooper mTestableLooper;
    private NotificationConversationInfo mNotificationInfo;
    private NotificationChannel mNotificationChannel;
    private NotificationChannel mConversationChannel;
    private StatusBarNotification mSbn;
    private NotificationEntry mEntry;
    private StatusBarNotification mBubbleSbn;
    private NotificationEntry mBubbleEntry;
    @Mock
    private ShortcutInfo mShortcutInfo;
    @Mock
    private Drawable mIconDrawable;

    @Rule
    public MockitoRule mockito = MockitoJUnit.rule();
    @Mock
    private MetricsLogger mMetricsLogger;
    @Mock
    private INotificationManager mMockINotificationManager;
    @Mock
    private PackageManager mMockPackageManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private OnUserInteractionCallback mOnUserInteractionCallback;
    @Mock
    private BubblesManager mBubblesManager;
    @Mock
    private LauncherApps mLauncherApps;
    @Mock
    private PeopleSpaceWidgetManager mPeopleSpaceWidgetManager;
    @Mock
    private ShortcutManager mShortcutManager;
    @Mock
    private NotificationGuts mNotificationGuts;
    @Mock
    private ShadeController mShadeController;
    @Mock
    private ConversationIconFactory mIconFactory;
    @Mock
    private Notification.BubbleMetadata mBubbleMetadata;
    private Handler mTestHandler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);

        mTestHandler = new Handler(mTestableLooper.getLooper());
        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        mDependency.injectTestDependency(ShadeController.class, mShadeController);
        // Inflate the layout
        final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
        mNotificationInfo = (NotificationConversationInfo) layoutInflater.inflate(
                R.layout.notification_conversation_info,
                null);
        mNotificationInfo.setGutsParent(mNotificationGuts);
        doAnswer((Answer<Object>) invocation -> {
            mNotificationInfo.handleCloseControls(true, false);
            return null;
        }).when(mNotificationGuts).closeControls(any(View.class), eq(true));
        // Our view is never attached to a window so the View#post methods in NotificationInfo never
        // get called. Setting this will skip the post and do the action immediately.
        mNotificationInfo.mSkipPost = true;

        // PackageManager must return a packageInfo and applicationInfo.
        final PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = TEST_PACKAGE_NAME;
        when(mMockPackageManager.getPackageInfo(eq(TEST_PACKAGE_NAME), anyInt()))
                .thenReturn(packageInfo);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = TEST_UID;  // non-zero
        when(mMockPackageManager.getApplicationInfo(eq(TEST_PACKAGE_NAME), anyInt())).thenReturn(
                applicationInfo);
        final PackageInfo systemPackageInfo = new PackageInfo();
        systemPackageInfo.packageName = TEST_SYSTEM_PACKAGE_NAME;
        when(mMockPackageManager.getPackageInfo(eq(TEST_SYSTEM_PACKAGE_NAME), anyInt()))
                .thenReturn(systemPackageInfo);
        when(mMockPackageManager.getPackageInfo(eq("android"), anyInt()))
                .thenReturn(packageInfo);

        when(mShortcutInfo.getLabel()).thenReturn("Convo name");
        List<ShortcutInfo> shortcuts = Arrays.asList(mShortcutInfo);
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(shortcuts);
        when(mIconFactory.getBaseIconDrawable(any(ShortcutInfo.class)))
                .thenReturn(mIconDrawable);

        mNotificationChannel = new NotificationChannel(
                TEST_CHANNEL, TEST_CHANNEL_NAME, IMPORTANCE_LOW);

        Notification notification = new Notification.Builder(mContext, mNotificationChannel.getId())
                .setShortcutId(CONVERSATION_ID)
                .setStyle(new Notification.MessagingStyle(new Person.Builder().setName("m").build())
                        .addMessage(new Notification.MessagingStyle.Message(
                                "hello!", 1000, new Person.Builder().setName("other").build())))
                .build();
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, TEST_PACKAGE_NAME, 0, null, TEST_UID, 0,
                notification, UserHandle.CURRENT, null, 0);
        mEntry = new NotificationEntryBuilder().setSbn(mSbn).setShortcutInfo(mShortcutInfo).build();

        PendingIntent bubbleIntent = PendingIntent.getActivity(mContext, 0,
                new Intent(mContext, BubblesTestActivity.class),
                PendingIntent.FLAG_MUTABLE);
        mBubbleSbn = new SbnBuilder(mSbn).setBubbleMetadata(
                new Notification.BubbleMetadata.Builder(bubbleIntent,
                        Icon.createWithResource(mContext, R.drawable.android)).build())
                .build();
        mBubbleEntry = new NotificationEntryBuilder()
                .setSbn(mBubbleSbn)
                .setShortcutInfo(mShortcutInfo)
                .build();

        mConversationChannel = new NotificationChannel(
                TEST_CHANNEL + " : " + CONVERSATION_ID, TEST_CHANNEL_NAME, IMPORTANCE_LOW);
        mConversationChannel.setConversationId(TEST_CHANNEL, CONVERSATION_ID);
        when(mMockINotificationManager.getConversationNotificationChannel(anyString(), anyInt(),
                anyString(), eq(TEST_CHANNEL), eq(false), eq(CONVERSATION_ID)))
                .thenReturn(mConversationChannel);

        when(mMockINotificationManager.getConsolidatedNotificationPolicy())
                .thenReturn(mock(NotificationManager.Policy.class));

        when(mPeopleSpaceWidgetManager.requestPinAppWidget(any(), any())).thenReturn(true);
    }

    @Test
    public void testBindNotification_SetsShortcutIcon() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        final ImageView view = mNotificationInfo.findViewById(R.id.conversation_icon);
        assertEquals(mIconDrawable, view.getDrawable());
    }

    @Test
    public void testBindNotification_SetsTextApplicationName() {
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        final TextView textView = mNotificationInfo.findViewById(R.id.pkg_name);
        assertTrue(textView.getText().toString().contains("App Name"));
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
    }
/**
    @Test
    public void testBindNotification_SetsTextChannelName() {
        mNotificationInfo.bindNotification(
                -1,
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
    mBubbleMetadata,
    null,
                null,
                null,
                true);
        final TextView textView = mNotificationInfo.findViewById(R.id.parent_channel_name);
        assertTrue(textView.getText().toString().contains(mNotificationChannel.getName()));
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
    }
*/
    @Test
    public void testBindNotification_SetsTextGroupName() throws Exception {
        NotificationChannelGroup group = new NotificationChannelGroup("id", "name");
        when(mMockINotificationManager.getNotificationChannelGroupForPackage(
               anyString(), anyString(), anyInt())).thenReturn(group);
        mNotificationChannel.setGroup(group.getId());
        mConversationChannel.setGroup(group.getId());

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        final TextView textView = mNotificationInfo.findViewById(R.id.group_name);
        assertTrue(textView.getText().toString().contains(group.getName()));
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
        assertEquals(VISIBLE, textView.getVisibility());
    }

    @Test
    public void testBindNotification_GroupNameHiddenIfNoGroup() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        final TextView textView = mNotificationInfo.findViewById(R.id.group_name);
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
        assertEquals(GONE, textView.getVisibility());
    }

    @Test
    public void testBindNotification_noDelegate() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        final TextView nameView = mNotificationInfo.findViewById(R.id.delegate_name);
        assertEquals(GONE, nameView.getVisibility());
    }

    @Test
    public void testBindNotification_delegate() throws Exception {
        mSbn = new StatusBarNotification(TEST_PACKAGE_NAME, "other", 0, null, TEST_UID, 0,
                mSbn.getNotification(), UserHandle.CURRENT, null, 0);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = 7;  // non-zero
        when(mMockPackageManager.getApplicationInfo(eq("other"), anyInt())).thenReturn(
                applicationInfo);
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("Other");

        NotificationEntry entry = new NotificationEntryBuilder()
                .setSbn(mSbn)
                .setShortcutInfo(mShortcutInfo)
                .build();
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                entry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        final TextView nameView = mNotificationInfo.findViewById(R.id.delegate_name);
        assertEquals(VISIBLE, nameView.getVisibility());
        assertTrue(nameView.getText().toString().contains("Proxied"));
    }

    @Test
    public void testBindNotification_SetsOnClickListenerForSettings() {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mConversationChannel, c);
                    latch.countDown();
                },
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        settingsButton.performClick();
        // Verify that listener was triggered.
        assertEquals(0, latch.getCount());
    }

    @Test
    public void testBindNotification_SettingsButtonInvisibleWhenNoClickListener() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        assertTrue(settingsButton.getVisibility() != View.VISIBLE);
    }

    @Test
    public void testBindNotification_SettingsButtonInvisibleWhenDeviceUnprovisioned() {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mNotificationChannel, c);
                    latch.countDown();
                },
                mIconFactory,
                mContext,
                false,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        assertTrue(settingsButton.getVisibility() != View.VISIBLE);
    }

    @Test
    public void testBindNotification_silentSelected_isFave_isSilent() {
        mConversationChannel.setImportance(IMPORTANCE_LOW);
        mConversationChannel.setImportantConversation(true);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        View view = mNotificationInfo.findViewById(R.id.silence);
        assertThat(view.isSelected()).isTrue();
    }

    @Test
    public void testBindNotification_defaultSelected_notFave_notSilent() throws Exception {
        when(mMockINotificationManager.getBubblePreferenceForPackage(anyString(), anyInt()))
                .thenReturn(BUBBLE_PREFERENCE_SELECTED);
        mConversationChannel.setImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportantConversation(false);
        mConversationChannel.setAllowBubbles(true);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        View view = mNotificationInfo.findViewById(R.id.default_behavior);
        assertThat(view.isSelected()).isTrue();
        assertThat(((TextView) view.findViewById(R.id.default_summary)).getText()).isEqualTo(
                mContext.getString(R.string.notification_channel_summary_default));
    }

    @Test
    public void testBindNotification_default_allCanBubble() throws Exception {
        when(mMockINotificationManager.getBubblePreferenceForPackage(anyString(), anyInt()))
                .thenReturn(BUBBLE_PREFERENCE_ALL);
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mConversationChannel.setImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportantConversation(false);
        mConversationChannel.setAllowBubbles(true);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        View view = mNotificationInfo.findViewById(R.id.default_behavior);
        assertThat(view.isSelected()).isTrue();
        assertThat(((TextView) view.findViewById(R.id.default_summary)).getText()).isEqualTo(
                mContext.getString(R.string.notification_channel_summary_default_with_bubbles,
                        "App Name"));
        assertThat(((TextView) mNotificationInfo.findViewById(R.id.priority_summary)).getText())
                .isEqualTo(mContext.getString(
                        R.string.notification_channel_summary_priority_bubble));
    }

    @Test
    public void testBindNotification_priorityDnd() throws Exception {
        NotificationManager.Policy policy = new NotificationManager.Policy(
                PRIORITY_CATEGORY_CONVERSATIONS, 0, 0, 0, CONVERSATION_SENDERS_ANYONE);
        when(mMockINotificationManager.getConsolidatedNotificationPolicy())
                .thenReturn(policy);
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mConversationChannel.setImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportantConversation(false);
        mConversationChannel.setAllowBubbles(false);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        assertThat(((TextView) mNotificationInfo.findViewById(R.id.priority_summary)).getText())
                .isEqualTo(mContext.getString(
                        R.string.notification_channel_summary_priority_dnd));
    }

    @Test
    public void testBindNotification_priorityBaseline() throws Exception {
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mConversationChannel.setImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportantConversation(false);
        mConversationChannel.setAllowBubbles(false);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                null,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        assertThat(((TextView) mNotificationInfo.findViewById(R.id.priority_summary)).getText())
                .isEqualTo(mContext.getString(
                        R.string.notification_channel_summary_priority_baseline));
    }

    @Test
    public void testBindNotification_priorityDndAndBubble() throws Exception {
        NotificationManager.Policy policy = new NotificationManager.Policy(
                PRIORITY_CATEGORY_CONVERSATIONS, 0, 0, 0, CONVERSATION_SENDERS_ANYONE);
        when(mMockINotificationManager.getConsolidatedNotificationPolicy())
                .thenReturn(policy);

        when(mMockINotificationManager.getBubblePreferenceForPackage(anyString(), anyInt()))
                .thenReturn(BUBBLE_PREFERENCE_ALL);
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mConversationChannel.setImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportantConversation(false);
        mConversationChannel.setAllowBubbles(true);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);
        assertThat(((TextView) mNotificationInfo.findViewById(R.id.priority_summary)).getText())
                .isEqualTo(mContext.getString(
                        R.string.notification_channel_summary_priority_all));
    }

    @Test
    public void testFavorite() throws Exception {
        mConversationChannel.setAllowBubbles(false);
        mConversationChannel.setImportance(IMPORTANCE_LOW);
        mConversationChannel.setImportantConversation(false);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        View fave = mNotificationInfo.findViewById(R.id.priority);
        fave.performClick();
        mTestableLooper.processAllMessages();

        // silence subtext visible, others not
        assertThat(mNotificationInfo.findViewById(R.id.priority_summary).getVisibility())
                .isEqualTo(VISIBLE);
        assertThat(mNotificationInfo.findViewById(R.id.default_summary).getVisibility())
                .isEqualTo(GONE);
        assertThat(mNotificationInfo.findViewById(R.id.silence_summary).getVisibility())
                .isEqualTo(GONE);

        // no changes until hit done
        assertFalse(mNotificationInfo.shouldBeSavedOnClose());
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), anyInt(), any());
        assertFalse(mConversationChannel.isImportantConversation());
        assertFalse(mConversationChannel.canBubble());
        assertEquals(IMPORTANCE_LOW, mConversationChannel.getImportance());
    }

    @Test
    public void testDefault() throws Exception {
        mConversationChannel.setAllowBubbles(false);
        mConversationChannel.setImportance(IMPORTANCE_LOW);
        mConversationChannel.setImportantConversation(false);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        mNotificationInfo.findViewById(R.id.default_behavior).performClick();
        mTestableLooper.processAllMessages();

        // silence subtext visible, others not
        assertThat(mNotificationInfo.findViewById(R.id.priority_summary).getVisibility())
                .isEqualTo(GONE);
        assertThat(mNotificationInfo.findViewById(R.id.default_summary).getVisibility())
                .isEqualTo(VISIBLE);
        assertThat(mNotificationInfo.findViewById(R.id.silence_summary).getVisibility())
                .isEqualTo(GONE);

        // no changes until hit done
        assertFalse(mNotificationInfo.shouldBeSavedOnClose());
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), anyInt(), any());
        assertFalse(mConversationChannel.isImportantConversation());
        assertFalse(mConversationChannel.canBubble());
        assertEquals(IMPORTANCE_LOW, mConversationChannel.getImportance());
    }

    @Test
    public void testSilence() throws Exception {
        mConversationChannel.setImportance(IMPORTANCE_DEFAULT);
        mConversationChannel.setImportantConversation(false);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        View silence = mNotificationInfo.findViewById(R.id.silence);

        silence.performClick();
        mTestableLooper.processAllMessages();

        // silence subtext visible, others not
        assertThat(mNotificationInfo.findViewById(R.id.priority_summary).getVisibility())
                .isEqualTo(GONE);
        assertThat(mNotificationInfo.findViewById(R.id.default_summary).getVisibility())
                .isEqualTo(GONE);
        assertThat(mNotificationInfo.findViewById(R.id.silence_summary).getVisibility())
                .isEqualTo(VISIBLE);

        // no changes until save
        assertFalse(mNotificationInfo.shouldBeSavedOnClose());
        verify(mMockINotificationManager, never()).updateNotificationChannelForPackage(
                anyString(), anyInt(), any());
        assertEquals(IMPORTANCE_DEFAULT, mConversationChannel.getImportance());
    }

    @Test
    public void testFavorite_andSave() throws Exception {
        mConversationChannel.setAllowBubbles(false);
        mConversationChannel.setImportance(IMPORTANCE_LOW);
        mConversationChannel.setImportantConversation(false);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        View fave = mNotificationInfo.findViewById(R.id.priority);
        fave.performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mTestableLooper.processAllMessages();

        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertTrue(captor.getValue().isImportantConversation());
        assertTrue(captor.getValue().canBubble());
        assertEquals(IMPORTANCE_DEFAULT, captor.getValue().getImportance());
        assertFalse(mNotificationInfo.shouldBeSavedOnClose());
    }

    @Test
    public void testFavorite_andSave_doesNotLowerImportance() throws Exception {
        mConversationChannel.setOriginalImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportance(9);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        View fave = mNotificationInfo.findViewById(R.id.priority);
        fave.performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mTestableLooper.processAllMessages();

        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertEquals(IMPORTANCE_HIGH, captor.getValue().getImportance());
    }

    @Test
    public void testFavorite_thenDefaultThenFavorite_andSave_nothingChanged() throws Exception {
        mConversationChannel.setOriginalImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportantConversation(true);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        View fave = mNotificationInfo.findViewById(R.id.priority);
        fave.performClick();
        mNotificationInfo.findViewById(R.id.default_behavior).performClick();
        fave.performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mTestableLooper.processAllMessages();

        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertEquals(IMPORTANCE_HIGH, captor.getValue().getImportance());
        assertTrue(captor.getValue().isImportantConversation());
    }

    @Test
    public void testDefaultSelectedWhenChannelIsDefault() throws Exception {
        // GIVEN channel importance indicates "Default" priority
        mConversationChannel.setImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportantConversation(false);

        // WHEN we indicate no selected action
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        // THEN the selected action is -1, so the selected option is "Default" priority
        assertEquals(mNotificationInfo.getSelectedAction(), -1);
        assertTrue(mNotificationInfo.findViewById(R.id.default_behavior).isSelected());
    }

    @Test
    public void testFavoriteSelectedWhenChannelIsDefault() throws Exception {
        // GIVEN channel importance indicates "Default" priority
        mConversationChannel.setImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportantConversation(false);

        // WHEN we indicate the selected action should be "Favorite"
        mNotificationInfo.setSelectedAction(NotificationConversationInfo.ACTION_FAVORITE);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        // THEN the selected action is "Favorite", so the selected option is "priority" priority
        assertEquals(mNotificationInfo.getSelectedAction(),
                NotificationConversationInfo.ACTION_FAVORITE);
        assertTrue(mNotificationInfo.findViewById(R.id.priority).isSelected());
    }

    @Test
    public void testDefault_andSave() throws Exception {
        mConversationChannel.setAllowBubbles(true);
        mConversationChannel.setOriginalImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportantConversation(true);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        mNotificationInfo.findViewById(R.id.default_behavior).performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mTestableLooper.processAllMessages();

        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertFalse(captor.getValue().isImportantConversation());
        assertFalse(captor.getValue().canBubble());
        assertEquals(IMPORTANCE_HIGH, captor.getValue().getImportance());
        assertFalse(mNotificationInfo.shouldBeSavedOnClose());
    }

    @Test
    public void testDefault_andSave_doesNotChangeNonImportantBubbling() throws Exception {
        mConversationChannel.setAllowBubbles(true);
        mConversationChannel.setOriginalImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportantConversation(false);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        mNotificationInfo.findViewById(R.id.default_behavior).performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mTestableLooper.processAllMessages();

        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertFalse(captor.getValue().isImportantConversation());
        assertTrue(captor.getValue().canBubble());
        assertEquals(IMPORTANCE_HIGH, captor.getValue().getImportance());
    }

    @Test
    public void testDefault_andSave_doesNotDemoteImportance() throws Exception {
        mConversationChannel.setImportance(9);
        mConversationChannel.setOriginalImportance(IMPORTANCE_HIGH);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        mNotificationInfo.findViewById(R.id.default_behavior).performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mTestableLooper.processAllMessages();

        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertEquals(IMPORTANCE_HIGH, captor.getValue().getImportance());
    }

    @Test
    public void testSilence_andSave() throws Exception {
        mConversationChannel.setImportance(IMPORTANCE_DEFAULT);
        mConversationChannel.setImportantConversation(true);
        mConversationChannel.setAllowBubbles(true);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        View silence = mNotificationInfo.findViewById(R.id.silence);
        silence.performClick();
        mNotificationInfo.findViewById(R.id.done).performClick();
        mTestableLooper.processAllMessages();

        ArgumentCaptor<NotificationChannel> captor =
                ArgumentCaptor.forClass(NotificationChannel.class);
        verify(mMockINotificationManager, times(1)).updateNotificationChannelForPackage(
                anyString(), anyInt(), captor.capture());
        assertFalse(captor.getValue().isImportantConversation());
        assertFalse(captor.getValue().canBubble());
        assertEquals(IMPORTANCE_LOW, captor.getValue().getImportance());
        assertFalse(mNotificationInfo.shouldBeSavedOnClose());
    }

    @Test
    public void testSilence_closeGutsThenTryToSave() {
        mConversationChannel.setImportance(IMPORTANCE_DEFAULT);
        mConversationChannel.setImportantConversation(true);
        mConversationChannel.setAllowBubbles(true);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        mNotificationInfo.findViewById(R.id.silence).performClick();
        mNotificationInfo.handleCloseControls(false, false);
        mNotificationInfo.findViewById(R.id.done).performClick();

        mTestableLooper.processAllMessages();

        assertEquals(IMPORTANCE_DEFAULT, mConversationChannel.getImportance());
        assertFalse(mNotificationInfo.shouldBeSavedOnClose());
    }

    @Test
    public void testBindNotification_createsNewChannel() throws Exception {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        verify(mMockINotificationManager, times(1)).createConversationNotificationChannelForPackage(
                anyString(), anyInt(), any(), eq(CONVERSATION_ID));
    }

    @Test
    public void testBindNotification_doesNotCreateNewChannelIfExists() throws Exception {
        mNotificationChannel.setConversationId("", CONVERSATION_ID);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        verify(mMockINotificationManager, never()).createConversationNotificationChannelForPackage(
                anyString(), anyInt(), any(), eq(CONVERSATION_ID));
    }

    @Test
    public void testSelectPriorityRequestsPinPeopleTile() {
        when(mUserManager.isSameProfileGroup(anyInt(), anyInt())).thenReturn(true);
        //WHEN channel is default importance
        mNotificationChannel.setImportantConversation(false);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        // WHEN user clicks "priority"
        mNotificationInfo.setSelectedAction(NotificationConversationInfo.ACTION_FAVORITE);

        // and then done
        mNotificationInfo.findViewById(R.id.done).performClick();

        // THEN the user is presented with the People Tile pinning request
        verify(mPeopleSpaceWidgetManager, times(1)).requestPinAppWidget(any(), any());
    }

    @Test
    public void testSelectPriorityRequestsPinPeopleTile_noMultiuser() {
        when(mUserManager.isSameProfileGroup(anyInt(), anyInt())).thenReturn(false);
        //WHEN channel is default importance
        mNotificationChannel.setImportantConversation(false);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        // WHEN user clicks "priority"
        mNotificationInfo.setSelectedAction(NotificationConversationInfo.ACTION_FAVORITE);

        // and then done
        mNotificationInfo.findViewById(R.id.done).performClick();

        // No widget prompt; on a secondary user
        verify(mPeopleSpaceWidgetManager, never()).requestPinAppWidget(any(), any());
    }

    @Test
    public void testSelectDefaultDoesNotRequestPinPeopleTile() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        // WHEN user clicks "default"
        mNotificationInfo.setSelectedAction(NotificationConversationInfo.ACTION_DEFAULT);

        // and then done
        mNotificationInfo.findViewById(R.id.done).performClick();

        // THEN the user is not presented with the People Tile pinning request
        verify(mPeopleSpaceWidgetManager, never()).requestPinAppWidget(eq(mShortcutInfo), any());
    }

    @Test
    public void testSelectPriority_AlreadyPriority_DoesNotRequestPinPeopleTile() {
        mConversationChannel.setOriginalImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportantConversation(true);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mMockINotificationManager,
                mOnUserInteractionCallback,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                mIconFactory,
                mContext,
                true,
                mTestHandler,
                mTestHandler, null, Optional.of(mBubblesManager),
                mShadeController);

        // WHEN user clicks "priority"
        mNotificationInfo.setSelectedAction(NotificationConversationInfo.ACTION_FAVORITE);

        // and then done
        mNotificationInfo.findViewById(R.id.done).performClick();

        // THEN the user is not presented with the People Tile pinning request
        verify(mPeopleSpaceWidgetManager, never()).requestPinAppWidget(eq(mShortcutInfo), any());
    }
}
