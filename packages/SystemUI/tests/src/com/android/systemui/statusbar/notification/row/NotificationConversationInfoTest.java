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
import static android.print.PrintManager.PRINT_SPOOLER_PACKAGE_NAME;
import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
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
import android.os.UserHandle;
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
import com.android.systemui.Dependency;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.bubbles.BubblesTestActivity;
import com.android.systemui.statusbar.SbnBuilder;
import com.android.systemui.statusbar.notification.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.phone.ShadeController;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javax.inject.Provider;

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
    private VisualStabilityManager mVisualStabilityManager;
    @Mock
    private BubbleController mBubbleController;
    @Mock
    private LauncherApps mLauncherApps;
    @Mock
    private ShortcutManager mShortcutManager;
    @Mock
    private NotificationGuts mNotificationGuts;
    @Mock
    private ShadeController mShadeController;
    @Mock
    private ConversationIconFactory mIconFactory;
    @Mock(answer = Answers.RETURNS_SELF)
    private PriorityOnboardingDialogController.Builder mBuilder;
    private Provider<PriorityOnboardingDialogController.Builder> mBuilderProvider = () -> mBuilder;
    @Mock
    private Notification.BubbleMetadata mBubbleMetadata;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mTestableLooper = TestableLooper.get(this);

        mDependency.injectTestDependency(Dependency.BG_LOOPER, mTestableLooper.getLooper());
        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        mDependency.injectTestDependency(BubbleController.class, mBubbleController);
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
        }).when(mNotificationGuts).closeControls(anyInt(), anyInt(), eq(true), eq(false));
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

        when(mShortcutInfo.getShortLabel()).thenReturn("Convo name");
        List<ShortcutInfo> shortcuts = Arrays.asList(mShortcutInfo);
        when(mLauncherApps.getShortcuts(any(), any())).thenReturn(shortcuts);
        when(mIconFactory.getConversationDrawable(
                any(ShortcutInfo.class), anyString(), anyInt(), anyBoolean()))
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
                new Intent(mContext, BubblesTestActivity.class), 0);
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

        when(mBuilder.build()).thenReturn(mock(PriorityOnboardingDialogController.class));
    }

    @Test
    public void testBindNotification_SetsShortcutIcon() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);
        final ImageView view = mNotificationInfo.findViewById(R.id.conversation_icon);
        assertEquals(mIconDrawable, view.getDrawable());
    }

    @Test
    public void testBindNotification_SetsTextApplicationName() {
        when(mMockPackageManager.getApplicationLabel(any())).thenReturn("App Name");
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);
        final TextView textView = mNotificationInfo.findViewById(R.id.pkg_name);
        assertTrue(textView.getText().toString().contains("App Name"));
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
    }
