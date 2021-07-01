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

package com.android.systemui.people.widget;

import static android.app.Notification.CATEGORY_MISSED_CALL;
import static android.app.Notification.EXTRA_PEOPLE_LIST;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS;
import static android.app.NotificationManager.INTERRUPTION_FILTER_ALL;
import static android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY;
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_IMPORTANT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_ON;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR;
import static android.app.people.ConversationStatus.ACTIVITY_ANNIVERSARY;
import static android.app.people.ConversationStatus.ACTIVITY_BIRTHDAY;
import static android.app.people.ConversationStatus.ACTIVITY_GAME;
import static android.app.people.PeopleSpaceTile.BLOCK_CONVERSATIONS;
import static android.app.people.PeopleSpaceTile.SHOW_CONTACTS;
import static android.app.people.PeopleSpaceTile.SHOW_CONVERSATIONS;
import static android.app.people.PeopleSpaceTile.SHOW_IMPORTANT_CONVERSATIONS;
import static android.app.people.PeopleSpaceTile.SHOW_STARRED_CONTACTS;
import static android.content.Intent.ACTION_BOOT_COMPLETED;
import static android.content.Intent.ACTION_PACKAGES_SUSPENDED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;
import static android.content.PermissionChecker.PERMISSION_GRANTED;
import static android.content.PermissionChecker.PERMISSION_HARD_DENIED;
import static android.service.notification.ZenPolicy.CONVERSATION_SENDERS_ANYONE;

