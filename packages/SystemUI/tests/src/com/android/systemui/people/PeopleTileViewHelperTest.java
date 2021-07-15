/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.people;

import static android.app.Notification.CATEGORY_MISSED_CALL;
import static android.app.people.ConversationStatus.ACTIVITY_ANNIVERSARY;
import static android.app.people.ConversationStatus.ACTIVITY_BIRTHDAY;
import static android.app.people.ConversationStatus.ACTIVITY_GAME;
import static android.app.people.ConversationStatus.ACTIVITY_NEW_STORY;
import static android.app.people.ConversationStatus.AVAILABILITY_AVAILABLE;
import static android.app.people.PeopleSpaceTile.BLOCK_CONVERSATIONS;
import static android.app.people.PeopleSpaceTile.SHOW_CONTACTS;
import static android.app.people.PeopleSpaceTile.SHOW_IMPORTANT_CONVERSATIONS;
import static android.app.people.PeopleSpaceTile.SHOW_STARRED_CONTACTS;

import static com.android.systemui.people.PeopleSpaceUtils.STARRED_CONTACT;
import static com.android.systemui.people.PeopleSpaceUtils.VALID_CONTACT;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.people.ConversationStatus;
import android.app.people.PeopleSpaceTile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.people.widget.PeopleTileKey;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.util.Arrays;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class PeopleTileViewHelperTest extends SysuiTestCase {

    private static final String SHORTCUT_ID_1 = "101";
    private static final String NOTIFICATION_KEY = "notification_key";
    private static final String NOTIFICATION_CONTENT = "notification_content";
    private static final Uri URI = Uri.parse("fake_uri");
    private static final Icon ICON = Icon.createWithResource("package", R.drawable.ic_android);
    private static final String GAME_DESCRIPTION = "Playing a game!";
    private static final CharSequence MISSED_CALL = "Custom missed call message";
    private static final String NAME = "username";
    private static final UserHandle USER = new UserHandle(0);
    private static final String SENDER = "sender";

    private static final CharSequence EMOJI_BR_FLAG = "\ud83c\udde7\ud83c\uddf7";
    private static final CharSequence EMOJI_BEAR = "\ud83d\udc3b";
    private static final CharSequence EMOJI_THUMBS_UP_BROWN_SKIN = "\uD83D\uDC4D\uD83C\uDFFD";
    private static final CharSequence EMOJI_JOY = "\uD83D\uDE02";
    private static final CharSequence EMOJI_FAMILY =
            "\ud83d\udc69\u200d\ud83d\udc69\u200d\ud83d\udc67\u200d\ud83d\udc67";

    private static final PeopleSpaceTile PERSON_TILE_WITHOUT_NOTIFICATION =
            new PeopleSpaceTile
                    .Builder(SHORTCUT_ID_1, NAME, ICON, new Intent())
                    .setLastInteractionTimestamp(0L)
                    .setUserHandle(USER)
                    .build();
    private static final PeopleSpaceTile PERSON_TILE =
            new PeopleSpaceTile
                    .Builder(SHORTCUT_ID_1, NAME, ICON, new Intent())
                    .setLastInteractionTimestamp(123L)
                    .setNotificationKey(NOTIFICATION_KEY)
                    .setNotificationContent(NOTIFICATION_CONTENT)
                    .setNotificationDataUri(URI)
                    .setUserHandle(USER)
                    .build();
    private static final PeopleSpaceTile PERSON_TILE_WITH_SENDER =
            new PeopleSpaceTile
                    .Builder(SHORTCUT_ID_1, NAME, ICON, new Intent())
                    .setLastInteractionTimestamp(123L)
                    .setNotificationKey(NOTIFICATION_KEY)
                    .setNotificationContent(NOTIFICATION_CONTENT)
                    .setNotificationSender(SENDER)
                    .setUserHandle(USER)
                    .build();
    private static final ConversationStatus GAME_STATUS =
            new ConversationStatus
                    .Builder(PERSON_TILE.getId(), ACTIVITY_GAME)
                    .setDescription(GAME_DESCRIPTION)
                    .build();
    private static final ConversationStatus NEW_STORY_WITH_AVAILABILITY =
            new ConversationStatus
                    .Builder(PERSON_TILE.getId(), ACTIVITY_NEW_STORY)
                    .setAvailability(AVAILABILITY_AVAILABLE)
                    .build();

    @Mock
    private Icon mIcon;

    @Mock
    private Context mMockContext;
    @Mock
    private PackageManager mPackageManager;

    private int mWidth;
    private int mHeight;
    private PeopleTileViewHelper mPeopleTileViewHelper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mMockContext.getString(R.string.birthday_status)).thenReturn(
                mContext.getString(R.string.birthday_status));
        when(mMockContext.getPackageManager()).thenReturn(mPackageManager);
        when(mMockContext.getString(R.string.over_two_weeks_timestamp)).thenReturn(
                mContext.getString(R.string.over_two_weeks_timestamp));
        Configuration configuration = mock(Configuration.class);
        DisplayMetrics displayMetrics = mock(DisplayMetrics.class);
        Resources resources = mock(Resources.class);
        when(mMockContext.getResources()).thenReturn(resources);
        when(resources.getConfiguration()).thenReturn(configuration);
        when(resources.getDisplayMetrics()).thenReturn(displayMetrics);
        TextView textView = mock(TextView.class);
        when(textView.getLineHeight()).thenReturn(16);
        when(mPackageManager.getApplicationIcon(anyString())).thenReturn(null);

        mWidth = getSizeInDp(R.dimen.default_width);
        mHeight = getSizeInDp(R.dimen.default_height);
        mPeopleTileViewHelper = getPeopleTileViewHelper(PERSON_TILE);
    }

    @Test
    public void testCreateRemoteViewsWithLastInteractionTimeUnderOneDayHidden() {
        RemoteViews views = getPeopleTileViewHelper(PERSON_TILE_WITHOUT_NOTIFICATION).getViews();
        View result = views.apply(mContext, null);

        // Not showing last interaction.
        assertEquals(View.GONE, result.findViewById(R.id.last_interaction).getVisibility());

        mWidth = getSizeInDp(R.dimen.required_width_for_large);
        mHeight = getSizeInDp(R.dimen.required_height_for_large);
        RemoteViews largeView = getPeopleTileViewHelper(
                PERSON_TILE_WITHOUT_NOTIFICATION).getViews();
        View largeResult = largeView.apply(mContext, null);

        // Not showing last interaction.
        assertEquals(View.GONE, largeResult.findViewById(R.id.last_interaction).getVisibility());
    }

    @Test
    public void testLastInteractionTime() {
        long now = System.currentTimeMillis();
        long fiveDaysAgo = now - Duration.ofDays(5).toMillis();
        String lastInteractionString = PeopleTileViewHelper.getLastInteractionString(mContext,
                fiveDaysAgo);
        assertThat(lastInteractionString).isEqualTo("5 days ago");

        long lessThanOneDayAgo = now - Duration.ofHours(20).toMillis();
        lastInteractionString = PeopleTileViewHelper.getLastInteractionString(mContext,
                lessThanOneDayAgo);
        assertThat(lastInteractionString).isNull();

        long overOneWeekAgo = now - Duration.ofDays(8).toMillis();
        lastInteractionString = PeopleTileViewHelper.getLastInteractionString(mContext,
                overOneWeekAgo);
        assertThat(lastInteractionString).isEqualTo("Over 1 week ago");

        long overTwoWeeksAgo = now - Duration.ofDays(15).toMillis();
        lastInteractionString = PeopleTileViewHelper.getLastInteractionString(mContext,
                overTwoWeeksAgo);
        assertThat(lastInteractionString).isEqualTo("Over 2 weeks ago");
    }

    @Test
    public void testCreateRemoteViewsWithLastInteractionTime() {
        PeopleSpaceTile tileWithLastInteraction =
                PERSON_TILE_WITHOUT_NOTIFICATION.toBuilder().setLastInteractionTimestamp(
                        123445L).build();
        RemoteViews views = getPeopleTileViewHelper(tileWithLastInteraction).getViews();
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        // Has last interaction.
        assertEquals(View.VISIBLE, result.findViewById(R.id.last_interaction).getVisibility());
        TextView lastInteraction = (TextView) result.findViewById(R.id.last_interaction);
        assertEquals(lastInteraction.getText(), "Over 2 weeks ago");
        // No availability.
        assertEquals(View.GONE, result.findViewById(R.id.availability).getVisibility());
        // Shows person icon.
        assertEquals(View.VISIBLE, result.findViewById(R.id.person_icon).getVisibility());
        // No status.
        assertThat((View) result.findViewById(R.id.text_content)).isNull();

        mWidth = getSizeInDp(R.dimen.required_width_for_medium) - 1;
        RemoteViews smallView = getPeopleTileViewHelper(tileWithLastInteraction).getViews();
        View smallResult = smallView.apply(mContext, null);

        // Show name over predefined icon.
        assertEquals(View.VISIBLE, smallResult.findViewById(R.id.name).getVisibility());
        assertEquals(View.GONE, smallResult.findViewById(R.id.predefined_icon).getVisibility());
        // Shows person icon.
        assertEquals(View.VISIBLE, smallResult.findViewById(R.id.person_icon).getVisibility());
        // No messages count.
        assertEquals(View.GONE, smallResult.findViewById(R.id.messages_count).getVisibility());

        mHeight = getSizeInDp(R.dimen.required_height_for_medium) - 1;
        RemoteViews smallViewHorizontal = getPeopleTileViewHelper(
                tileWithLastInteraction).getViews();
        View smallResultHorizontal = smallViewHorizontal.apply(mContext, null);

        // Show name over predefined icon.
        assertEquals(View.VISIBLE, smallResultHorizontal.findViewById(R.id.name).getVisibility());
        assertEquals(View.GONE,
                smallResultHorizontal.findViewById(R.id.predefined_icon).getVisibility());
        // Shows person icon.
        assertEquals(View.VISIBLE,
                smallResultHorizontal.findViewById(R.id.person_icon).getVisibility());
        // No messages count.
        assertEquals(View.GONE,
                smallResultHorizontal.findViewById(R.id.messages_count).getVisibility());

        mWidth = getSizeInDp(R.dimen.required_width_for_large);
        mHeight = getSizeInDp(R.dimen.required_height_for_large);
        RemoteViews largeView = getPeopleTileViewHelper(
                tileWithLastInteraction).getViews();
        View largeResult = largeView.apply(mContext, null);

        name = (TextView) largeResult.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        // Has last interaction.
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.last_interaction).getVisibility());
        lastInteraction = (TextView) result.findViewById(R.id.last_interaction);
        assertEquals(lastInteraction.getText(), "Over 2 weeks ago");
        // No availability.
        assertEquals(View.GONE, result.findViewById(R.id.availability).getVisibility());
        // Shows person icon.
        assertEquals(View.VISIBLE, result.findViewById(R.id.person_icon).getVisibility());
        // No status.
        assertThat((View) result.findViewById(R.id.text_content)).isNull();
    }

    @Test
    public void testCreateRemoteViewsWithGameTypeOnlyIsIgnored() {
        PeopleSpaceTile tileWithAvailabilityAndNewStory =
                PERSON_TILE_WITHOUT_NOTIFICATION.toBuilder().setStatuses(
                        Arrays.asList(NEW_STORY_WITH_AVAILABILITY,
                                new ConversationStatus.Builder(
                                        PERSON_TILE_WITHOUT_NOTIFICATION.getId(),
                                        ACTIVITY_GAME).build())).build();
        RemoteViews views = getPeopleTileViewHelper(tileWithAvailabilityAndNewStory).getViews();
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        // Has last interaction over status.
        assertEquals(View.GONE, result.findViewById(R.id.last_interaction).getVisibility());
        // Has availability.
        assertEquals(View.VISIBLE, result.findViewById(R.id.availability).getVisibility());
        assertEquals(result.findViewById(R.id.availability).getContentDescription(),
                mContext.getString(R.string.person_available));
        // Has person icon.
        assertEquals(View.VISIBLE, result.findViewById(R.id.person_icon).getVisibility());
        // No status.
        assertThat((View) result.findViewById(R.id.text_content)).isNull();

        mWidth = getSizeInDp(R.dimen.required_width_for_medium) - 1;
        RemoteViews smallView = getPeopleTileViewHelper(tileWithAvailabilityAndNewStory).getViews();
        View smallResult = smallView.apply(mContext, null);

        // Show name rather than game type.
        assertEquals(View.VISIBLE, smallResult.findViewById(R.id.name).getVisibility());
        assertEquals(View.GONE, smallResult.findViewById(R.id.predefined_icon).getVisibility());
        // Has person icon.
        assertEquals(View.VISIBLE,
                smallResult.findViewById(R.id.person_icon).getVisibility());
        // No messages count.
        assertEquals(View.GONE, smallResult.findViewById(R.id.messages_count).getVisibility());

        mHeight = getSizeInDp(R.dimen.required_height_for_medium) - 1;
        RemoteViews smallViewHorizontal = getPeopleTileViewHelper(
                tileWithAvailabilityAndNewStory).getViews();
        View smallResultHorizontal = smallViewHorizontal.apply(mContext, null);

        // Show name over predefined icon.
        assertEquals(View.VISIBLE, smallResultHorizontal.findViewById(R.id.name).getVisibility());
        assertEquals(View.GONE,
                smallResultHorizontal.findViewById(R.id.predefined_icon).getVisibility());
        // Shows person icon.
        assertEquals(View.VISIBLE,
                smallResultHorizontal.findViewById(R.id.person_icon).getVisibility());
        // No messages count.
        assertEquals(View.GONE,
                smallResultHorizontal.findViewById(R.id.messages_count).getVisibility());

        mWidth = getSizeInDp(R.dimen.required_width_for_large);
        mHeight = getSizeInDp(R.dimen.required_height_for_large);
        RemoteViews largeView = getPeopleTileViewHelper(tileWithAvailabilityAndNewStory).getViews();
        View largeResult = largeView.apply(mContext, null);

        name = (TextView) largeResult.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        // Has last interaction.
        assertEquals(View.GONE, largeResult.findViewById(R.id.last_interaction).getVisibility());
        // Has availability.
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.availability).getVisibility());
        assertEquals(largeResult.findViewById(R.id.availability).getContentDescription(),
                mContext.getString(R.string.person_available));
        // Shows person icon.
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.person_icon).getVisibility());
        // No status.
        assertThat((View) largeResult.findViewById(R.id.text_content)).isNull();
    }

    @Test
    public void testCreateRemoteViewsWithBirthdayTypeOnlyIsNotIgnored() {
        PeopleSpaceTile tileWithStatusTemplate =
                PERSON_TILE_WITHOUT_NOTIFICATION.toBuilder().setStatuses(
                        Arrays.asList(
                                NEW_STORY_WITH_AVAILABILITY, new ConversationStatus.Builder(
                                        PERSON_TILE_WITHOUT_NOTIFICATION.getId(),
                                        ACTIVITY_BIRTHDAY).build())).build();
        RemoteViews views = getPeopleTileViewHelper(tileWithStatusTemplate).getViews();
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        assertEquals(View.GONE, result.findViewById(R.id.subtext).getVisibility());
        assertEquals(View.VISIBLE, result.findViewById(R.id.predefined_icon).getVisibility());
        // Has availability.
        assertEquals(View.VISIBLE, result.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        assertEquals(View.VISIBLE, result.findViewById(R.id.person_icon).getVisibility());
        // Has status text from backup text.
        TextView statusContent = (TextView) result.findViewById(R.id.text_content);
        assertEquals(View.VISIBLE, statusContent.getVisibility());
        assertEquals(statusContent.getText(), mContext.getString(R.string.birthday_status));
        assertThat(statusContent.getMaxLines()).isEqualTo(2);
        assertThat(statusContent.getContentDescription().toString()).isEqualTo(
                mContext.getString(R.string.new_status_content_description, NAME,
                        mContext.getString(R.string.birthday_status_content_description, NAME)));

        mWidth = getSizeInDp(R.dimen.required_width_for_medium) - 1;
        RemoteViews smallView = getPeopleTileViewHelper(tileWithStatusTemplate).getViews();
        View smallResult = smallView.apply(mContext, null);

        // Show icon instead of name.
        assertEquals(View.GONE, smallResult.findViewById(R.id.name).getVisibility());
        assertEquals(View.VISIBLE,
                smallResult.findViewById(R.id.predefined_icon).getVisibility());
        assertThat(smallResult.findViewById(
                R.id.predefined_icon).getContentDescription().toString()).isEqualTo(
                mContext.getString(R.string.new_status_content_description, NAME,
                        mContext.getString(R.string.birthday_status_content_description, NAME)));
        // Has person icon.
        assertEquals(View.VISIBLE,
                smallResult.findViewById(R.id.person_icon).getVisibility());
        // No messages count.
        assertEquals(View.GONE, smallResult.findViewById(R.id.messages_count).getVisibility());

        mHeight = getSizeInDp(R.dimen.required_height_for_medium) - 1;
        RemoteViews smallViewHorizontal = getPeopleTileViewHelper(
                tileWithStatusTemplate).getViews();
        View smallResultHorizontal = smallViewHorizontal.apply(mContext, null);

        // Show name over predefined icon.
        assertEquals(View.GONE, smallResultHorizontal.findViewById(R.id.name).getVisibility());
        assertEquals(View.VISIBLE,
                smallResultHorizontal.findViewById(R.id.predefined_icon).getVisibility());
        // Shows person icon.
        assertEquals(View.VISIBLE,
                smallResultHorizontal.findViewById(R.id.person_icon).getVisibility());
        // No messages count.
        assertEquals(View.GONE,
                smallResultHorizontal.findViewById(R.id.messages_count).getVisibility());

        mWidth = getSizeInDp(R.dimen.required_width_for_large);
        mHeight = getSizeInDp(R.dimen.required_height_for_large);
        RemoteViews largeView = getPeopleTileViewHelper(tileWithStatusTemplate).getViews();
        View largeResult = largeView.apply(mContext, null);

        name = (TextView) largeResult.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        assertEquals(View.GONE, largeResult.findViewById(R.id.subtext).getVisibility());
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.predefined_icon).getVisibility());
        // Has availability.
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        View personIcon = largeResult.findViewById(R.id.person_icon);
        assertEquals(View.VISIBLE, personIcon.getVisibility());
        // Has notification content.
        statusContent = (TextView) largeResult.findViewById(R.id.text_content);
        assertEquals(View.VISIBLE, statusContent.getVisibility());
        assertEquals(statusContent.getText(), mContext.getString(R.string.birthday_status));
        assertThat(statusContent.getMaxLines()).isEqualTo(2);
        assertThat(statusContent.getContentDescription().toString()).isEqualTo(
                mContext.getString(R.string.new_status_content_description, NAME,
                        mContext.getString(R.string.birthday_status_content_description, NAME)));
    }

    @Test
    public void testCreateRemoteViewsWithStatusTemplate() {
        PeopleSpaceTile tileWithStatusTemplate =
                PERSON_TILE_WITHOUT_NOTIFICATION.toBuilder().setStatuses(
                        Arrays.asList(GAME_STATUS,
                                NEW_STORY_WITH_AVAILABILITY)).build();
        RemoteViews views = getPeopleTileViewHelper(tileWithStatusTemplate).getViews();
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        assertEquals(View.GONE, result.findViewById(R.id.subtext).getVisibility());
        assertEquals(View.VISIBLE, result.findViewById(R.id.predefined_icon).getVisibility());
        assertEquals(View.GONE, result.findViewById(R.id.scrim_layout).getVisibility());
        assertEquals(View.GONE, result.findViewById(R.id.image).getVisibility());
        // Has availability.
        assertEquals(View.VISIBLE, result.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        assertEquals(View.VISIBLE, result.findViewById(R.id.person_icon).getVisibility());
        // Has status.
        TextView statusContent = (TextView) result.findViewById(R.id.text_content);
        assertEquals(statusContent.getText(), GAME_DESCRIPTION);
        assertThat(statusContent.getMaxLines()).isEqualTo(2);
        assertThat(statusContent.getContentDescription().toString()).isEqualTo(
                mContext.getString(R.string.new_status_content_description, NAME,
                        GAME_DESCRIPTION));

        mWidth = getSizeInDp(R.dimen.required_width_for_medium) - 1;
        RemoteViews smallView = getPeopleTileViewHelper(tileWithStatusTemplate).getViews();
        View smallResult = smallView.apply(mContext, null);

        // Show icon instead of name.
        assertEquals(View.GONE, smallResult.findViewById(R.id.name).getVisibility());
        assertEquals(View.VISIBLE,
                smallResult.findViewById(R.id.predefined_icon).getVisibility());
        assertThat(smallResult.findViewById(
                R.id.predefined_icon).getContentDescription().toString()).isEqualTo(
                mContext.getString(R.string.new_status_content_description, NAME,
                        GAME_DESCRIPTION));
        // Has person icon.
        assertEquals(View.VISIBLE,
                smallResult.findViewById(R.id.person_icon).getVisibility());
        // No messages count.
        assertEquals(View.GONE, smallResult.findViewById(R.id.messages_count).getVisibility());

        mHeight = getSizeInDp(R.dimen.required_height_for_medium) - 1;
        RemoteViews smallViewHorizontal = getPeopleTileViewHelper(
                tileWithStatusTemplate).getViews();
        View smallResultHorizontal = smallViewHorizontal.apply(mContext, null);

        // Show name over predefined icon.
        assertEquals(View.GONE, smallResultHorizontal.findViewById(R.id.name).getVisibility());
        assertEquals(View.VISIBLE,
                smallResultHorizontal.findViewById(R.id.predefined_icon).getVisibility());
        // Shows person icon.
        assertEquals(View.VISIBLE,
                smallResultHorizontal.findViewById(R.id.person_icon).getVisibility());
        // No messages count.
        assertEquals(View.GONE,
                smallResultHorizontal.findViewById(R.id.messages_count).getVisibility());

        mWidth = getSizeInDp(R.dimen.required_width_for_large);
        mHeight = getSizeInDp(R.dimen.required_height_for_large);
        RemoteViews largeView = getPeopleTileViewHelper(tileWithStatusTemplate).getViews();
        View largeResult = largeView.apply(mContext, null);

        name = (TextView) largeResult.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        assertEquals(View.GONE, largeResult.findViewById(R.id.subtext).getVisibility());
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.predefined_icon).getVisibility());
        assertEquals(View.GONE, largeResult.findViewById(R.id.scrim_layout).getVisibility());
        assertEquals(View.GONE, largeResult.findViewById(R.id.image).getVisibility());
        // Has availability.
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        View personIcon = largeResult.findViewById(R.id.person_icon);
        assertEquals(View.VISIBLE, personIcon.getVisibility());
        // Has notification content.
        statusContent = (TextView) largeResult.findViewById(R.id.text_content);
        assertEquals(View.VISIBLE, statusContent.getVisibility());
        assertEquals(statusContent.getText(), GAME_DESCRIPTION);
        assertThat(statusContent.getMaxLines()).isEqualTo(2);
        assertThat(statusContent.getContentDescription().toString()).isEqualTo(
                mContext.getString(R.string.new_status_content_description, NAME,
                        GAME_DESCRIPTION));
    }

    @Test
    public void testCreateRemoteViewsWithStatusTemplateWithImageOnMediumAndLarge() {
        PeopleSpaceTile tileWithIconInStatusTemplate =
                PERSON_TILE_WITHOUT_NOTIFICATION.toBuilder().setStatuses(
                        Arrays.asList(new ConversationStatus.Builder(PERSON_TILE.getId(),
                                ACTIVITY_ANNIVERSARY).setDescription("Anniversary").setAvailability(
                                AVAILABILITY_AVAILABLE).setIcon(mIcon).build())).build();
        RemoteViews views = getPeopleTileViewHelper(
                tileWithIconInStatusTemplate).getViews();
        View result = views.apply(mContext, null);

        assertEquals(View.GONE, result.findViewById(R.id.subtext).getVisibility());
        assertEquals(View.VISIBLE, result.findViewById(R.id.predefined_icon).getVisibility());
        assertEquals(View.VISIBLE, result.findViewById(R.id.scrim_layout).getVisibility());
        assertEquals(View.GONE, result.findViewById(R.id.image).getVisibility());
        // Has availability.
        assertEquals(View.VISIBLE, result.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        assertEquals(View.VISIBLE, result.findViewById(R.id.person_icon).getVisibility());
        // Has status.
        TextView statusContent = (TextView) result.findViewById(R.id.name);
        assertEquals(statusContent.getText(), "Anniversary");
        // Since the image is showing which removes name, we need to manually include the name.
        assertThat(statusContent.getContentDescription().toString()).isEqualTo(
                mContext.getString(R.string.new_status_content_description, NAME, "Anniversary"));
        assertThat(statusContent.getMaxLines()).isEqualTo(1);

        mWidth = getSizeInDp(R.dimen.required_width_for_large);
        mHeight = getSizeInDp(R.dimen.required_height_for_large);
        RemoteViews largeView = getPeopleTileViewHelper(tileWithIconInStatusTemplate).getViews();
        View largeResult = largeView.apply(mContext, null);

        assertEquals(View.GONE, largeResult.findViewById(R.id.subtext).getVisibility());
        assertEquals(View.GONE, largeResult.findViewById(R.id.name).getVisibility());
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.predefined_icon).getVisibility());
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.scrim_layout).getVisibility());
        assertEquals(View.GONE, largeResult.findViewById(R.id.image).getVisibility());
        // Has availability.
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        View personIcon = largeResult.findViewById(R.id.person_icon);
        assertEquals(View.VISIBLE, personIcon.getVisibility());
        // Has status content.
        statusContent = (TextView) largeResult.findViewById(R.id.text_content);
        assertEquals(View.VISIBLE, statusContent.getVisibility());
        assertEquals(statusContent.getText(), "Anniversary");
        // Since the image is showing which removes name, we need to manually include the name.
        assertThat(statusContent.getContentDescription().toString()).isEqualTo(
                mContext.getString(R.string.new_status_content_description, NAME, "Anniversary"));
        assertThat(statusContent.getMaxLines()).isEqualTo(2);
    }

    @Test
    public void testCreateRemoteViewsWithPackageSuspended() {
        PeopleSpaceTile tile = PERSON_TILE.toBuilder()
                .setIsPackageSuspended(true)
                .build();
        RemoteViews views = getPeopleTileViewHelper(tile).getViews();
        View result = views.apply(mContext, null);

        assertEquals(result.getSourceLayoutResId(), R.layout.people_tile_suppressed_layout);
    }

    @Test
    public void testCreateRemoteViewsWithUserQuieted() {
        PeopleSpaceTile tile = PERSON_TILE.toBuilder()
                .setIsUserQuieted(true)
                .build();
        RemoteViews views = getPeopleTileViewHelper(tile).getViews();
        View result = views.apply(mContext, null);

        assertEquals(result.getSourceLayoutResId(), R.layout.people_tile_work_profile_quiet_layout);
    }

    @Test
    public void testCreateRemoteViewsWithDndBlocking() {
        PeopleSpaceTile tileWithDndBlocking = PERSON_TILE.toBuilder()
                .setNotificationPolicyState(BLOCK_CONVERSATIONS)
                .build();
        RemoteViews views = getPeopleTileViewHelper(tileWithDndBlocking).getViews();
        View result = views.apply(mContext, null);

        assertResourcesEqual(
                result.getSourceLayoutResId(),
                R.layout.people_tile_with_suppression_detail_content_horizontal);

        tileWithDndBlocking = PERSON_TILE.toBuilder()
                .setNotificationPolicyState(BLOCK_CONVERSATIONS)
                .setCanBypassDnd(true)
                .build();
        views = getPeopleTileViewHelper(tileWithDndBlocking).getViews();
        result = views.apply(mContext, null);

        assertResourcesNotEqual(
                result.getSourceLayoutResId(),
                R.layout.people_tile_with_suppression_detail_content_horizontal);

        tileWithDndBlocking = PERSON_TILE.toBuilder()
                .setNotificationPolicyState(SHOW_IMPORTANT_CONVERSATIONS)
                .build();
        views = getPeopleTileViewHelper(tileWithDndBlocking).getViews();
        result = views.apply(mContext, null);

        assertResourcesEqual(
                result.getSourceLayoutResId(),
                R.layout.people_tile_with_suppression_detail_content_horizontal);

        tileWithDndBlocking = PERSON_TILE.toBuilder()
                .setNotificationPolicyState(SHOW_IMPORTANT_CONVERSATIONS)
                .setIsImportantConversation(true)
                .build();
        views = getPeopleTileViewHelper(tileWithDndBlocking).getViews();
        result = views.apply(mContext, null);

        assertResourcesNotEqual(
                result.getSourceLayoutResId(),
                R.layout.people_tile_with_suppression_detail_content_horizontal);

        tileWithDndBlocking = PERSON_TILE.toBuilder()
                .setNotificationPolicyState(SHOW_STARRED_CONTACTS)
                .setContactAffinity(VALID_CONTACT)
                .build();
        views = getPeopleTileViewHelper(tileWithDndBlocking).getViews();
        result = views.apply(mContext, null);

        assertResourcesEqual(
                result.getSourceLayoutResId(),
                R.layout.people_tile_with_suppression_detail_content_horizontal);

        tileWithDndBlocking = PERSON_TILE.toBuilder()
                .setNotificationPolicyState(SHOW_STARRED_CONTACTS)
                .setContactAffinity(STARRED_CONTACT)
                .build();
        views = getPeopleTileViewHelper(tileWithDndBlocking).getViews();
        result = views.apply(mContext, null);

        assertResourcesNotEqual(
                result.getSourceLayoutResId(),
                R.layout.people_tile_with_suppression_detail_content_horizontal);

        tileWithDndBlocking = PERSON_TILE.toBuilder()
                .setNotificationPolicyState(SHOW_CONTACTS)
                .setContactAffinity(STARRED_CONTACT)
                .build();
        views = getPeopleTileViewHelper(tileWithDndBlocking).getViews();
        result = views.apply(mContext, null);

        assertResourcesNotEqual(
                result.getSourceLayoutResId(),
                R.layout.people_tile_with_suppression_detail_content_horizontal);

        tileWithDndBlocking = PERSON_TILE.toBuilder()
                .setNotificationPolicyState(SHOW_CONTACTS)
                .setContactAffinity(VALID_CONTACT)
                .build();
        views = getPeopleTileViewHelper(tileWithDndBlocking).getViews();
        result = views.apply(mContext, null);

        assertResourcesNotEqual(
                result.getSourceLayoutResId(),
                R.layout.people_tile_with_suppression_detail_content_horizontal);

        tileWithDndBlocking = PERSON_TILE.toBuilder()
                .setNotificationPolicyState(SHOW_CONTACTS)
                .build();
        views = getPeopleTileViewHelper(tileWithDndBlocking).getViews();
        result = views.apply(mContext, null);

        assertResourcesEqual(
                result.getSourceLayoutResId(),
                R.layout.people_tile_with_suppression_detail_content_horizontal);
        assertThat(result.<TextView>findViewById(R.id.text_content).getText().toString())
                .isEqualTo(mContext.getString(R.string.paused_by_dnd));
    }

    @Test
    public void testCreateRemoteViewsWithMissedCallNotification() {
        PeopleSpaceTile tileWithMissedCallNotification = PERSON_TILE.toBuilder()
                .setNotificationDataUri(null)
                .setNotificationCategory(CATEGORY_MISSED_CALL)
                .setNotificationContent(MISSED_CALL)
                .build();
        RemoteViews views = getPeopleTileViewHelper(tileWithMissedCallNotification).getViews();
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        assertEquals(View.GONE, result.findViewById(R.id.subtext).getVisibility());
        assertEquals(View.VISIBLE, result.findViewById(R.id.predefined_icon).getVisibility());
        // Has availability.
        assertEquals(View.GONE, result.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        assertEquals(View.VISIBLE, result.findViewById(R.id.person_icon).getVisibility());
        // Has missed call notification content.
        TextView statusContent = (TextView) result.findViewById(R.id.text_content);
        assertEquals(View.VISIBLE, statusContent.getVisibility());
        assertEquals(statusContent.getText(), MISSED_CALL);
        assertEquals(statusContent.getContentDescription(), mContext.getString(
                R.string.new_notification_text_content_description, NAME, MISSED_CALL));
        assertThat(statusContent.getMaxLines()).isEqualTo(2);

        mWidth = getSizeInDp(R.dimen.required_width_for_medium) - 1;
        RemoteViews smallView = getPeopleTileViewHelper(
                tileWithMissedCallNotification).getViews();
        View smallResult = smallView.apply(mContext, null);

        // Show icon instead of name.
        assertEquals(View.GONE, smallResult.findViewById(R.id.name).getVisibility());
        assertEquals(View.VISIBLE,
                smallResult.findViewById(R.id.predefined_icon).getVisibility());
        assertEquals(smallResult.findViewById(R.id.predefined_icon).getContentDescription(),
                mContext.getString(
                        R.string.new_notification_text_content_description, NAME, MISSED_CALL));
        // Has person icon.
        assertEquals(View.VISIBLE, smallResult.findViewById(R.id.person_icon).getVisibility());
        // No messages count.
        assertEquals(View.GONE, smallResult.findViewById(R.id.messages_count).getVisibility());

        mWidth = getSizeInDp(R.dimen.required_width_for_large);
        mHeight = getSizeInDp(R.dimen.required_height_for_large);
        RemoteViews largeView = getPeopleTileViewHelper(tileWithMissedCallNotification).getViews();
        View largeResult = largeView.apply(mContext, null);

        name = (TextView) largeResult.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        assertEquals(View.GONE, largeResult.findViewById(R.id.subtext).getVisibility());
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.predefined_icon).getVisibility());
        // Has availability.
        assertEquals(View.GONE, largeResult.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        View personIcon = largeResult.findViewById(R.id.person_icon);
        assertEquals(View.VISIBLE, personIcon.getVisibility());
        // Has notification content.
        statusContent = (TextView) largeResult.findViewById(R.id.text_content);
        assertEquals(View.VISIBLE, statusContent.getVisibility());
        assertEquals(statusContent.getText(), MISSED_CALL);
        assertEquals(statusContent.getContentDescription(), mContext.getString(
                R.string.new_notification_text_content_description, NAME, MISSED_CALL));
        assertThat(statusContent.getMaxLines()).isEqualTo(2);
    }

    @Test
    public void testCreateRemoteViewsWithNotificationTemplate() {
        PeopleSpaceTile tileWithStatusAndNotification = PERSON_TILE.toBuilder()
                .setNotificationDataUri(null)
                .setStatuses(Arrays.asList(GAME_STATUS,
                        NEW_STORY_WITH_AVAILABILITY)).build();
        RemoteViews views = getPeopleTileViewHelper(
                tileWithStatusAndNotification).getViews();
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        assertEquals(View.GONE, result.findViewById(R.id.subtext).getVisibility());
        assertEquals(View.GONE, result.findViewById(R.id.predefined_icon).getVisibility());
        assertEquals(View.GONE, result.findViewById(R.id.scrim_layout).getVisibility());
        // Has availability.
        assertEquals(View.VISIBLE, result.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        assertEquals(View.VISIBLE, result.findViewById(R.id.person_icon).getVisibility());
        // Has notification content.
        TextView statusContent = (TextView) result.findViewById(R.id.text_content);
        assertEquals(View.VISIBLE, statusContent.getVisibility());
        assertEquals(statusContent.getText(), NOTIFICATION_CONTENT);
        assertEquals(statusContent.getContentDescription(), mContext.getString(
                R.string.new_notification_text_content_description, NAME, NOTIFICATION_CONTENT));
        assertThat(statusContent.getMaxLines()).isEqualTo(2);

        // Has a single message, no count shown.
        assertEquals(View.GONE, result.findViewById(R.id.messages_count).getVisibility());

        mWidth = getSizeInDp(R.dimen.required_width_for_medium) - 1;
        RemoteViews smallView = getPeopleTileViewHelper(
                tileWithStatusAndNotification).getViews();
        View smallResult = smallView.apply(mContext, null);

        // Show icon instead of name.
        assertEquals(View.GONE, smallResult.findViewById(R.id.name).getVisibility());
        assertEquals(View.VISIBLE,
                smallResult.findViewById(R.id.predefined_icon).getVisibility());
        assertEquals(smallResult.findViewById(R.id.predefined_icon).getContentDescription(),
                mContext.getString(R.string.new_notification_text_content_description, NAME,
                        NOTIFICATION_CONTENT));
        // Has person icon.
        assertEquals(View.VISIBLE,
                smallResult.findViewById(R.id.person_icon).getVisibility());

        // Has a single message, no count shown.
        assertEquals(View.GONE, smallResult.findViewById(R.id.messages_count).getVisibility());

        mWidth = getSizeInDp(R.dimen.required_width_for_large);
        mHeight = getSizeInDp(R.dimen.required_height_for_large);
        RemoteViews largeView = getPeopleTileViewHelper(
                tileWithStatusAndNotification).getViews();
        View largeResult = largeView.apply(mContext, null);

        name = (TextView) largeResult.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        assertEquals(View.GONE, largeResult.findViewById(R.id.subtext).getVisibility());
        assertEquals(View.GONE, largeResult.findViewById(R.id.predefined_icon).getVisibility());
        assertEquals(largeResult.findViewById(R.id.scrim_layout), null);
        // Has availability.
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        View personIcon = largeResult.findViewById(R.id.person_icon);
        assertEquals(View.VISIBLE, personIcon.getVisibility());
        // Has notification content.
        statusContent = (TextView) largeResult.findViewById(R.id.text_content);
        assertEquals(View.VISIBLE, statusContent.getVisibility());
        assertEquals(statusContent.getText(), NOTIFICATION_CONTENT);
        assertEquals(statusContent.getContentDescription(), mContext.getString(
                R.string.new_notification_text_content_description, NAME, NOTIFICATION_CONTENT));
        assertThat(statusContent.getMaxLines()).isEqualTo(2);
        // Has a single message, no count shown.
        assertEquals(View.GONE, largeResult.findViewById(R.id.messages_count).getVisibility());

    }

    @Test
    public void testCreateRemoteViewsWithNotificationWithSenderTemplate() {
        PeopleSpaceTile tileWithStatusAndNotification = PERSON_TILE_WITH_SENDER.toBuilder()
                .setNotificationDataUri(null)
                .setStatuses(Arrays.asList(GAME_STATUS,
                        NEW_STORY_WITH_AVAILABILITY)).build();
        RemoteViews views = getPeopleTileViewHelper(
                tileWithStatusAndNotification).getViews();
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        TextView subtext = (TextView) result.findViewById(R.id.subtext);
        assertEquals(View.VISIBLE, result.findViewById(R.id.subtext).getVisibility());
        assertEquals(subtext.getText(), SENDER);
        assertEquals(View.GONE, result.findViewById(R.id.predefined_icon).getVisibility());
        // Has availability.
        assertEquals(View.VISIBLE, result.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        assertEquals(View.VISIBLE, result.findViewById(R.id.person_icon).getVisibility());
        // Has notification content.
        TextView statusContent = (TextView) result.findViewById(R.id.text_content);
        assertEquals(View.VISIBLE, statusContent.getVisibility());
        assertEquals(statusContent.getText(), NOTIFICATION_CONTENT);
        assertEquals(statusContent.getContentDescription(), mContext.getString(
                R.string.new_notification_text_content_description, SENDER, NOTIFICATION_CONTENT));

        // Subtract one from lines because sender is included.
        assertThat(statusContent.getMaxLines()).isEqualTo(1);

        // Has a single message, no count shown.
        assertEquals(View.GONE, result.findViewById(R.id.messages_count).getVisibility());

        mWidth = getSizeInDp(R.dimen.required_width_for_medium) - 1;
        RemoteViews smallView = getPeopleTileViewHelper(
                tileWithStatusAndNotification).getViews();
        View smallResult = smallView.apply(mContext, null);

        // Show icon instead of name.
        assertEquals(View.GONE, smallResult.findViewById(R.id.name).getVisibility());
        assertEquals(View.VISIBLE,
                smallResult.findViewById(R.id.predefined_icon).getVisibility());
        assertEquals(smallResult.findViewById(R.id.predefined_icon).getContentDescription(),
                mContext.getString(
                        R.string.new_notification_text_content_description, SENDER,
                        NOTIFICATION_CONTENT));
        // Has person icon.
        assertEquals(View.VISIBLE,
                smallResult.findViewById(R.id.person_icon).getVisibility());

        // Has a single message, no count shown.
        assertEquals(View.GONE, smallResult.findViewById(R.id.messages_count).getVisibility());

        mWidth = getSizeInDp(R.dimen.required_width_for_large);
        mHeight = getSizeInDp(R.dimen.required_height_for_large);
        RemoteViews largeView = getPeopleTileViewHelper(
                tileWithStatusAndNotification).getViews();
        View largeResult = largeView.apply(mContext, null);

        name = (TextView) largeResult.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        subtext = (TextView) largeResult.findViewById(R.id.subtext);
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.subtext).getVisibility());
        assertEquals(subtext.getText(), SENDER);
        assertEquals(View.GONE, largeResult.findViewById(R.id.predefined_icon).getVisibility());
        // Has availability.
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        View personIcon = largeResult.findViewById(R.id.person_icon);
        assertEquals(View.VISIBLE, personIcon.getVisibility());
        // Has notification content.
        statusContent = (TextView) largeResult.findViewById(R.id.text_content);
        assertEquals(View.VISIBLE, statusContent.getVisibility());
        assertEquals(statusContent.getText(), NOTIFICATION_CONTENT);
        assertEquals(statusContent.getContentDescription(), mContext.getString(
                R.string.new_notification_text_content_description, SENDER, NOTIFICATION_CONTENT));

        // Subtract one from lines because sender is included.
        assertThat(statusContent.getMaxLines()).isEqualTo(1);

        // Has a single message, no count shown.
        assertEquals(View.GONE, largeResult.findViewById(R.id.messages_count).getVisibility());

    }

    @Test
    public void testCreateRemoteViewsWithNotificationTemplateTwoMessages() {
        PeopleSpaceTile tileWithStatusAndNotification = PERSON_TILE.toBuilder()
                .setNotificationDataUri(null)
                .setStatuses(Arrays.asList(GAME_STATUS,
                        NEW_STORY_WITH_AVAILABILITY))
                .setMessagesCount(2).build();
        RemoteViews views = getPeopleTileViewHelper(
                tileWithStatusAndNotification).getViews();
        View result = views.apply(mContext, null);

        TextView name = (TextView) result.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        assertEquals(View.GONE, result.findViewById(R.id.subtext).getVisibility());
        assertEquals(View.GONE, result.findViewById(R.id.predefined_icon).getVisibility());
        // Has availability.
        assertEquals(View.VISIBLE, result.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        assertEquals(View.VISIBLE, result.findViewById(R.id.person_icon).getVisibility());
        // Has notification content.
        TextView statusContent = (TextView) result.findViewById(R.id.text_content);
        assertEquals(View.VISIBLE, statusContent.getVisibility());
        assertEquals(statusContent.getText(), NOTIFICATION_CONTENT);
        assertThat(statusContent.getMaxLines()).isEqualTo(2);

        // Has two messages, show count.
        assertEquals(View.VISIBLE, result.findViewById(R.id.messages_count).getVisibility());

        mWidth = getSizeInDp(R.dimen.required_width_for_medium) - 1;
        RemoteViews smallView = getPeopleTileViewHelper(
                tileWithStatusAndNotification).getViews();
        View smallResult = smallView.apply(mContext, null);

        // Show icon instead of name.
        assertEquals(View.GONE, smallResult.findViewById(R.id.name).getVisibility());
        assertEquals(View.GONE,
                smallResult.findViewById(R.id.predefined_icon).getVisibility());
        // Has person icon.
        assertEquals(View.VISIBLE,
                smallResult.findViewById(R.id.person_icon).getVisibility());

        // Has two messages, show count.
        assertEquals(View.VISIBLE, smallResult.findViewById(R.id.messages_count).getVisibility());

        mWidth = getSizeInDp(R.dimen.required_width_for_large);
        mHeight = getSizeInDp(R.dimen.required_height_for_large);
        RemoteViews largeView = getPeopleTileViewHelper(
                tileWithStatusAndNotification).getViews();
        View largeResult = largeView.apply(mContext, null);

        name = (TextView) largeResult.findViewById(R.id.name);
        assertEquals(name.getText(), NAME);
        assertEquals(View.GONE, largeResult.findViewById(R.id.subtext).getVisibility());
        assertEquals(View.GONE, largeResult.findViewById(R.id.predefined_icon).getVisibility());
        // Has availability.
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.availability).getVisibility());
        // Has person icon.
        View personIcon = largeResult.findViewById(R.id.person_icon);
        assertEquals(View.VISIBLE, personIcon.getVisibility());
        // Has notification content.
        statusContent = (TextView) largeResult.findViewById(R.id.text_content);
        assertEquals(View.VISIBLE, statusContent.getVisibility());
        assertEquals(statusContent.getText(), NOTIFICATION_CONTENT);
        assertThat(statusContent.getMaxLines()).isEqualTo(2);

        // Has two messages, show count.
        assertEquals(View.VISIBLE, largeResult.findViewById(R.id.messages_count).getVisibility());
    }


    @Test
    public void testGetDoublePunctuationNoPunctuation() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoublePunctuation("test");

        assertThat(backgroundText).isNull();
    }

    @Test
    public void testGetDoublePunctuationSingleExclamation() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoublePunctuation("test!");

        assertThat(backgroundText).isNull();
    }

    @Test
    public void testGetDoublePunctuationSingleQuestion() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoublePunctuation("?test");

        assertThat(backgroundText).isNull();
    }

    @Test
    public void testGetDoublePunctuationSeparatedMarks() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoublePunctuation("test! right!");

        assertThat(backgroundText).isNull();
    }

    @Test
    public void testGetDoublePunctuationDoubleExclamation() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoublePunctuation("!!test");

        assertThat(backgroundText).isEqualTo("!");
    }

    @Test
    public void testGetDoublePunctuationDoubleQuestion() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoublePunctuation("test??");

        assertThat(backgroundText).isEqualTo("?");
    }

    @Test
    public void testGetDoublePunctuationMixed() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoublePunctuation("test?!");

        assertThat(backgroundText).isEqualTo("!?");
    }

    @Test
    public void testGetDoublePunctuationMixedInTheMiddle() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoublePunctuation(
                "test!? in the middle");

        assertThat(backgroundText).isEqualTo("!?");
    }

    @Test
    public void testGetDoublePunctuationMixedDifferentOrder() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoublePunctuation(
                "test!? in the middle");

        assertThat(backgroundText).isEqualTo("!?");
    }

    @Test
    public void testGetDoublePunctuationMultiple() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoublePunctuation(
                "test!?!!? in the middle");

        assertThat(backgroundText).isEqualTo("!?");
    }

    @Test
    public void testGetDoublePunctuationQuestionFirst() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoublePunctuation(
                "test?? in the middle!!");

        assertThat(backgroundText).isEqualTo("?");
    }

    @Test
    public void testGetDoublePunctuationExclamationFirst() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoublePunctuation(
                "test!! in the middle??");

        assertThat(backgroundText).isEqualTo("!");
    }

    @Test
    public void testGetDoubleEmojisNoEmojis() {
        CharSequence backgroundText = mPeopleTileViewHelper
                .getDoubleEmoji("This string has no emojis.");
        assertThat(backgroundText).isNull();
    }

    @Test
    public void testGetDoubleEmojisSingleEmoji() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoubleEmoji(
                "This string has one emoji " + EMOJI_JOY + " in the middle.");
        assertThat(backgroundText).isNull();
    }

    @Test
    public void testGetDoubleEmojisSingleEmojiThenTwoEmojis() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoubleEmoji(
                "This string has one emoji " + EMOJI_JOY + " in the middle, then two "
                        + EMOJI_BEAR + EMOJI_BEAR);
        assertEquals(backgroundText, EMOJI_BEAR);
    }

    @Test
    public void testGetDoubleEmojisTwoEmojisWithModifier() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoubleEmoji(
                "Yes! " + EMOJI_THUMBS_UP_BROWN_SKIN + EMOJI_THUMBS_UP_BROWN_SKIN + " Sure.");
        assertEquals(backgroundText, EMOJI_THUMBS_UP_BROWN_SKIN);
    }

    @Test
    public void testGetDoubleEmojisTwoFlagEmojis() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoubleEmoji(
                "Let's travel to " + EMOJI_BR_FLAG + EMOJI_BR_FLAG + " next year.");
        assertEquals(backgroundText, EMOJI_BR_FLAG);
    }

    @Test
    public void testGetDoubleEmojiTwoBears() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoubleEmoji(
                EMOJI_BEAR.toString() + EMOJI_BEAR.toString() + "bears!");
        assertEquals(backgroundText, EMOJI_BEAR);
    }

    @Test
    public void testGetDoubleEmojiTwoEmojisTwice() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoubleEmoji(
                "Two sets of two emojis: " + EMOJI_FAMILY + EMOJI_FAMILY + EMOJI_BEAR + EMOJI_BEAR);
        assertEquals(backgroundText, EMOJI_FAMILY);
    }

    @Test
    public void testGetDoubleEmojiTwoEmojisSeparated() {
        CharSequence backgroundText = mPeopleTileViewHelper.getDoubleEmoji(
                "Two emojis " + EMOJI_BEAR + " separated " + EMOJI_BEAR + ".");
        assertThat(backgroundText).isNull();
    }

    private int getSizeInDp(int dimenResourceId) {
        return (int) (mContext.getResources().getDimension(dimenResourceId)
                / mContext.getResources().getDisplayMetrics().density);
    }

    private PeopleTileViewHelper getPeopleTileViewHelper(
            PeopleSpaceTile tile) {
        return new PeopleTileViewHelper(mContext, tile, 0, mWidth, mHeight,
                new PeopleTileKey(tile.getId(), 0, tile.getPackageName()));
    }

    private void assertResourcesEqual(int expected, int actual) {
        assertThat(getResourceName(actual)).isEqualTo(getResourceName(expected));
    }

    private void assertResourcesNotEqual(int expected, int actual) {
        assertThat(getResourceName(actual)).isNotEqualTo(getResourceName(expected));
    }

    private String getResourceName(int resId) {
        Resources resources = mContext.getResources();
        try {
            return resources.getResourceEntryName(resId);
        } catch (Resources.NotFoundException e) {
            return String.valueOf(resId);
        }
    }
}