/**
    @Test
    public void testBindNotification_SetsTextChannelName() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mLauncherApps,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
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
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);
        final TextView textView = mNotificationInfo.findViewById(R.id.group_name);
        assertTrue(textView.getText().toString().contains(group.getName()));
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
        assertEquals(VISIBLE, textView.getVisibility());
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.group_divider).getVisibility());
    }

    @Test
    public void testBindNotification_GroupNameHiddenIfNoGroup() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);
        final TextView textView = mNotificationInfo.findViewById(R.id.group_name);
        assertEquals(VISIBLE, mNotificationInfo.findViewById(R.id.header).getVisibility());
        assertEquals(GONE, textView.getVisibility());
        assertEquals(GONE, mNotificationInfo.findViewById(R.id.group_divider).getVisibility());
    }

    @Test
    public void testBindNotification_noDelegate() {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);
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

        NotificationEntry entry = new NotificationEntryBuilder().setSbn(mSbn).build();
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                entry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);
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
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mConversationChannel, c);
                    latch.countDown();
                },
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);

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
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);
        final View settingsButton = mNotificationInfo.findViewById(R.id.info);
        assertTrue(settingsButton.getVisibility() != View.VISIBLE);
    }

    @Test
    public void testBindNotification_SettingsButtonInvisibleWhenDeviceUnprovisioned() {
        final CountDownLatch latch = new CountDownLatch(1);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                (View v, NotificationChannel c, int appUid) -> {
                    assertEquals(mNotificationChannel, c);
                    latch.countDown();
                },
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                false);
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
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);
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
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);
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
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);
        View view = mNotificationInfo.findViewById(R.id.default_behavior);
        assertThat(view.isSelected()).isTrue();
        assertThat(((TextView) view.findViewById(R.id.default_summary)).getText()).isEqualTo(
                mContext.getString(R.string.notification_channel_summary_default_with_bubbles,
                        "App Name"));
    }

    @Test
    public void testFavorite() throws Exception {
        mConversationChannel.setAllowBubbles(false);
        mConversationChannel.setImportance(IMPORTANCE_LOW);
        mConversationChannel.setImportantConversation(false);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);

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
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);

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
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);

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
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);

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
    }

    @Test
    public void testFavorite_andSave_doesNotLowerImportance() throws Exception {
        mConversationChannel.setOriginalImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportance(9);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);

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
    public void testDefault_andSave() throws Exception {
        mConversationChannel.setAllowBubbles(true);
        mConversationChannel.setOriginalImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportantConversation(true);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);

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
    }

    @Test
    public void testDefault_andSave_doesNotChangeNonImportantBubbling() throws Exception {
        mConversationChannel.setAllowBubbles(true);
        mConversationChannel.setOriginalImportance(IMPORTANCE_HIGH);
        mConversationChannel.setImportantConversation(false);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);

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
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);

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
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);

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
    }

    @Test
    public void testBindNotification_createsNewChannel() throws Exception {
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);

        verify(mMockINotificationManager, times(1)).createConversationNotificationChannelForPackage(
                anyString(), anyInt(), anyString(), any(), eq(CONVERSATION_ID));
    }

    @Test
    public void testBindNotification_doesNotCreateNewChannelIfExists() throws Exception {
        mNotificationChannel.setConversationId("", CONVERSATION_ID);
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                mBuilderProvider,
                true);

        verify(mMockINotificationManager, never()).createConversationNotificationChannelForPackage(
                anyString(), anyInt(), anyString(), any(), eq(CONVERSATION_ID));
    }

    @Test
    public void testSelectPriorityPresentsOnboarding_firstTime() {
        // GIVEN pref is false
        Prefs.putBoolean(mContext, Prefs.Key.HAS_SEEN_PRIORITY_ONBOARDING, false);

        // GIVEN the priority onboarding screen is present
        PriorityOnboardingDialogController.Builder b =
                mock(PriorityOnboardingDialogController.Builder.class, Answers.RETURNS_SELF);
        PriorityOnboardingDialogController controller =
                mock(PriorityOnboardingDialogController.class);
        when(b.build()).thenReturn(controller);

        // GIVEN the user is changing conversation settings
        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                () -> b,
                true);

        // WHEN user clicks "priority"
        mNotificationInfo.setSelectedAction(NotificationConversationInfo.ACTION_FAVORITE);

        // THEN the user is presented with the priority onboarding screen
        verify(controller, atLeastOnce()).show();
    }

    @Test
    public void testSelectPriorityDoesNotShowOnboarding_secondTime() {
        //WHEN pref is true
        Prefs.putBoolean(mContext, Prefs.Key.HAS_SEEN_PRIORITY_ONBOARDING, true);

        PriorityOnboardingDialogController.Builder b =
                mock(PriorityOnboardingDialogController.Builder.class, Answers.RETURNS_SELF);
        PriorityOnboardingDialogController controller =
                mock(PriorityOnboardingDialogController.class);
        when(b.build()).thenReturn(controller);

        mNotificationInfo.bindNotification(
                mShortcutManager,
                mMockPackageManager,
                mMockINotificationManager,
                mVisualStabilityManager,
                TEST_PACKAGE_NAME,
                mNotificationChannel,
                mEntry,
                mBubbleMetadata,
                null,
                null,
                mIconFactory,
                mContext,
                () -> b,
                true);

        // WHEN user clicks "priority"
        mNotificationInfo.setSelectedAction(NotificationConversationInfo.ACTION_FAVORITE);

        // THEN the user is presented with the priority onboarding screen
        verify(controller, never()).show();
    }
}