import static com.android.systemui.people.PeopleSpaceUtils.EMPTY_STRING;
import static com.android.systemui.people.PeopleSpaceUtils.INVALID_USER_ID;
import static com.android.systemui.people.PeopleSpaceUtils.PACKAGE_NAME;
import static com.android.systemui.people.PeopleSpaceUtils.USER_ID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static java.util.Objects.requireNonNull;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.app.backup.BackupManager;
import android.app.people.ConversationChannel;
import android.app.people.ConversationStatus;
import android.app.people.IPeopleManager;
import android.app.people.PeopleManager;
import android.app.people.PeopleSpaceTile;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.notification.ConversationChannelWrapper;
import android.service.notification.StatusBarNotification;
import android.service.notification.ZenModeConfig;
import android.testing.AndroidTestingRunner;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;
import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.people.PeopleBackupFollowUpJob;
import com.android.systemui.people.PeopleSpaceUtils;
import com.android.systemui.people.SharedPreferencesHelper;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;
import com.android.systemui.statusbar.SbnBuilder;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NoManSimulator;
import com.android.systemui.statusbar.notification.collection.NoManSimulator.NotifEvent;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.wm.shell.bubbles.Bubbles;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class PeopleSpaceWidgetManagerTest extends SysuiTestCase {
    private static final long MIN_LINGER_DURATION = 5;

    private static final String TEST_PACKAGE_A = "com.android.systemui.tests";
    private static final String TEST_PACKAGE_B = "com.test.package_b";
    private static final String TEST_CHANNEL_ID = "channel_id";
    private static final String TEST_CHANNEL_NAME = "channel_name";
    private static final String TEST_PARENT_CHANNEL_ID = "parent_channel_id";
    private static final String TEST_CONVERSATION_ID = "conversation_id";
    private static final int WIDGET_ID_WITH_SHORTCUT = 1;
    private static final int SECOND_WIDGET_ID_WITH_SHORTCUT = 3;
    private static final int WIDGET_ID_WITHOUT_SHORTCUT = 2;
    private static final int WIDGET_ID_WITH_KEY_IN_OPTIONS = 4;
    private static final int WIDGET_ID_WITH_SAME_URI = 5;
    private static final int WIDGET_ID_WITH_DIFFERENT_URI = 6;
    private static final int WIDGET_ID_8 = 8;
    private static final int WIDGET_ID_9 = 9;
    private static final int WIDGET_ID_11 = 11;
    private static final int WIDGET_ID_14 = 14;
    private static final int WIDGET_ID_15 = 15;
    private static final String SHORTCUT_ID = "101";
    private static final String OTHER_SHORTCUT_ID = "102";
    private static final String NOTIFICATION_KEY = "0|com.android.systemui.tests|0|null|0";
    private static final String NOTIFICATION_CONTENT_1 = "message text 1";
    private static final Uri URI = Uri.parse("fake_uri");
    private static final Icon ICON = Icon.createWithResource("package", R.drawable.ic_android);
    private static final PeopleTileKey KEY = new PeopleTileKey(SHORTCUT_ID, 0, TEST_PACKAGE_A);
    private static final Person PERSON = new Person.Builder()
            .setName("name")
            .setKey("abc")
            .setUri(URI.toString())
            .setBot(false)
            .build();
    private static final PeopleSpaceTile PERSON_TILE =
            new PeopleSpaceTile
                    .Builder(SHORTCUT_ID, "username", ICON, new Intent())
                    .setPackageName(TEST_PACKAGE_A)
                    .setUserHandle(new UserHandle(0))
                    .setNotificationKey(NOTIFICATION_KEY + "1")
                    .setNotificationContent(NOTIFICATION_CONTENT_1)
                    .setNotificationDataUri(URI)
                    .setContactUri(URI)
                    .build();
    private static final PeopleSpaceTile PERSON_TILE_WITH_SAME_URI =
            new PeopleSpaceTile
                    // Different shortcut ID
                    .Builder(OTHER_SHORTCUT_ID, "username", ICON, new Intent())
                    // Different package name
                    .setPackageName(TEST_PACKAGE_B)
                    .setUserHandle(new UserHandle(0))
                    // Same contact uri.
                    .setContactUri(URI)
                    .build();
    private static final int ALL_SUPPRESSED_VISUAL_EFFECTS = SUPPRESSED_EFFECT_SCREEN_OFF
            | SUPPRESSED_EFFECT_SCREEN_ON
            | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT
            | SUPPRESSED_EFFECT_AMBIENT
            | SUPPRESSED_EFFECT_STATUS_BAR
            | SUPPRESSED_EFFECT_BADGE
            | SUPPRESSED_EFFECT_LIGHTS
            | SUPPRESSED_EFFECT_PEEK
            | SUPPRESSED_EFFECT_NOTIFICATION_LIST;
    private static final long SBN_POST_TIME = 567L;

    private static final Map<String, String> WIDGETS_MAPPING = Map.of(
            String.valueOf(WIDGET_ID_8), String.valueOf(WIDGET_ID_WITH_SHORTCUT),
            String.valueOf(WIDGET_ID_9), String.valueOf(WIDGET_ID_WITHOUT_SHORTCUT),
            String.valueOf(WIDGET_ID_11), String.valueOf(WIDGET_ID_WITH_KEY_IN_OPTIONS),
            String.valueOf(WIDGET_ID_14), String.valueOf(WIDGET_ID_WITH_SAME_URI),
            String.valueOf(WIDGET_ID_15), String.valueOf(WIDGET_ID_WITH_DIFFERENT_URI)
    );

    private ShortcutInfo mShortcutInfo;
    private NotificationEntry mNotificationEntry;

    private PeopleSpaceWidgetManager mManager;

    @Mock
    private Context mMockContext;

    @Mock
    private NotificationListener mListenerService;

    @Mock
    private AppWidgetManager mAppWidgetManager;
    @Mock
    private IPeopleManager mIPeopleManager;
    @Mock
    private PeopleManager mPeopleManager;
    @Mock
    private LauncherApps mLauncherApps;
    @Mock
    private NotificationEntryManager mNotificationEntryManager;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private INotificationManager mINotificationManager;
    @Mock
    private UserManager mUserManager;
    @Mock
    private NotificationManager mNotificationManager;
    @Mock
    private NotificationManager.Policy mNotificationPolicy;
    @Mock
    private Bubbles mBubbles;
    @Mock
    private BackupManager mBackupManager;

    @Captor
    private ArgumentCaptor<NotificationHandler> mListenerCaptor;
    @Captor
    private ArgumentCaptor<Bundle> mBundleArgumentCaptor;

    private final NoManSimulator mNoMan = new NoManSimulator();
    private final FakeSystemClock mClock = new FakeSystemClock();

    private final FakeExecutor mFakeExecutor = new FakeExecutor(mClock);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mLauncherApps = mock(LauncherApps.class);
        mDependency.injectTestDependency(NotificationEntryManager.class, mNotificationEntryManager);
        mManager = new PeopleSpaceWidgetManager(mContext, mAppWidgetManager, mIPeopleManager,
                mPeopleManager, mLauncherApps, mNotificationEntryManager, mPackageManager,
                Optional.of(mBubbles), mUserManager, mBackupManager, mINotificationManager,
                mNotificationManager, mFakeExecutor);
        mManager.attach(mListenerService);

        verify(mListenerService).addNotificationHandler(mListenerCaptor.capture());
        NotificationHandler serviceListener = requireNonNull(mListenerCaptor.getValue());
        mNoMan.addListener(serviceListener);

        clearStorage();
        addTileForWidget(PERSON_TILE, WIDGET_ID_WITH_SHORTCUT);
        addTileForWidget(PERSON_TILE_WITH_SAME_URI, WIDGET_ID_WITH_SAME_URI);
        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITHOUT_SHORTCUT)))
                .thenReturn(new Bundle());

        when(mUserManager.isQuietModeEnabled(any())).thenReturn(false);
        when(mPackageManager.isPackageSuspended(any())).thenReturn(false);
        setFinalField("suppressedVisualEffects", ALL_SUPPRESSED_VISUAL_EFFECTS);
        when(mNotificationPolicy.allowConversationsFrom()).thenReturn(CONVERSATION_SENDERS_ANYONE);
        when(mNotificationPolicy.allowConversations()).thenReturn(false);
        when(mNotificationPolicy.allowMessagesFrom()).thenReturn(ZenModeConfig.SOURCE_ANYONE);
        when(mNotificationPolicy.allowMessages()).thenReturn(false);
        when(mNotificationManager.getNotificationPolicy()).thenReturn(mNotificationPolicy);
        when(mNotificationManager.getCurrentInterruptionFilter()).thenReturn(
                INTERRUPTION_FILTER_ALL);
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        when(mBubbles.isBubbleNotificationSuppressedFromShade(any(), any())).thenReturn(false);

        when(mMockContext.getPackageName()).thenReturn(TEST_PACKAGE_A);
        when(mMockContext.getUserId()).thenReturn(0);
        mShortcutInfo = new ShortcutInfo.Builder(mMockContext,
                SHORTCUT_ID).setLongLabel("name").build();
        mNotificationEntry = new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false))
                .setId(1)
                .setShortcutInfo(mShortcutInfo)
                .build();
    }

    @Test
    public void testGetRecentTilesReturnsSortedListWithOnlyRecentConversations() throws Exception {
        // Ensure the less-recent Important conversation is before more recent conversations.
        ConversationChannelWrapper newerNonImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID, false, 3);
        ConversationChannelWrapper newerImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID + 1, true, 3);
        ConversationChannelWrapper olderImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID + 2,
                true, 1);
        when(mINotificationManager.getConversations(anyBoolean())).thenReturn(
                new ParceledListSlice(Arrays.asList(
                        newerNonImportantConversation, newerImportantConversation,
                        olderImportantConversation)));

        // Ensure the non-Important conversation is sorted between these recent conversations.
        ConversationChannel recentConversationBeforeNonImportantConversation =
                getConversationChannel(
                        SHORTCUT_ID + 3, 4);
        ConversationChannel recentConversationAfterNonImportantConversation =
                getConversationChannel(SHORTCUT_ID + 4,
                        2);
        when(mIPeopleManager.getRecentConversations()).thenReturn(
                new ParceledListSlice(Arrays.asList(recentConversationAfterNonImportantConversation,
                        recentConversationBeforeNonImportantConversation)));

        List<String> orderedShortcutIds = mManager.getRecentTiles()
                .stream().map(tile -> tile.getId()).collect(Collectors.toList());

        // Check for sorted recent conversations.
        assertThat(orderedShortcutIds).containsExactly(
                recentConversationBeforeNonImportantConversation.getShortcutInfo().getId(),
                newerNonImportantConversation.getShortcutInfo().getId(),
                recentConversationAfterNonImportantConversation.getShortcutInfo().getId())
                .inOrder();
    }

    @Test
    public void testGetPriorityTilesReturnsSortedListWithOnlyImportantConversations()
            throws Exception {
        // Ensure the less-recent Important conversation is before more recent conversations.
        ConversationChannelWrapper newerNonImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID, false, 3);
        ConversationChannelWrapper newerImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID + 1, true, 3);
        ConversationChannelWrapper olderImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID + 2,
                true, 1);
        when(mINotificationManager.getConversations(anyBoolean())).thenReturn(
                new ParceledListSlice(Arrays.asList(
                        newerNonImportantConversation, newerImportantConversation,
                        olderImportantConversation)));

        List<String> orderedShortcutIds = mManager.getPriorityTiles()
                .stream().map(tile -> tile.getId()).collect(Collectors.toList());

        // Check for sorted priority conversations.
        assertThat(orderedShortcutIds).containsExactly(
                newerImportantConversation.getShortcutInfo().getId(),
                olderImportantConversation.getShortcutInfo().getId())
                .inOrder();
    }

    @Test
    public void testGetTilesReturnsNothingInQuietMode()
            throws Exception {
        // Ensure the less-recent Important conversation is before more recent conversations.
        ConversationChannelWrapper newerNonImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID, false, 3);
        ConversationChannelWrapper newerImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID + 1, true, 3);
        ConversationChannelWrapper olderImportantConversation = getConversationChannelWrapper(
                SHORTCUT_ID + 2,
                true, 1);
        when(mINotificationManager.getConversations(anyBoolean())).thenReturn(
                new ParceledListSlice(Arrays.asList(
                        newerNonImportantConversation, newerImportantConversation,
                        olderImportantConversation)));
        ConversationChannel recentConversation =
                getConversationChannel(
                        SHORTCUT_ID + 3, 4);
        when(mIPeopleManager.getRecentConversations()).thenReturn(
                new ParceledListSlice(Arrays.asList(recentConversation)));

        when(mUserManager.isQuietModeEnabled(any())).thenReturn(true);

        // Check nothing returned.
        assertThat(mManager.getPriorityTiles()).isEmpty();
        assertThat(mManager.getRecentTiles()).isEmpty();
    }

    @Test
    public void testDoNotUpdateAppWidgetIfNoWidgets() throws Exception {
        int[] widgetIdsArray = {};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createNotification(
                OTHER_SHORTCUT_ID, /* isMessagingStyle = */ false, /* isMissedCall = */ false);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateAppWidgetIfNoShortcutInfo() throws Exception {
        int[] widgetIdsArray = {};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        Notification notificationWithoutShortcut = new Notification.Builder(mContext)
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setStyle(new Notification.MessagingStyle(PERSON)
                        .addMessage(new Notification.MessagingStyle.Message("text3", 10, PERSON))
                )
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(new SbnBuilder()
                        .setNotification(notificationWithoutShortcut)
                        .setPkg(TEST_PACKAGE_A)
                        .build())
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateAppWidgetIfNoPackage() throws Exception {
        int[] widgetIdsArray = {};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbnWithoutPackageName = new SbnBuilder()
                .setNotification(createMessagingStyleNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ false, /* isMissedCall = */ false))
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbnWithoutPackageName)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateAppWidgetIfNonConversationChannelModified() throws Exception {
        int[] widgetIdsArray = {1};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotificationChannel channel =
                new NotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_NAME, IMPORTANCE_DEFAULT);

        mNoMan.issueChannelModification(TEST_PACKAGE_A,
                UserHandle.getUserHandleForUid(0), channel, IMPORTANCE_HIGH);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testUpdateAppWidgetIfConversationChannelModified() throws Exception {
        int[] widgetIdsArray = {1};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        NotificationChannel channel =
                new NotificationChannel(TEST_CHANNEL_ID, TEST_CHANNEL_NAME, IMPORTANCE_DEFAULT);
        channel.setConversationId(TEST_PARENT_CHANNEL_ID, TEST_CONVERSATION_ID);

        mNoMan.issueChannelModification(TEST_PACKAGE_A,
                UserHandle.getUserHandleForUid(0), channel, IMPORTANCE_HIGH);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1)).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateNotificationPostedIfDifferentShortcutId() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createNotification(
                OTHER_SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateNotificationPostedIfDifferentPackageName() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbnWithDifferentPackageName = new SbnBuilder()
                .setNotification(createMessagingStyleNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ false, /* isMissedCall = */ false))
                .setPkg(TEST_PACKAGE_B)
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbnWithDifferentPackageName)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateNotificationRemovedIfDifferentShortcutId() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createNotification(
                OTHER_SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(4);
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn, 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateNotificationRemovedIfDifferentPackageName() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbnWithDifferentPackageName = new SbnBuilder()
                .setNotification(createMessagingStyleNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false))
                .setPkg(TEST_PACKAGE_B)
                .build();
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbnWithDifferentPackageName)
                .setId(1));
        mClock.advanceTime(4);
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn, 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testDoNotUpdateStatusPostedIfDifferentShortcutId() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        ConversationStatus status1 = new ConversationStatus.Builder(OTHER_SHORTCUT_ID,
                ACTIVITY_GAME).setDescription("Playing a game!").build();
        ConversationStatus status2 = new ConversationStatus.Builder(OTHER_SHORTCUT_ID,
                ACTIVITY_BIRTHDAY).build();
        ConversationChannel conversationChannel = getConversationWithShortcutId(
                new PeopleTileKey(OTHER_SHORTCUT_ID, 0, TEST_PACKAGE_A),
                Arrays.asList(status1, status2));
        mManager.updateWidgetsWithConversationChanged(conversationChannel);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, never()).updateAppWidget(anyInt(),
                any());
    }

    @Test
    public void testUpdateStatusPostedIfExistingTile() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        ConversationStatus status = new ConversationStatus.Builder(SHORTCUT_ID,
                ACTIVITY_GAME).setDescription("Playing a game!").build();
        ConversationChannel conversationChannel = getConversationWithShortcutId(
                new PeopleTileKey(SHORTCUT_ID, 0, TEST_PACKAGE_A),
                Arrays.asList(status));
        mManager.updateWidgetsWithConversationChanged(conversationChannel);
        mClock.advanceTime(MIN_LINGER_DURATION);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getStatuses()).containsExactly(status);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateStatusPostedOnTwoExistingTiles() throws Exception {
        addSecondWidgetForPersonTile();

        ConversationStatus status = new ConversationStatus.Builder(SHORTCUT_ID,
                ACTIVITY_ANNIVERSARY).build();
        ConversationChannel conversationChannel = getConversationWithShortcutId(
                new PeopleTileKey(SHORTCUT_ID, 0, TEST_PACKAGE_A), Arrays.asList(status));
        mManager.updateWidgetsWithConversationChanged(conversationChannel);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateNotificationPostedIfExistingTile() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false))
                .setShortcutInfo(mShortcutInfo)
                .setId(1);
        NotificationEntry entry = builder.build();
        when(mNotificationEntryManager.getVisibleNotifications()).thenReturn(List.of(entry));

        NotifEvent notif1 = mNoMan.postNotif(builder);
        mClock.advanceTime(MIN_LINGER_DURATION);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tile.getLastInteractionTimestamp()).isEqualTo(SBN_POST_TIME);
        assertThat(tile.getNotificationContent()).isEqualTo(NOTIFICATION_CONTENT_1);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateNotificationPostedOnTwoExistingTiles() throws Exception {
        addSecondWidgetForPersonTile();

        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateNotificationOnExistingTileAfterRemovingTileForSamePerson()
            throws Exception {
        addSecondWidgetForPersonTile();

        PeopleSpaceUtils.removeSharedPreferencesStorageForTile(
                mContext, KEY, SECOND_WIDGET_ID_WITH_SHORTCUT, EMPTY_STRING);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false))
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        verify(mAppWidgetManager, never()).updateAppWidget(eq(SECOND_WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateMissedCallNotificationWithoutContentPostedIfExistingTile()
            throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ false, /* isMissedCall = */ true))
                .setShortcutInfo(mShortcutInfo)
                .setId(1);
        NotificationEntry entry = builder.build();
        when(mNotificationEntryManager.getVisibleNotifications()).thenReturn(List.of(entry));

        NotifEvent notif1 = mNoMan.postNotif(builder);
        mClock.advanceTime(MIN_LINGER_DURATION);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tile.getNotificationContent())
                .isEqualTo(mContext.getString(R.string.missed_call));
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateMissedCallNotificationWithContentPostedIfExistingTile()
            throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ true))
                .setShortcutInfo(mShortcutInfo)
                .setId(1);
        NotificationEntry entry = builder.build();
        when(mNotificationEntryManager.getVisibleNotifications()).thenReturn(List.of(entry));

        NotifEvent notif1 = mNoMan.postNotif(builder);
        mClock.advanceTime(MIN_LINGER_DURATION);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tile.getNotificationContent()).isEqualTo(NOTIFICATION_CONTENT_1);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateMissedCallNotificationWithContentPostedIfMatchingUriTile()
            throws Exception {
        int[] widgetIdsArray =
                {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT, WIDGET_ID_WITH_SAME_URI};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ true))
                .setShortcutInfo(mShortcutInfo)
                .setId(1);
        NotificationEntry entry = builder.build();
        when(mNotificationEntryManager.getVisibleNotifications()).thenReturn(List.of(entry));

        NotifEvent notif1 = mNoMan.postNotif(builder);
        mClock.advanceTime(MIN_LINGER_DURATION);

        PeopleSpaceTile tileWithMissedCallOrigin = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tileWithMissedCallOrigin.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithMissedCallOrigin.getNotificationContent()).isEqualTo(
                NOTIFICATION_CONTENT_1);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        PeopleSpaceTile tileWithSameUri = mManager.mTiles.get(WIDGET_ID_WITH_SAME_URI);
        assertThat(tileWithSameUri.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithSameUri.getNotificationContent()).isEqualTo(NOTIFICATION_CONTENT_1);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SAME_URI),
                any());
    }

    @Test
    public void testRemoveMissedCallNotificationWithContentPostedIfMatchingUriTile()
            throws Exception {
        int[] widgetIdsArray =
                {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT, WIDGET_ID_WITH_SAME_URI};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ true))
                .setShortcutInfo(mShortcutInfo)
                .setId(1);

        NotificationEntry entry = builder.build();
        when(mNotificationEntryManager.getVisibleNotifications()).thenReturn(List.of(entry));

        NotifEvent notif1 = mNoMan.postNotif(builder);
        mClock.advanceTime(MIN_LINGER_DURATION);

        when(mNotificationEntryManager.getVisibleNotifications()).thenReturn(List.of());
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn.cloneLight(), 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        PeopleSpaceTile tileWithMissedCallOrigin = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tileWithMissedCallOrigin.getNotificationKey()).isEqualTo(null);
        assertThat(tileWithMissedCallOrigin.getNotificationContent()).isEqualTo(null);
        verify(mAppWidgetManager, times(2)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        PeopleSpaceTile tileWithSameUri = mManager.mTiles.get(WIDGET_ID_WITH_SAME_URI);
        assertThat(tileWithSameUri.getNotificationKey()).isEqualTo(null);
        assertThat(tileWithSameUri.getNotificationContent()).isEqualTo(null);
        verify(mAppWidgetManager, times(2)).updateAppWidget(eq(WIDGET_ID_WITH_SAME_URI),
                any());
    }

    @Test
    public void testUpdateMissedCallNotificationWithContentPostedIfMatchingUriTileFromSender()
            throws Exception {
        int[] widgetIdsArray =
                {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT, WIDGET_ID_WITH_SAME_URI};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        Notification notificationWithPersonOnlyInSender =
                createMessagingStyleNotificationWithoutExtras(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */
                        true).build();
        StatusBarNotification sbn = new SbnBuilder()
                .setNotification(notificationWithPersonOnlyInSender)
                .setPkg(TEST_PACKAGE_A)
                .setUid(0)
                .setUser(new UserHandle(0))
                .build();
        NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setRank(1)
                .setShortcutInfo(mShortcutInfo)
                .setSbn(sbn)
                .setId(1);
        NotificationEntry entry = builder.build();
        when(mNotificationEntryManager.getVisibleNotifications()).thenReturn(List.of(entry));

        NotifEvent notif1 = mNoMan.postNotif(builder);
        mClock.advanceTime(MIN_LINGER_DURATION);

        PeopleSpaceTile tileWithMissedCallOrigin = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tileWithMissedCallOrigin.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithMissedCallOrigin.getNotificationContent()).isEqualTo(
                NOTIFICATION_CONTENT_1);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        PeopleSpaceTile tileWithSameUri = mManager.mTiles.get(WIDGET_ID_WITH_SAME_URI);
        assertThat(tileWithSameUri.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithSameUri.getNotificationContent()).isEqualTo(NOTIFICATION_CONTENT_1);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SAME_URI),
                any());
    }

    @Test
    public void testDoNotUpdateMissedCallNotificationWithContentPostedIfNoPersonsAttached()
            throws Exception {
        int[] widgetIdsArray =
                {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT, WIDGET_ID_WITH_SAME_URI};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        // Notification posted without any Person attached.
        Notification notificationWithoutPersonObject =
                createMessagingStyleNotificationWithoutExtras(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */
                        true).setStyle(new Notification.MessagingStyle("sender")
                        .addMessage(
                                new Notification.MessagingStyle.Message(NOTIFICATION_CONTENT_1, 10,
                                        "sender"))
                ).build();
        StatusBarNotification sbn = new SbnBuilder()
                .setNotification(notificationWithoutPersonObject)
                .setPkg(TEST_PACKAGE_A)
                .setUid(0)
                .setUser(new UserHandle(0))
                .build();
        NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setSbn(sbn)
                .setShortcutInfo(mShortcutInfo)
                .setId(1);
        NotificationEntry entry = builder.build();
        when(mNotificationEntryManager.getVisibleNotifications()).thenReturn(List.of(entry));

        NotifEvent notif1 = mNoMan.postNotif(builder);

        mClock.advanceTime(MIN_LINGER_DURATION);

        PeopleSpaceTile tileWithMissedCallOrigin = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tileWithMissedCallOrigin.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithMissedCallOrigin.getNotificationContent()).isEqualTo(
                NOTIFICATION_CONTENT_1);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        // Do not update since notification doesn't include a Person reference.
        verify(mAppWidgetManager, times(0)).updateAppWidget(eq(WIDGET_ID_WITH_SAME_URI),
                any());
    }

    @Test
    public void testDoNotUpdateMissedCallNotificationWithContentPostedIfNotMatchingUriTile()
            throws Exception {
        clearStorage();
        addTileForWidget(PERSON_TILE, WIDGET_ID_WITH_SHORTCUT);
        addTileForWidget(PERSON_TILE_WITH_SAME_URI.toBuilder().setContactUri(
                Uri.parse("different_uri")).build(), WIDGET_ID_WITH_DIFFERENT_URI);
        int[] widgetIdsArray =
                {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT, WIDGET_ID_WITH_DIFFERENT_URI};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ true))
                .setShortcutInfo(mShortcutInfo)
                .setId(1);
        NotificationEntry entry = builder.build();
        when(mNotificationEntryManager.getVisibleNotifications()).thenReturn(List.of(entry));

        NotifEvent notif1 = mNoMan.postNotif(builder);
        mClock.advanceTime(MIN_LINGER_DURATION);

        PeopleSpaceTile tileWithMissedCallOrigin = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tileWithMissedCallOrigin.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithMissedCallOrigin.getNotificationContent()).isEqualTo(
                NOTIFICATION_CONTENT_1);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        // Do not update since missing permission to read contacts.
        verify(mAppWidgetManager, times(0)).updateAppWidget(eq(WIDGET_ID_WITH_DIFFERENT_URI),
                any());
    }

    @Test
    public void testDoNotUpdateMissedCallIfMatchingUriTileMissingReadContactsPermission()
            throws Exception {
        when(mPackageManager.checkPermission(any(),
                eq(PERSON_TILE_WITH_SAME_URI.getPackageName()))).thenReturn(
                PERMISSION_HARD_DENIED);
        int[] widgetIdsArray =
                {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT, WIDGET_ID_WITH_SAME_URI};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        NotificationEntryBuilder builder = new NotificationEntryBuilder()
                .setSbn(createNotification(
                        SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ true))
                .setShortcutInfo(mShortcutInfo)
                .setId(1);
        NotificationEntry entry = builder.build();
        when(mNotificationEntryManager.getVisibleNotifications()).thenReturn(List.of(entry));

        NotifEvent notif1 = mNoMan.postNotif(builder);
        mClock.advanceTime(MIN_LINGER_DURATION);

        PeopleSpaceTile tileWithMissedCallOrigin = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tileWithMissedCallOrigin.getNotificationKey()).isEqualTo(NOTIFICATION_KEY);
        assertThat(tileWithMissedCallOrigin.getNotificationContent()).isEqualTo(
                NOTIFICATION_CONTENT_1);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
        // Do not update since missing permission to read contacts.
        PeopleSpaceTile tileNoNotification = mManager.mTiles.get(WIDGET_ID_WITH_SAME_URI);
        assertThat(tileNoNotification.getNotificationKey()).isNull();
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SAME_URI),
                any());
    }

    @Test
    public void testUpdateNotificationRemovedIfExistingTile() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);

        StatusBarNotification sbn = createNotification(
                SHORTCUT_ID, /* isMessagingStyle = */ true, /* isMissedCall = */ false);
        NotifEvent notif1 = mNoMan.postNotif(new NotificationEntryBuilder()
                .setSbn(sbn)
                .setId(1));
        mClock.advanceTime(MIN_LINGER_DURATION);
        long timestampBeforeNotificationClear = System.currentTimeMillis();
        NotifEvent notif1b = mNoMan.retractNotif(notif1.sbn, 0);
        mClock.advanceTime(MIN_LINGER_DURATION);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getNotificationKey()).isEqualTo(null);
        assertThat(tile.getLastInteractionTimestamp()).isLessThan(
                timestampBeforeNotificationClear);
        assertThat(tile.getNotificationContent()).isEqualTo(null);
        assertThat(tile.getNotificationDataUri()).isEqualTo(null);
        verify(mAppWidgetManager, times(2)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testAddThenReconfigureWidgetsUpdatesStorageCacheAndListeners()
            throws Exception {
        clearStorage();
        mManager.addNewWidget(WIDGET_ID_WITH_SHORTCUT, new PeopleTileKey(PERSON_TILE));
        // Check storage.
        SharedPreferences widgetSp = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        assertThat(widgetSp.getString(PACKAGE_NAME, null)).isEqualTo(TEST_PACKAGE_A);
        assertThat(widgetSp.getString(PeopleSpaceUtils.SHORTCUT_ID, null)).isEqualTo(
                PERSON_TILE.getId());
        assertThat(widgetSp.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(0);
        // Check listener and caching.
        verify(mPeopleManager).registerConversationListener(eq(TEST_PACKAGE_A), anyInt(),
                eq(SHORTCUT_ID), any(),
                any());
        verify(mLauncherApps, times(1)).cacheShortcuts(
                eq(TEST_PACKAGE_A),
                eq(Arrays.asList(SHORTCUT_ID)), eq(UserHandle.of(0)),
                eq(LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS));

        // Reconfigure WIDGET_ID_WITH_SHORTCUT from PERSON_TILE to PERSON_TILE_WITH_SAME_URI
        mManager.addNewWidget(
                WIDGET_ID_WITH_SHORTCUT, new PeopleTileKey(PERSON_TILE_WITH_SAME_URI));

        // Check listener is removed and shortcut is uncached.
        verify(mPeopleManager).unregisterConversationListener(any());
        verify(mLauncherApps).uncacheShortcuts(eq(TEST_PACKAGE_A),
                eq(Arrays.asList(PERSON_TILE.getId())), eq(UserHandle.of(0)),
                eq(LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS));
        // Check reconfigured storage from TEST_PACKAGE_A to B and SHORTCUT_ID to OTHER_SHORTCUT_ID.
        assertThat(widgetSp.getString(PACKAGE_NAME, null)).isEqualTo(TEST_PACKAGE_B);
        assertThat(widgetSp.getString(PeopleSpaceUtils.SHORTCUT_ID, null)).isEqualTo(
                OTHER_SHORTCUT_ID);
        assertThat(widgetSp.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(0);
        // Check listener & caching are reconfigured to TEST_PACKAGE_B and OTHER_SHORTCUT_ID.
        verify(mPeopleManager, times(1)).registerConversationListener(eq(TEST_PACKAGE_B), anyInt(),
                eq(OTHER_SHORTCUT_ID), any(),
                any());
        verify(mLauncherApps, times(1)).cacheShortcuts(
                eq(TEST_PACKAGE_B),
                eq(Arrays.asList(OTHER_SHORTCUT_ID)), eq(UserHandle.of(0)),
                eq(LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS));
    }

    @Test
    public void testDeleteAllWidgetsForConversationsUncachesShortcutAndRemovesListeners()
            throws Exception {
        addSecondWidgetForPersonTile();
        mManager.updateWidgets(new int[]{WIDGET_ID_WITH_SHORTCUT, SECOND_WIDGET_ID_WITH_SHORTCUT});

        // Delete only one widget for the conversation in background.
        mManager.deleteWidgets(new int[]{WIDGET_ID_WITH_SHORTCUT});
        mClock.advanceTime(MIN_LINGER_DURATION);

        // Check deleted storage.
        SharedPreferences widgetSp = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        assertThat(widgetSp.getString(PACKAGE_NAME, null)).isNull();
        assertThat(widgetSp.getString(SHORTCUT_ID, null)).isNull();
        assertThat(widgetSp.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(INVALID_USER_ID);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        assertThat(sp.getStringSet(KEY.toString(), new HashSet<>())).containsExactly(
                String.valueOf(SECOND_WIDGET_ID_WITH_SHORTCUT));
        // Check listener & shortcut caching remain for other widget.
        verify(mPeopleManager, never()).unregisterConversationListener(any());
        verify(mLauncherApps, never()).uncacheShortcuts(eq(TEST_PACKAGE_A),
                eq(Arrays.asList(SHORTCUT_ID)), eq(UserHandle.of(0)),
                eq(LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS));

        // Delete all widgets for the conversation in background.
        mManager.deleteWidgets(new int[]{SECOND_WIDGET_ID_WITH_SHORTCUT});
        mClock.advanceTime(MIN_LINGER_DURATION);

        // Check deleted storage.
        SharedPreferences secondWidgetSp = mContext.getSharedPreferences(
                String.valueOf(SECOND_WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        assertThat(secondWidgetSp.getString(PACKAGE_NAME, null)).isNull();
        assertThat(secondWidgetSp.getString(SHORTCUT_ID, null)).isNull();
        assertThat(secondWidgetSp.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(INVALID_USER_ID);
        assertThat(sp.getStringSet(KEY.toString(), new HashSet<>())).isEmpty();
        // Check listener is removed and shortcut is uncached.
        verify(mPeopleManager, times(1)).unregisterConversationListener(any());
        verify(mLauncherApps, times(1)).uncacheShortcuts(eq(TEST_PACKAGE_A),
                eq(Arrays.asList(SHORTCUT_ID)), eq(UserHandle.of(0)),
                eq(LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS));
    }

    @Test
    public void testUpdateWidgetsWithEmptyOptionsAddsPeopleTileToOptions() throws Exception {
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
        when(mAppWidgetManager.getAppWidgetOptions(eq(WIDGET_ID_WITH_SHORTCUT)))
                .thenReturn(new Bundle());

        mManager.updateWidgets(widgetIdsArray);
        mClock.advanceTime(MIN_LINGER_DURATION);

        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testOnAppWidgetOptionsChangedNoWidgetAdded() {
        Bundle newOptions = new Bundle();
        mManager.onAppWidgetOptionsChanged(SECOND_WIDGET_ID_WITH_SHORTCUT, newOptions);

        // Check that options is not modified
        verify(mAppWidgetManager, never()).updateAppWidgetOptions(
                eq(SECOND_WIDGET_ID_WITH_SHORTCUT), any());
        // Check listener is not added and shortcut is not cached.
        verify(mPeopleManager, never()).registerConversationListener(any(), anyInt(), any(), any(),
                any());
        verify(mLauncherApps, never()).cacheShortcuts(any(), any(), any(), anyInt());
        // Check no added storage.
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        assertThat(sp.getStringSet(KEY.toString(), new HashSet<>()))
                .doesNotContain(SECOND_WIDGET_ID_WITH_SHORTCUT);
        SharedPreferences widgetSp = mContext.getSharedPreferences(
                String.valueOf(SECOND_WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        assertThat(widgetSp.getString(PACKAGE_NAME, EMPTY_STRING)).isEqualTo(EMPTY_STRING);
        assertThat(widgetSp.getString(SHORTCUT_ID, EMPTY_STRING)).isEqualTo(EMPTY_STRING);
        assertThat(widgetSp.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(INVALID_USER_ID);

    }

    @Test
    public void testOnAppWidgetOptionsChangedWidgetAdded() {
        Bundle newOptions = new Bundle();
        newOptions.putString(PeopleSpaceUtils.SHORTCUT_ID, SHORTCUT_ID);
        newOptions.putInt(USER_ID, 0);
        newOptions.putString(PACKAGE_NAME, TEST_PACKAGE_A);
        when(mAppWidgetManager.getAppWidgetOptions(eq(SECOND_WIDGET_ID_WITH_SHORTCUT)))
                .thenReturn(newOptions);

        mManager.onAppWidgetOptionsChanged(SECOND_WIDGET_ID_WITH_SHORTCUT, newOptions);

        verify(mAppWidgetManager, times(1)).updateAppWidgetOptions(
                eq(SECOND_WIDGET_ID_WITH_SHORTCUT), mBundleArgumentCaptor.capture());
        Bundle first = mBundleArgumentCaptor.getValue();
        assertThat(first.getString(PeopleSpaceUtils.SHORTCUT_ID, EMPTY_STRING))
                .isEqualTo(EMPTY_STRING);
        assertThat(first.getInt(USER_ID, INVALID_USER_ID)).isEqualTo(INVALID_USER_ID);
        assertThat(first.getString(PACKAGE_NAME, EMPTY_STRING)).isEqualTo(EMPTY_STRING);
        verify(mLauncherApps, times(1)).cacheShortcuts(eq(TEST_PACKAGE_A),
                eq(Arrays.asList(SHORTCUT_ID)), eq(UserHandle.of(0)),
                eq(LauncherApps.FLAG_CACHE_PEOPLE_TILE_SHORTCUTS));
    }

    @Test
    public void testGetPeopleTileFromPersistentStorageExistingConversation()
            throws Exception {
        ConversationChannel channel = getConversationWithShortcutId(
                new PeopleTileKey(SHORTCUT_ID, 0, TEST_PACKAGE_A));
        when(mIPeopleManager.getConversation(TEST_PACKAGE_A, 0, SHORTCUT_ID)).thenReturn(channel);
        PeopleTileKey key = new PeopleTileKey(SHORTCUT_ID, 0, TEST_PACKAGE_A);
        PeopleSpaceTile tile = mManager.getTileFromPersistentStorage(key, WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getId()).isEqualTo(key.getShortcutId());
    }

    @Test
    public void testGetPeopleTileFromPersistentStorageNoConversation() throws Exception {
        when(mIPeopleManager.getConversation(TEST_PACKAGE_A, 0, SHORTCUT_ID)).thenReturn(null);
        PeopleTileKey key = new PeopleTileKey(SHORTCUT_ID, 0, TEST_PACKAGE_A);
        PeopleSpaceTile tile = mManager.getTileFromPersistentStorage(key, WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile).isNull();
    }

    @Test
    public void testRequestPinAppWidgetExistingConversation() throws Exception {
        ConversationChannel channel = getConversationWithShortcutId(
                new PeopleTileKey(SHORTCUT_ID, 0, TEST_PACKAGE_A));
        when(mIPeopleManager.getConversation(TEST_PACKAGE_A, 0, SHORTCUT_ID))
                .thenReturn(channel);
        when(mAppWidgetManager.requestPinAppWidget(any(), any(), any())).thenReturn(true);

        ShortcutInfo info = new ShortcutInfo.Builder(mMockContext, SHORTCUT_ID).build();
        boolean valid = mManager.requestPinAppWidget(info, new Bundle());

        assertThat(valid).isTrue();
        verify(mAppWidgetManager, times(1)).requestPinAppWidget(
                any(), any(), any());
    }

    @Test
    public void testRequestPinAppWidgetNoConversation() throws Exception {
        when(mIPeopleManager.getConversation(TEST_PACKAGE_A, 0, SHORTCUT_ID)).thenReturn(null);

        ShortcutInfo info = new ShortcutInfo.Builder(mMockContext, SHORTCUT_ID).build();
        boolean valid = mManager.requestPinAppWidget(info, new Bundle());

        assertThat(valid).isFalse();
        verify(mAppWidgetManager, never()).requestPinAppWidget(any(), any(), any());
    }

    @Test
    public void testAugmentTileFromNotifications() {
        clearStorage();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        assertThat(sp.getString(String.valueOf(WIDGET_ID_WITH_SHORTCUT), null)).isEqualTo(null);
        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder(SHORTCUT_ID, "userName", ICON, new Intent())
                        .setPackageName(TEST_PACKAGE_A)
                        .setUserHandle(new UserHandle(0))
                        .build();

        PeopleTileKey key = new PeopleTileKey(tile);
        PeopleSpaceTile actual = mManager.augmentTileFromNotifications(tile, key, EMPTY_STRING,
                Map.of(new PeopleTileKey(mNotificationEntry),
                        new HashSet<>(Collections.singleton(mNotificationEntry))),
                Optional.of(WIDGET_ID_WITH_SHORTCUT));

        assertThat(actual.getNotificationContent().toString()).isEqualTo(NOTIFICATION_CONTENT_1);
        assertThat(sp.getString(String.valueOf(WIDGET_ID_WITH_SHORTCUT), null)).isEqualTo(
                URI.toString());
    }

    @Test
    public void testAugmentTileFromNotificationsDifferentShortcutId() {
        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder(OTHER_SHORTCUT_ID, "userName", ICON, new Intent())
                        .setPackageName(TEST_PACKAGE_A)
                        .setUserHandle(new UserHandle(0))
                        .build();
        PeopleTileKey key = new PeopleTileKey(tile);
        PeopleSpaceTile actual = mManager
                .augmentTileFromNotifications(tile, key, EMPTY_STRING,
                        Map.of(new PeopleTileKey(mNotificationEntry),
                                new HashSet<>(Collections.singleton(mNotificationEntry))),
                        Optional.empty());

        assertThat(actual.getNotificationContent()).isEqualTo(null);
    }

    @Test
    public void testAugmentTileFromNotificationEntryManager() {
        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder(SHORTCUT_ID, "userName", ICON, new Intent())
                        .setPackageName(TEST_PACKAGE_A)
                        .setUserHandle(new UserHandle(0))
                        .build();
        when(mNotificationEntryManager.getVisibleNotifications())
                .thenReturn(List.of(mNotificationEntry));

        PeopleSpaceTile actual =
                mManager.augmentTileFromNotificationEntryManager(tile,
                        Optional.of(WIDGET_ID_WITH_SHORTCUT));

        assertThat(actual.getNotificationContent().toString()).isEqualTo(NOTIFICATION_CONTENT_1);

        verify(mNotificationEntryManager, times(1))
                .getVisibleNotifications();
    }

    @Test
    public void testAugmentTileFromNotificationEntryManager_notificationHidden() {
        when(mBubbles.isBubbleNotificationSuppressedFromShade(any(), any())).thenReturn(true);
        PeopleSpaceTile tile =
                new PeopleSpaceTile
                        .Builder(SHORTCUT_ID, "userName", ICON, new Intent())
                        .setPackageName(TEST_PACKAGE_A)
                        .setUserHandle(new UserHandle(0))
                        .build();
        when(mNotificationEntryManager.getVisibleNotifications())
                .thenReturn(List.of(mNotificationEntry));

        PeopleSpaceTile actual =
                mManager.augmentTileFromNotificationEntryManager(tile,
                        Optional.of(WIDGET_ID_WITH_SHORTCUT));

        assertThat(TextUtils.isEmpty(actual.getNotificationContent())).isTrue();

        verify(mNotificationEntryManager, times(1))
                .getVisibleNotifications();
    }

    @Test
    public void testUpdateWidgetsFromBroadcastInBackgroundBootCompleteWithPackageUninstalled()
            throws Exception {
        when(mPackageManager.getApplicationInfoAsUser(any(), anyInt(), anyInt())).thenThrow(
                PackageManager.NameNotFoundException.class);

        // We should remove widgets if the package is uninstalled at next reboot if we missed the
        // package removed broadcast.
        mManager.updateWidgetsFromBroadcastInBackground(ACTION_BOOT_COMPLETED);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile).isNull();
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateWidgetsFromBroadcastInBackgroundPackageRemovedWithPackageUninstalled()
            throws Exception {
        when(mPackageManager.getApplicationInfoAsUser(any(), anyInt(), anyInt())).thenThrow(
                PackageManager.NameNotFoundException.class);

        mManager.updateWidgetsFromBroadcastInBackground(ACTION_PACKAGE_REMOVED);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile).isNull();
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateWidgetsFromBroadcastInBackground() {
        mManager.updateWidgetsFromBroadcastInBackground(ACTION_BOOT_COMPLETED);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.isPackageSuspended()).isFalse();
        assertThat(tile.isUserQuieted()).isFalse();
        assertThat(tile.canBypassDnd()).isFalse();
        assertThat(tile.getNotificationPolicyState()).isEqualTo(SHOW_CONVERSATIONS);
        verify(mAppWidgetManager, times(1)).updateAppWidget(eq(WIDGET_ID_WITH_SHORTCUT),
                any());
    }

    @Test
    public void testUpdateWidgetsFromBroadcastInBackgroundWithUserQuieted() {
        when(mUserManager.isQuietModeEnabled(any())).thenReturn(true);

        mManager.updateWidgetsFromBroadcastInBackground(ACTION_BOOT_COMPLETED);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.isPackageSuspended()).isFalse();
        assertThat(tile.isUserQuieted()).isTrue();
        assertThat(tile.getNotificationPolicyState()).isEqualTo(SHOW_CONVERSATIONS);
    }

    @Test
    public void testUpdateWidgetsFromBroadcastInBackgroundWithPackageSuspended() throws Exception {
        when(mPackageManager.isPackageSuspended(any())).thenReturn(true);

        mManager.updateWidgetsFromBroadcastInBackground(ACTION_PACKAGES_SUSPENDED);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.isPackageSuspended()).isTrue();
        assertThat(tile.isUserQuieted()).isFalse();
        assertThat(tile.getNotificationPolicyState()).isEqualTo(SHOW_CONVERSATIONS);
    }

    @Test
    public void testUpdateWidgetsFromBroadcastInBackgroundNotInDnd() {
        int expected = 0;
        mManager.updateWidgetsFromBroadcastInBackground(NotificationManager
                .ACTION_INTERRUPTION_FILTER_CHANGED);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getNotificationPolicyState()).isEqualTo(expected | SHOW_CONVERSATIONS);
    }

    @Test
    public void testUpdateWidgetsFromBroadcastInBackgroundAllConversations() {
        int expected = 0;
        when(mNotificationManager.getCurrentInterruptionFilter()).thenReturn(
                INTERRUPTION_FILTER_PRIORITY);
        when(mNotificationPolicy.allowConversations()).thenReturn(true);
        setFinalField("priorityConversationSenders", CONVERSATION_SENDERS_ANYONE);

        mManager.updateWidgetsFromBroadcastInBackground(NotificationManager
                .ACTION_INTERRUPTION_FILTER_CHANGED);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getNotificationPolicyState()).isEqualTo(expected | SHOW_CONVERSATIONS);
    }

    @Test
    public void testUpdateWidgetsFromBroadcastInBackgroundAllowOnlyImportantConversations() {
        int expected = 0;
        // Only allow important conversations.
        when(mNotificationManager.getCurrentInterruptionFilter()).thenReturn(
                INTERRUPTION_FILTER_PRIORITY);
        when(mNotificationPolicy.allowConversations()).thenReturn(true);
        setFinalField("priorityConversationSenders", CONVERSATION_SENDERS_IMPORTANT);

        mManager.updateWidgetsFromBroadcastInBackground(NotificationManager
                .ACTION_INTERRUPTION_FILTER_CHANGED);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getNotificationPolicyState()).isEqualTo(
                expected | SHOW_IMPORTANT_CONVERSATIONS);
    }

    @Test
    public void testUpdateWidgetsFromBroadcastInBackgroundAllowNoConversations() {
        int expected = 0;
        when(mNotificationManager.getCurrentInterruptionFilter()).thenReturn(
                INTERRUPTION_FILTER_PRIORITY);
        when(mNotificationPolicy.allowConversations()).thenReturn(false);

        mManager.updateWidgetsFromBroadcastInBackground(NotificationManager
                .ACTION_INTERRUPTION_FILTER_CHANGED);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getNotificationPolicyState()).isEqualTo(expected | BLOCK_CONVERSATIONS);
    }

    @Test
    public void testUpdateWidgetsFromBroadcastInBackgroundAllowNoConversationsAllowContactMessages() {
        int expected = 0;
        when(mNotificationManager.getCurrentInterruptionFilter()).thenReturn(
                INTERRUPTION_FILTER_PRIORITY);
        when(mNotificationPolicy.allowConversations()).thenReturn(false);
        when(mNotificationPolicy.allowMessagesFrom()).thenReturn(ZenModeConfig.SOURCE_CONTACT);
        when(mNotificationPolicy.allowMessages()).thenReturn(true);

        mManager.updateWidgetsFromBroadcastInBackground(ACTION_BOOT_COMPLETED);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getNotificationPolicyState()).isEqualTo(expected | SHOW_CONTACTS);
    }

    @Test
    public void testUpdateWidgetsFromBroadcastInBackgroundAllowNoConversationsAllowStarredContactMessages() {
        int expected = 0;
        when(mNotificationManager.getCurrentInterruptionFilter()).thenReturn(
                INTERRUPTION_FILTER_PRIORITY);
        when(mNotificationPolicy.allowConversations()).thenReturn(false);
        when(mNotificationPolicy.allowMessagesFrom()).thenReturn(ZenModeConfig.SOURCE_STAR);
        when(mNotificationPolicy.allowMessages()).thenReturn(true);

        mManager.updateWidgetsFromBroadcastInBackground(ACTION_BOOT_COMPLETED);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getNotificationPolicyState()).isEqualTo(expected | SHOW_STARRED_CONTACTS);

        setFinalField("suppressedVisualEffects", SUPPRESSED_EFFECT_FULL_SCREEN_INTENT
                | SUPPRESSED_EFFECT_AMBIENT);
        mManager.updateWidgetsFromBroadcastInBackground(ACTION_BOOT_COMPLETED);

        tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getNotificationPolicyState()).isEqualTo(expected | SHOW_CONVERSATIONS);
    }

    @Test
    public void testUpdateWidgetsFromBroadcastInBackgroundAllowAlarmsOnly() {
        int expected = 0;
        when(mNotificationManager.getCurrentInterruptionFilter()).thenReturn(
                INTERRUPTION_FILTER_ALARMS);

        mManager.updateWidgetsFromBroadcastInBackground(NotificationManager
                .ACTION_INTERRUPTION_FILTER_CHANGED);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getNotificationPolicyState()).isEqualTo(expected | BLOCK_CONVERSATIONS);
    }

    @Test
    public void testUpdateWidgetsFromBroadcastInBackgroundAllowVisualEffectsAndAllowAlarmsOnly() {
        int expected = 0;
        // If we show visuals, but just only make sounds for alarms, still show content in tiles.
        when(mNotificationManager.getCurrentInterruptionFilter()).thenReturn(
                INTERRUPTION_FILTER_ALARMS);
        setFinalField("suppressedVisualEffects", SUPPRESSED_EFFECT_FULL_SCREEN_INTENT
                | SUPPRESSED_EFFECT_AMBIENT);

        mManager.updateWidgetsFromBroadcastInBackground(ACTION_BOOT_COMPLETED);

        PeopleSpaceTile tile = mManager.mTiles.get(WIDGET_ID_WITH_SHORTCUT);
        assertThat(tile.getNotificationPolicyState()).isEqualTo(expected | SHOW_CONVERSATIONS);
    }

    @Test
    public void testRemapWidgetFiles() {
        setStorageForTile(SHORTCUT_ID, TEST_PACKAGE_A, WIDGET_ID_8, URI);
        setStorageForTile(OTHER_SHORTCUT_ID, TEST_PACKAGE_B, WIDGET_ID_11, URI);

        mManager.remapWidgetFiles(WIDGETS_MAPPING);

        SharedPreferences sp1 = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITH_SHORTCUT), Context.MODE_PRIVATE);
        PeopleTileKey key1 = SharedPreferencesHelper.getPeopleTileKey(sp1);
        assertThat(key1.getShortcutId()).isEqualTo(SHORTCUT_ID);
        assertThat(key1.getPackageName()).isEqualTo(TEST_PACKAGE_A);

        SharedPreferences sp4 = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITH_KEY_IN_OPTIONS), Context.MODE_PRIVATE);
        PeopleTileKey key4 = SharedPreferencesHelper.getPeopleTileKey(sp4);
        assertThat(key4.getShortcutId()).isEqualTo(OTHER_SHORTCUT_ID);
        assertThat(key4.getPackageName()).isEqualTo(TEST_PACKAGE_B);

        SharedPreferences sp8 = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_8), Context.MODE_PRIVATE);
        PeopleTileKey key8 = SharedPreferencesHelper.getPeopleTileKey(sp8);
        assertThat(key8.getShortcutId()).isNull();
        assertThat(key8.getPackageName()).isNull();

        SharedPreferences sp11 = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_11), Context.MODE_PRIVATE);
        PeopleTileKey key11 = SharedPreferencesHelper.getPeopleTileKey(sp11);
        assertThat(key11.getShortcutId()).isNull();
        assertThat(key11.getPackageName()).isNull();
    }

    @Test
    public void testRemapSharedFile() {
        setStorageForTile(SHORTCUT_ID, TEST_PACKAGE_A, WIDGET_ID_8, URI);
        setStorageForTile(OTHER_SHORTCUT_ID, TEST_PACKAGE_B, WIDGET_ID_11, URI);

        mManager.remapSharedFile(WIDGETS_MAPPING);

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);

        assertThat(sp.getString(String.valueOf(WIDGET_ID_8), null)).isNull();
        assertThat(sp.getString(String.valueOf(WIDGET_ID_11), null)).isNull();
        assertThat(sp.getString(String.valueOf(WIDGET_ID_WITH_SHORTCUT), null))
                .isEqualTo(URI.toString());
        assertThat(sp.getString(String.valueOf(WIDGET_ID_WITH_KEY_IN_OPTIONS), null))
                .isEqualTo(URI.toString());

        assertThat(sp.getStringSet(URI.toString(), new HashSet<>())).containsExactly(
                String.valueOf(WIDGET_ID_WITH_SHORTCUT),
                String.valueOf(WIDGET_ID_WITH_KEY_IN_OPTIONS));

        PeopleTileKey key8 = new PeopleTileKey(SHORTCUT_ID, 0, TEST_PACKAGE_A);
        assertThat(sp.getStringSet(key8.toString(), new HashSet<>())).containsExactly(
                String.valueOf(WIDGET_ID_WITH_SHORTCUT));

        PeopleTileKey key11 = new PeopleTileKey(OTHER_SHORTCUT_ID, 0, TEST_PACKAGE_B);
        assertThat(sp.getStringSet(key11.toString(), new HashSet<>())).containsExactly(
                String.valueOf(WIDGET_ID_WITH_KEY_IN_OPTIONS));
    }

    @Test
    public void testRemapFollowupFile() {
        PeopleTileKey key8 = new PeopleTileKey(SHORTCUT_ID, 0, TEST_PACKAGE_A);
        PeopleTileKey key11 = new PeopleTileKey(OTHER_SHORTCUT_ID, 0, TEST_PACKAGE_B);
        Set<String> set8 = new HashSet<>(Collections.singleton(String.valueOf(WIDGET_ID_8)));
        Set<String> set11 = new HashSet<>(Collections.singleton(String.valueOf(WIDGET_ID_11)));

        SharedPreferences followUp = mContext.getSharedPreferences(
                PeopleBackupFollowUpJob.SHARED_FOLLOW_UP, Context.MODE_PRIVATE);
        SharedPreferences.Editor followUpEditor = followUp.edit();
        followUpEditor.putStringSet(key8.toString(), set8);
        followUpEditor.putStringSet(key11.toString(), set11);
        followUpEditor.apply();

        mManager.remapFollowupFile(WIDGETS_MAPPING);

        assertThat(followUp.getStringSet(key8.toString(), new HashSet<>())).containsExactly(
                String.valueOf(WIDGET_ID_WITH_SHORTCUT));
        assertThat(followUp.getStringSet(key11.toString(), new HashSet<>())).containsExactly(
                String.valueOf(WIDGET_ID_WITH_KEY_IN_OPTIONS));
    }

    private void setFinalField(String fieldName, int value) {
        try {
            Field field = NotificationManager.Policy.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(mNotificationPolicy, value);
        } catch (Exception e) {
        }
    }

    /**
     * Adds another widget for {@code PERSON_TILE} with widget ID: {@code
     * SECOND_WIDGET_ID_WITH_SHORTCUT}.
     */
    private void addSecondWidgetForPersonTile() throws Exception {
        // Set the same Person associated on another People Tile widget ID.
        addTileForWidget(PERSON_TILE, SECOND_WIDGET_ID_WITH_SHORTCUT);
        int[] widgetIdsArray = {WIDGET_ID_WITH_SHORTCUT, WIDGET_ID_WITHOUT_SHORTCUT,
                SECOND_WIDGET_ID_WITH_SHORTCUT};
        when(mAppWidgetManager.getAppWidgetIds(any())).thenReturn(widgetIdsArray);
    }

    private void addTileForWidget(PeopleSpaceTile tile, int widgetId) throws Exception {
        setStorageForTile(tile.getId(), tile.getPackageName(), widgetId, tile.getContactUri());
        Bundle options = new Bundle();
        ConversationChannel channel = getConversationWithShortcutId(new PeopleTileKey(tile));
        when(mAppWidgetManager.getAppWidgetOptions(eq(widgetId)))
                .thenReturn(options);
        when(mIPeopleManager.getConversation(tile.getPackageName(), 0, tile.getId()))
                .thenReturn(channel);
        when(mPackageManager.checkPermission(any(), eq(tile.getPackageName()))).thenReturn(
                PERMISSION_GRANTED);
    }

    /**
     * Returns a single conversation associated with {@code shortcutId}.
     */
    private ConversationChannel getConversationWithShortcutId(PeopleTileKey key) throws Exception {
        return getConversationWithShortcutId(key, Arrays.asList());
    }

    /**
     * Returns a single conversation associated with {@code shortcutId} and {@code statuses}.
     */
    private ConversationChannel getConversationWithShortcutId(PeopleTileKey key,
            List<ConversationStatus> statuses) throws Exception {
        when(mMockContext.getPackageName()).thenReturn(key.getPackageName());
        when(mMockContext.getUserId()).thenReturn(key.getUserId());
        ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(mMockContext, key.getShortcutId())
                .setLongLabel("name").setPerson(PERSON).build();
        ConversationChannel convo = new ConversationChannel(shortcutInfo, 0, null, null,
                0L, false, false, statuses);
        return convo;
    }

    private Notification createMessagingStyleNotification(String shortcutId,
            boolean isMessagingStyle, boolean isMissedCall) {
        Bundle extras = new Bundle();
        ArrayList<Person> person = new ArrayList<Person>();
        person.add(PERSON);
        extras.putParcelableArrayList(EXTRA_PEOPLE_LIST, person);
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setExtras(extras)
                .setShortcutId(shortcutId);
        if (isMessagingStyle) {
            builder.setStyle(new Notification.MessagingStyle(PERSON)
                    .addMessage(
                            new Notification.MessagingStyle.Message(NOTIFICATION_CONTENT_1, 10,
                                    PERSON))
            );
        }
        if (isMissedCall) {
            builder.setCategory(CATEGORY_MISSED_CALL);
        }
        return builder.build();
    }

    private Notification.Builder createMessagingStyleNotificationWithoutExtras(String shortcutId,
            boolean isMessagingStyle, boolean isMissedCall) {
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentTitle("TEST_TITLE")
                .setContentText("TEST_TEXT")
                .setShortcutId(shortcutId);
        if (isMessagingStyle) {
            builder.setStyle(new Notification.MessagingStyle(PERSON)
                    .addMessage(
                            new Notification.MessagingStyle.Message(NOTIFICATION_CONTENT_1, 10,
                                    PERSON))
            );
        }
        if (isMissedCall) {
            builder.setCategory(CATEGORY_MISSED_CALL);
        }
        return builder;
    }


    private StatusBarNotification createNotification(String shortcutId,
            boolean isMessagingStyle, boolean isMissedCall) {
        Notification notification = createMessagingStyleNotification(
                shortcutId, isMessagingStyle, isMissedCall);
        return new SbnBuilder()
                .setNotification(notification)
                .setPkg(TEST_PACKAGE_A)
                .setUid(0)
                .setPostTime(SBN_POST_TIME)
                .setUser(new UserHandle(0))
                .build();
    }

    private void clearStorage() {
        SharedPreferences widgetSp1 = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        widgetSp1.edit().clear().commit();
        SharedPreferences widgetSp2 = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITHOUT_SHORTCUT),
                Context.MODE_PRIVATE);
        widgetSp2.edit().clear().commit();
        SharedPreferences widgetSp3 = mContext.getSharedPreferences(
                String.valueOf(SECOND_WIDGET_ID_WITH_SHORTCUT),
                Context.MODE_PRIVATE);
        widgetSp3.edit().clear().commit();
        SharedPreferences widgetSp4 = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITH_KEY_IN_OPTIONS),
                Context.MODE_PRIVATE);
        widgetSp4.edit().clear().commit();
        SharedPreferences widgetSp5 = mContext.getSharedPreferences(
                String.valueOf(WIDGET_ID_WITH_SAME_URI),
                Context.MODE_PRIVATE);
        widgetSp5.edit().clear().commit();
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        sp.edit().clear().commit();
        mManager.mListeners.clear();
        mManager.mTiles.clear();
    }

    private void setStorageForTile(String shortcutId, String packageName, int widgetId,
            Uri contactUri) {
        SharedPreferences widgetSp = mContext.getSharedPreferences(
                String.valueOf(widgetId),
                Context.MODE_PRIVATE);
        SharedPreferences.Editor widgetEditor = widgetSp.edit();
        widgetEditor.putString(PeopleSpaceUtils.PACKAGE_NAME, packageName);
        widgetEditor.putString(PeopleSpaceUtils.SHORTCUT_ID, shortcutId);
        widgetEditor.putInt(PeopleSpaceUtils.USER_ID, 0);
        widgetEditor.apply();

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(String.valueOf(widgetId), contactUri.toString());

        String key = new PeopleTileKey(shortcutId, 0, packageName).toString();
        Set<String> storedWidgetIds = new HashSet<>(sp.getStringSet(key, new HashSet<>()));
        storedWidgetIds.add(String.valueOf(widgetId));
        editor.putStringSet(key, storedWidgetIds);

        Set<String> storedWidgetIdsByUri = new HashSet<>(
                sp.getStringSet(contactUri.toString(), new HashSet<>()));
        storedWidgetIdsByUri.add(String.valueOf(widgetId));
        editor.putStringSet(contactUri.toString(), storedWidgetIdsByUri);
        editor.apply();
    }

    private ConversationChannelWrapper getConversationChannelWrapper(String shortcutId,
            boolean importantConversation, long lastInteractionTimestamp) throws Exception {
        ConversationChannelWrapper convo = new ConversationChannelWrapper();
        NotificationChannel notificationChannel = new NotificationChannel(shortcutId,
                "channel" + shortcutId,
                NotificationManager.IMPORTANCE_DEFAULT);
        notificationChannel.setImportantConversation(importantConversation);
        convo.setNotificationChannel(notificationChannel);
        convo.setShortcutInfo(new ShortcutInfo.Builder(mContext, shortcutId).setLongLabel(
                "name").build());
        when(mIPeopleManager.getLastInteraction(anyString(), anyInt(),
                eq(shortcutId))).thenReturn(lastInteractionTimestamp);
        return convo;
    }

    private ConversationChannel getConversationChannel(String shortcutId,
            long lastInteractionTimestamp) throws Exception {
        ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(mContext, shortcutId).setLongLabel(
                "name").build();
        ConversationChannel convo = new ConversationChannel(shortcutInfo, 0, null, null,
                lastInteractionTimestamp, false);
        when(mIPeopleManager.getLastInteraction(anyString(), anyInt(),
                eq(shortcutId))).thenReturn(lastInteractionTimestamp);
        return convo;
    }
}
