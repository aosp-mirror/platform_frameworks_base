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

package android.app.people;

import static android.app.people.PeopleSpaceTile.SHOW_CONVERSATIONS;
import static android.app.people.PeopleSpaceTile.SHOW_IMPORTANT_CONVERSATIONS;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.ShortcutInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Parcel;
import android.os.UserHandle;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class PeopleSpaceTileTest {

    private Context mContext;
    private final Drawable mDrawable = new ColorDrawable(Color.BLUE);
    private final Icon mIcon = PeopleSpaceTile.convertDrawableToIcon(mDrawable);

    @Mock
    private LauncherApps mLauncherApps;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
        MockitoAnnotations.initMocks(this);
        when(mLauncherApps.getShortcutIconDrawable(any(), eq(0))).thenReturn(mDrawable);
    }

    @Test
    public void testId() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertThat(tile.getId()).isEqualTo("123");

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setId("5")
                .build();
        assertThat(tile.getId()).isEqualTo("5");

        tile = new PeopleSpaceTile.Builder("12", null, null, null).build();
        assertThat(tile.getId()).isEqualTo("12");
    }

    @Test
    public void testUserName() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertThat(tile.getUserName()).isNull();

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setUserName("Name 1")
                .build();
        assertThat(tile.getUserName()).isEqualTo("Name 1");

        tile = new PeopleSpaceTile.Builder(null, "Name 2", null, null).build();
        assertThat(tile.getUserName()).isEqualTo("Name 2");
    }

    @Test
    public void testUserIcon() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).setUserIcon(
                mIcon).build();
        assertThat(tile.getUserIcon().toString()).isEqualTo(mIcon.toString());

        tile = new PeopleSpaceTile.Builder("12", null, mIcon,
                null).build();
        assertThat(tile.getUserIcon().toString()).isEqualTo(mIcon.toString());
    }

    @Test
    public void testContactUri() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).setContactUri(
                Uri.parse("test")).build();

        assertThat(tile.getContactUri()).isEqualTo(Uri.parse("test"));
    }

    @Test
    public void testUserHandle() {
        PeopleSpaceTile tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setUserHandle(new UserHandle(0))
                .build();

        assertThat(tile.getUserHandle()).isEqualTo(new UserHandle(0));
    }

    @Test
    public void testPackageName() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        // Automatically added by creating a ShortcutInfo.
        assertThat(tile.getPackageName()).isEqualTo("com.android.frameworks.coretests");

        tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).setPackageName(
                "package.name").build();
        assertThat(tile.getPackageName()).isEqualTo("package.name");

        tile = new PeopleSpaceTile.Builder("12", null, null,
                new Intent().setPackage("intent.package")).build();
        assertThat(tile.getPackageName()).isEqualTo("intent.package");
    }

    @Test
    public void testLastInteractionTimestamp() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertThat(tile.getLastInteractionTimestamp()).isEqualTo(0L);

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setLastInteractionTimestamp(7L)
                .build();
        assertThat(tile.getLastInteractionTimestamp()).isEqualTo(7L);
    }

    @Test
    public void testImportantConversation() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertFalse(tile.isImportantConversation());

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setIsImportantConversation(true)
                .build();
        assertTrue(tile.isImportantConversation());
    }

    @Test
    public void testUserQuieted() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertFalse(tile.isUserQuieted());

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setIsUserQuieted(true)
                .build();
        assertTrue(tile.isUserQuieted());
    }

    @Test
    public void testCanBypassDnd() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertFalse(tile.canBypassDnd());

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setCanBypassDnd(true)
                .build();
        assertTrue(tile.canBypassDnd());
    }

    @Test
    public void testNotificationPolicyState() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertThat(tile.getNotificationPolicyState()).isEqualTo(SHOW_CONVERSATIONS);

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setNotificationPolicyState(SHOW_IMPORTANT_CONVERSATIONS)
                .build();
        assertThat(tile.getNotificationPolicyState()).isEqualTo(SHOW_IMPORTANT_CONVERSATIONS);
    }

    @Test
    public void testPackageSuspended() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertFalse(tile.isPackageSuspended());

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setIsPackageSuspended(true)
                .build();
        assertTrue(tile.isPackageSuspended());
    }

    @Test
    public void testContactAffinity() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertThat(tile.getContactAffinity()).isEqualTo(0f);

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setContactAffinity(1f)
                .build();
        assertThat(tile.getContactAffinity()).isEqualTo(1f);
    }

    @Test
    public void testStatuses() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertThat(tile.getStatuses()).isNull();

        List<ConversationStatus> statusList = ImmutableList.of(
                new ConversationStatus.Builder("id", ConversationStatus.ACTIVITY_BIRTHDAY).build());
        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setStatuses(statusList)
                .build();
        assertThat(tile.getStatuses()).isEqualTo(statusList);
    }

    @Test
    public void testCreateFromConversationChannel() {
        ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(mContext, "123").setLongLabel(
                "name").build();
        ConversationChannel convo = new ConversationChannel(shortcutInfo, 0, null, null,
                0L, false, true, null);
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(convo, mLauncherApps).build();
        assertThat(tile.getStatuses()).isNull();
        assertThat(tile.getId()).isEqualTo("123");
        assertThat(tile.getUserName()).isEqualTo("name");
        assertFalse(tile.isImportantConversation());
        assertThat(tile.getLastInteractionTimestamp()).isEqualTo(0L);

        List<ConversationStatus> statuses = ImmutableList.of(
                new ConversationStatus.Builder("id", ConversationStatus.ACTIVITY_BIRTHDAY).build());
        NotificationChannel notificationChannel = new NotificationChannel("123",
                "channel",
                NotificationManager.IMPORTANCE_DEFAULT);
        notificationChannel.setImportantConversation(true);
        convo = new ConversationChannel(shortcutInfo, 0, notificationChannel, null,
                123L, false, true, statuses);
        tile = new PeopleSpaceTile.Builder(convo, mLauncherApps).build();
        assertThat(tile.getStatuses()).isEqualTo(statuses);
        assertTrue(tile.isImportantConversation());
        assertThat(tile.getLastInteractionTimestamp()).isEqualTo(123L);
    }

    @Test
    public void testWriteThenReadFromParcel() {
        List<ConversationStatus> statusList = ImmutableList.of(
                new ConversationStatus.Builder("id", ConversationStatus.ACTIVITY_BIRTHDAY).build());
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setUserName("name")
                .setUserIcon(mIcon)
                .setContactUri(Uri.parse("contact"))
                .setUserHandle(new UserHandle(1))
                .setPackageName("package.name")
                .setLastInteractionTimestamp(7L)
                .setIsImportantConversation(true)
                .setStatuses(statusList).setNotificationKey("key")
                .setNotificationContent("content")
                .setNotificationSender("sender")
                .setNotificationDataUri(Uri.parse("data"))
                .setMessagesCount(2)
                .setIntent(new Intent())
                .setIsUserQuieted(true)
                .setCanBypassDnd(false)
                .setNotificationPolicyState(SHOW_IMPORTANT_CONVERSATIONS)
                .setIsPackageSuspended(true)
                .setContactAffinity(1f)
                .build();

        Parcel parcel = Parcel.obtain();
        tile.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        PeopleSpaceTile readTile = PeopleSpaceTile.CREATOR.createFromParcel(parcel);

        assertThat(readTile.getId()).isEqualTo(tile.getId());
        assertThat(readTile.getUserName()).isEqualTo(tile.getUserName());
        assertThat(readTile.getUserIcon().toString()).isEqualTo(tile.getUserIcon().toString());
        assertThat(readTile.getContactUri()).isEqualTo(tile.getContactUri());
        assertThat(readTile.getUserHandle()).isEqualTo(tile.getUserHandle());
        assertThat(readTile.getPackageName()).isEqualTo(tile.getPackageName());
        assertThat(readTile.getLastInteractionTimestamp()).isEqualTo(
                tile.getLastInteractionTimestamp());
        assertThat(readTile.isImportantConversation()).isEqualTo(tile.isImportantConversation());
        assertThat(readTile.getStatuses()).isEqualTo(tile.getStatuses());
        assertThat(readTile.getNotificationKey()).isEqualTo(tile.getNotificationKey());
        assertThat(readTile.getNotificationContent()).isEqualTo(tile.getNotificationContent());
        assertThat(readTile.getNotificationSender()).isEqualTo(tile.getNotificationSender());
        assertThat(readTile.getNotificationDataUri()).isEqualTo(tile.getNotificationDataUri());
        assertThat(readTile.getMessagesCount()).isEqualTo(tile.getMessagesCount());
        assertThat(readTile.getIntent().toString()).isEqualTo(tile.getIntent().toString());
        assertThat(readTile.isUserQuieted()).isEqualTo(tile.isUserQuieted());
        assertThat(readTile.canBypassDnd()).isEqualTo(tile.canBypassDnd());
        assertThat(readTile.getNotificationPolicyState()).isEqualTo(
                tile.getNotificationPolicyState());
        assertThat(readTile.isPackageSuspended()).isEqualTo(tile.isPackageSuspended());
        assertThat(readTile.getContactAffinity()).isEqualTo(tile.getContactAffinity());
    }

    @Test
    public void testNotificationKey() {
        PeopleSpaceTile tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setNotificationKey("test")
                .build();

        assertThat(tile.getNotificationKey()).isEqualTo("test");
    }

    @Test
    public void testNotificationContent() {
        PeopleSpaceTile tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setNotificationContent("test")
                .build();

        assertThat(tile.getNotificationContent()).isEqualTo("test");
    }

    @Test
    public void testNotificationSender() {
        PeopleSpaceTile tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setNotificationSender("test")
                .build();

        assertThat(tile.getNotificationSender()).isEqualTo("test");
    }

    @Test
    public void testNotificationDataUri() {
        PeopleSpaceTile tile =
                new PeopleSpaceTile.Builder(new ShortcutInfo.Builder(mContext, "123").build(),
                        mLauncherApps)
                        .setNotificationDataUri(Uri.parse("test"))
                        .build();

        assertThat(tile.getNotificationDataUri()).isEqualTo(Uri.parse("test"));
    }

    @Test
    public void testMessagesCount() {
        PeopleSpaceTile tile =
                new PeopleSpaceTile.Builder(new ShortcutInfo.Builder(mContext, "123").build(),
                        mLauncherApps)
                        .setMessagesCount(2)
                        .build();

        assertThat(tile.getMessagesCount()).isEqualTo(2);
    }

    @Test
    public void testIntent() {
        PeopleSpaceTile tile = new PeopleSpaceTile.Builder(
                new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps).build();
        assertThat(tile.getIntent()).isNull();

        tile = new PeopleSpaceTile
                .Builder(new ShortcutInfo.Builder(mContext, "123").build(), mLauncherApps)
                .setIntent(new Intent())
                .build();
        assertThat(tile.getIntent().toString()).isEqualTo(new Intent().toString());

        tile = new PeopleSpaceTile.Builder("12", null, null, new Intent()).build();
        assertThat(tile.getIntent().toString()).isEqualTo(new Intent().toString());
    }

}
