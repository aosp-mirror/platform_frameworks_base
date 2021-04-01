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
import static android.app.people.ConversationStatus.ACTIVITY_AUDIO;
import static android.app.people.ConversationStatus.ACTIVITY_BIRTHDAY;
import static android.app.people.ConversationStatus.ACTIVITY_GAME;
import static android.app.people.ConversationStatus.ACTIVITY_LOCATION;
import static android.app.people.ConversationStatus.ACTIVITY_NEW_STORY;
import static android.app.people.ConversationStatus.ACTIVITY_UPCOMING_BIRTHDAY;
import static android.app.people.ConversationStatus.ACTIVITY_VIDEO;
import static android.app.people.ConversationStatus.AVAILABILITY_AVAILABLE;
import static android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT;
import static android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH;
import static android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT;
import static android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH;

import static com.android.systemui.people.PeopleSpaceUtils.convertDrawableToBitmap;
import static com.android.systemui.people.PeopleSpaceUtils.getUserId;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.app.people.ConversationStatus;
import android.app.people.PeopleSpaceTile;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.IconDrawableFactory;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.people.widget.LaunchConversationActivity;
import com.android.systemui.people.widget.PeopleSpaceWidgetProvider;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class PeopleTileViewHelper {
    /** Turns on debugging information about People Space. */
    public static final boolean DEBUG = true;
    private static final String TAG = "PeopleTileView";

    public static final int LAYOUT_SMALL = 0;
    public static final int LAYOUT_MEDIUM = 1;
    public static final int LAYOUT_LARGE = 2;

    private static final int MIN_CONTENT_MAX_LINES = 2;

    private static final int FIXED_HEIGHT_DIMENS_FOR_LARGE_CONTENT = 14 + 12 + 4 + 16;
    private static final int FIXED_HEIGHT_DIMENS_FOR_MEDIUM_CONTENT = 8 + 4 + 4 + 8;
    private static final int FIXED_HEIGHT_DIMENS_FOR_SMALL = 6 + 4 + 8;
    private static final int FIXED_WIDTH_DIMENS_FOR_SMALL = 4 + 4;

    private static final int MESSAGES_COUNT_OVERFLOW = 7;

    private static final Pattern DOUBLE_EXCLAMATION_PATTERN = Pattern.compile("[!][!]+");
    private static final Pattern DOUBLE_QUESTION_PATTERN = Pattern.compile("[?][?]+");
    private static final Pattern ANY_DOUBLE_MARK_PATTERN = Pattern.compile("[!?][!?]+");
    private static final Pattern MIXED_MARK_PATTERN = Pattern.compile("![?].*|.*[?]!");

    public static final String EMPTY_STRING = "";

    private Context mContext;
    private PeopleSpaceTile mTile;
    private float mDensity;
    private int mAppWidgetId;
    private int mWidth;
    private int mHeight;
    private int mLayoutSize;

    private Locale mLocale;
    private NumberFormat mIntegerFormat;

    PeopleTileViewHelper(Context context, PeopleSpaceTile tile,
            int appWidgetId, Bundle options) {
        mContext = context;
        mTile = tile;
        mAppWidgetId = appWidgetId;
        mDensity = mContext.getResources().getDisplayMetrics().density;
        int display = mContext.getResources().getConfiguration().orientation;
        mWidth = display == Configuration.ORIENTATION_PORTRAIT
                ? options.getInt(OPTION_APPWIDGET_MIN_WIDTH,
                getSizeInDp(R.dimen.default_width)) : options.getInt(
                OPTION_APPWIDGET_MAX_WIDTH,
                getSizeInDp(R.dimen.default_width));
        mHeight = display == Configuration.ORIENTATION_PORTRAIT ? options.getInt(
                OPTION_APPWIDGET_MAX_HEIGHT,
                getSizeInDp(R.dimen.default_height))
                : options.getInt(OPTION_APPWIDGET_MIN_HEIGHT,
                        getSizeInDp(R.dimen.default_height));
        mLayoutSize = getLayoutSize();
    }

    public RemoteViews getViews() {
        RemoteViews viewsForTile = getViewForTile();
        int maxAvatarSize = getMaxAvatarSize(viewsForTile);
        RemoteViews views = setCommonRemoteViewsFields(viewsForTile, maxAvatarSize);
        return setLaunchIntents(views);
    }

    /**
     * The prioritization for the {@code mTile} content is missed calls, followed by notification
     * content, then birthdays, then the most recent status, and finally last interaction.
     */
    private RemoteViews getViewForTile() {
        if (Objects.equals(mTile.getNotificationCategory(), CATEGORY_MISSED_CALL)) {
            if (DEBUG) Log.d(TAG, "Create missed call view");
            return createMissedCallRemoteViews();
        }

        if (mTile.getNotificationKey() != null) {
            if (DEBUG) Log.d(TAG, "Create notification view");
            return createNotificationRemoteViews();
        }

        // TODO: Add sorting when we expose timestamp of statuses.
        List<ConversationStatus> statusesForEntireView =
                mTile.getStatuses() == null ? Arrays.asList() : mTile.getStatuses().stream().filter(
                        c -> isStatusValidForEntireStatusView(c)).collect(Collectors.toList());
        ConversationStatus birthdayStatus = getBirthdayStatus(statusesForEntireView);
        if (birthdayStatus != null) {
            if (DEBUG) Log.d(TAG, "Create birthday view");
            return createStatusRemoteViews(birthdayStatus);
        }

        if (!statusesForEntireView.isEmpty()) {
            if (DEBUG) {
                Log.d(TAG,
                        "Create status view for: " + statusesForEntireView.get(0).getActivity());
            }
            return createStatusRemoteViews(statusesForEntireView.get(0));
        }

        return createLastInteractionRemoteViews();
    }

    private void setMaxLines(RemoteViews views) {
        int textSize = mLayoutSize == LAYOUT_LARGE ? getSizeInDp(
                R.dimen.content_text_size_for_medium)
                : getSizeInDp(R.dimen.content_text_size_for_medium);
        int lineHeight = getLineHeight(textSize);
        int notificationContentHeight = getContentHeightForLayout(lineHeight);
        int maxAdaptiveLines = Math.floorDiv(notificationContentHeight, lineHeight);
        int maxLines = Math.max(MIN_CONTENT_MAX_LINES, maxAdaptiveLines);
        views.setInt(R.id.text_content, "setMaxLines", maxLines);
    }

    private int getLineHeight(int textSize) {
        try {
            TextView text = new TextView(mContext);
            text.setTextSize(textSize);
            int lineHeight = (int) (text.getLineHeight() / mDensity);
            return lineHeight;
        } catch (Exception e) {
            Log.e(TAG, "Could not create text view: " + e);
            return getSizeInDp(
                    R.dimen.content_text_size_for_medium);
        }
    }

    private int getSizeInDp(int dimenResourceId) {
        return getSizeInDp(mContext, dimenResourceId, mDensity);
    }

    public static int getSizeInDp(Context context, int dimenResourceId, float density) {
        return (int) (context.getResources().getDimension(dimenResourceId) / density);
    }

    private int getContentHeightForLayout(int lineHeight) {
        switch (mLayoutSize) {
            case LAYOUT_MEDIUM:
                return mHeight - (lineHeight + FIXED_HEIGHT_DIMENS_FOR_MEDIUM_CONTENT);
            case LAYOUT_LARGE:
                return mHeight - (getSizeInDp(
                        R.dimen.max_people_avatar_size_for_large_content) + lineHeight
                        + FIXED_HEIGHT_DIMENS_FOR_LARGE_CONTENT);
            default:
                return -1;
        }
    }

    /** Calculates the best layout relative to the size in {@code options}. */
    private int getLayoutSize() {
        if (mHeight >= getSizeInDp(R.dimen.required_width_for_large)
                && mWidth >= getSizeInDp(R.dimen.required_width_for_large)) {
            if (DEBUG) Log.d(TAG, "Large view for mWidth: " + mWidth + " mHeight: " + mHeight);
            return LAYOUT_LARGE;
        }
        // Small layout used below a certain minimum mWidth with any mHeight.
        if (mWidth >= getSizeInDp(R.dimen.required_width_for_medium)) {
            if (DEBUG) Log.d(TAG, "Medium view for mWidth: " + mWidth + " mHeight: " + mHeight);
            return LAYOUT_MEDIUM;
        }
        // Small layout can always handle our minimum mWidth and mHeight for our widget.
        if (DEBUG) Log.d(TAG, "Small view for mWidth: " + mWidth + " mHeight: " + mHeight);
        return LAYOUT_SMALL;
    }

    /** Returns the max avatar size for {@code views} under the current {@code options}. */
    private int getMaxAvatarSize(RemoteViews views) {
        int layoutId = views.getLayoutId();
        int avatarSize = getSizeInDp(R.dimen.avatar_size_for_medium);
        if (layoutId == R.layout.people_tile_medium_empty) {
            return getSizeInDp(
                    R.dimen.max_people_avatar_size_for_large_content);
        }
        if (layoutId == R.layout.people_tile_medium_with_content) {
            return getSizeInDp(R.dimen.avatar_size_for_medium);
        }

        // Calculate adaptive avatar size for remaining layouts.
        if (layoutId == R.layout.people_tile_small) {
            int avatarHeightSpace = mHeight - (FIXED_HEIGHT_DIMENS_FOR_SMALL + Math.max(18,
                    getLineHeight(getSizeInDp(
                            R.dimen.name_text_size_for_small))));
            int avatarWidthSpace = mWidth - FIXED_WIDTH_DIMENS_FOR_SMALL;
            avatarSize = Math.min(avatarHeightSpace, avatarWidthSpace);
        }

        if (layoutId == R.layout.people_tile_large_with_content) {
            avatarSize = mHeight - (FIXED_HEIGHT_DIMENS_FOR_LARGE_CONTENT + (getLineHeight(
                    getSizeInDp(R.dimen.content_text_size_for_large))
                    * 3));
            return Math.min(avatarSize, getSizeInDp(
                    R.dimen.max_people_avatar_size_for_large_content));
        }

        if (layoutId == R.layout.people_tile_large_empty) {
            int avatarHeightSpace = mHeight - (14 + 14 + getLineHeight(
                    getSizeInDp(R.dimen.name_text_size_for_large))
                    + getLineHeight(
                    getSizeInDp(R.dimen.content_text_size_for_large))
                    + 16 + 10 + 14);
            int avatarWidthSpace = mWidth - (14 + 14);
            avatarSize = Math.min(avatarHeightSpace, avatarWidthSpace);
        }
        return Math.min(avatarSize,
                getSizeInDp(R.dimen.max_people_avatar_size));
    }

    private RemoteViews setCommonRemoteViewsFields(RemoteViews views,
            int maxAvatarSize) {
        try {
            boolean isAvailable =
                    mTile.getStatuses() != null && mTile.getStatuses().stream().anyMatch(
                            c -> c.getAvailability() == AVAILABILITY_AVAILABLE);
            if (isAvailable) {
                views.setViewVisibility(R.id.availability, View.VISIBLE);
            } else {
                views.setViewVisibility(R.id.availability, View.GONE);
            }

            views.setTextViewText(R.id.name, mTile.getUserName().toString());
            views.setBoolean(R.id.image, "setClipToOutline", true);
            views.setImageViewBitmap(R.id.person_icon,
                    getPersonIconBitmap(mContext, mTile, maxAvatarSize));
            return views;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set common fields: " + e);
        }
        return views;
    }

    private RemoteViews setLaunchIntents(RemoteViews views) {
        try {
            Intent activityIntent = new Intent(mContext, LaunchConversationActivity.class);
            activityIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_HISTORY
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            activityIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_TILE_ID, mTile.getId());
            activityIntent.putExtra(
                    PeopleSpaceWidgetProvider.EXTRA_PACKAGE_NAME, mTile.getPackageName());
            activityIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_USER_HANDLE,
                    mTile.getUserHandle());
            activityIntent.putExtra(
                    PeopleSpaceWidgetProvider.EXTRA_NOTIFICATION_KEY, mTile.getNotificationKey());
            views.setOnClickPendingIntent(R.id.item, PendingIntent.getActivity(
                    mContext,
                    mAppWidgetId,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE));
            return views;
        } catch (Exception e) {
            Log.e(TAG, "Failed to add launch intents: " + e);
        }

        return views;
    }

    private RemoteViews createMissedCallRemoteViews() {
        RemoteViews views = getViewForContentLayout();
        views.setViewVisibility(R.id.predefined_icon, View.VISIBLE);
        setMaxLines(views);
        views.setTextViewText(R.id.text_content, mTile.getNotificationContent());
        views.setImageViewResource(R.id.predefined_icon, R.drawable.ic_phone_missed);
        return views;
    }

    private RemoteViews createNotificationRemoteViews() {
        RemoteViews views = getViewForContentLayout();
        Uri image = mTile.getNotificationDataUri();
        if (image != null) {
            // TODO: Use NotificationInlineImageCache
            views.setImageViewUri(R.id.image, image);
            views.setViewVisibility(R.id.image, View.VISIBLE);
            views.setViewVisibility(R.id.text_content, View.GONE);
            views.setImageViewResource(R.id.predefined_icon, R.drawable.ic_photo_camera);
        } else {
            setMaxLines(views);
            CharSequence content = mTile.getNotificationContent();
            views = setPunctuationRemoteViewsFields(views, content);
            TypedValue typedValue = new TypedValue();
            mContext.getTheme().resolveAttribute(android.R.attr.textColorPrimary, typedValue, true);
            int primaryTextColor = mContext.getColor(typedValue.resourceId);
            views.setInt(R.id.text_content, "setTextColor", primaryTextColor);
            views.setTextViewText(R.id.text_content, mTile.getNotificationContent());
            views.setViewVisibility(R.id.image, View.GONE);
            views.setImageViewResource(R.id.predefined_icon, R.drawable.ic_message);
        }
        if (mTile.getMessagesCount() > 1) {
            views.setViewVisibility(R.id.messages_count, View.VISIBLE);
            views.setTextViewText(R.id.messages_count,
                    getMessagesCountText(mTile.getMessagesCount()));
            if (mLayoutSize == LAYOUT_SMALL) {
                views.setViewVisibility(R.id.predefined_icon, View.GONE);
            }
        }
        // TODO: Set subtext as Group Sender name once storing the name in PeopleSpaceTile and
        //  subtract 1 from maxLines when present.
        views.setViewVisibility(R.id.subtext, View.GONE);
        return views;
    }

    // Some messaging apps only include up to 7 messages in their notifications.
    private String getMessagesCountText(int count) {
        if (count >= MESSAGES_COUNT_OVERFLOW) {
            return mContext.getResources().getString(
                    R.string.messages_count_overflow_indicator, MESSAGES_COUNT_OVERFLOW);
        }

        // Cache the locale-appropriate NumberFormat.  Configuration locale is guaranteed
        // non-null, so the first time this is called we will always get the appropriate
        // NumberFormat, then never regenerate it unless the locale changes on the fly.
        final Locale curLocale = mContext.getResources().getConfiguration().getLocales().get(0);
        if (!curLocale.equals(mLocale)) {
            mLocale = curLocale;
            mIntegerFormat = NumberFormat.getIntegerInstance(curLocale);
        }
        return mIntegerFormat.format(count);
    }

    private RemoteViews createStatusRemoteViews(ConversationStatus status) {
        RemoteViews views = getViewForContentLayout();
        CharSequence statusText = status.getDescription();
        if (TextUtils.isEmpty(statusText)) {
            statusText = getStatusTextByType(status.getActivity());
        }
        views.setViewVisibility(R.id.predefined_icon, View.VISIBLE);
        setMaxLines(views);
        // Secondary text color for statuses.
        TypedValue typedValue = new TypedValue();
        mContext.getTheme().resolveAttribute(android.R.attr.textColorSecondary, typedValue, true);
        int secondaryTextColor = mContext.getColor(typedValue.resourceId);
        views.setInt(R.id.text_content, "setTextColor", secondaryTextColor);
        views.setTextViewText(R.id.text_content, statusText);

        Icon statusIcon = status.getIcon();
        if (statusIcon != null) {
            // No multi-line text with status images on medium layout.
            views.setViewVisibility(R.id.text_content, View.GONE);
            // Show 1-line subtext on large layout with status images.
            if (mLayoutSize == LAYOUT_LARGE) {
                views.setViewVisibility(R.id.subtext, View.VISIBLE);
                views.setTextViewText(R.id.subtext, statusText);
            }
            views.setViewVisibility(R.id.image, View.VISIBLE);
            views.setImageViewIcon(R.id.image, statusIcon);
        } else {
            views.setViewVisibility(R.id.image, View.GONE);
        }
        // TODO: Set status pre-defined icons
        views.setImageViewResource(R.id.predefined_icon, R.drawable.ic_person);
        return views;
    }

    @Nullable
    private ConversationStatus getBirthdayStatus(
            List<ConversationStatus> statuses) {
        Optional<ConversationStatus> birthdayStatus = statuses.stream().filter(
                c -> c.getActivity() == ACTIVITY_BIRTHDAY).findFirst();
        if (birthdayStatus.isPresent()) {
            return birthdayStatus.get();
        }
        if (!TextUtils.isEmpty(mTile.getBirthdayText())) {
            return new ConversationStatus.Builder(mTile.getId(), ACTIVITY_BIRTHDAY).build();
        }

        return null;
    }

    /**
     * Returns whether a {@code status} should have its own entire templated view.
     *
     * <p>A status may still be shown on the view (for example, as a new story ring) even if it's
     * not valid to compose an entire view.
     */
    private boolean isStatusValidForEntireStatusView(ConversationStatus status) {
        switch (status.getActivity()) {
            // Birthday & Anniversary don't require text provided or icon provided.
            case ACTIVITY_BIRTHDAY:
            case ACTIVITY_ANNIVERSARY:
                return true;
            default:
                // For future birthday, location, new story, video, music, game, and other, the
                // app must provide either text or an icon.
                return !TextUtils.isEmpty(status.getDescription())
                        || status.getIcon() != null;
        }
    }

    private String getStatusTextByType(int activity) {
        switch (activity) {
            case ACTIVITY_BIRTHDAY:
                return mContext.getString(R.string.birthday_status);
            case ACTIVITY_UPCOMING_BIRTHDAY:
                return mContext.getString(R.string.upcoming_birthday_status);
            case ACTIVITY_ANNIVERSARY:
                return mContext.getString(R.string.anniversary_status);
            case ACTIVITY_LOCATION:
                return mContext.getString(R.string.location_status);
            case ACTIVITY_NEW_STORY:
                return mContext.getString(R.string.new_story_status);
            case ACTIVITY_VIDEO:
                return mContext.getString(R.string.video_status);
            case ACTIVITY_AUDIO:
                return mContext.getString(R.string.audio_status);
            case ACTIVITY_GAME:
                return mContext.getString(R.string.game_status);
            default:
                return EMPTY_STRING;
        }
    }

    private RemoteViews setPunctuationRemoteViewsFields(
            RemoteViews views, CharSequence content) {
        String punctuation = getBackgroundTextFromMessage(content.toString());
        int visibility = View.GONE;
        if (punctuation != null) {
            visibility = View.VISIBLE;
        }
        views.setTextViewText(R.id.punctuation1, punctuation);
        views.setTextViewText(R.id.punctuation2, punctuation);
        views.setTextViewText(R.id.punctuation3, punctuation);
        views.setTextViewText(R.id.punctuation4, punctuation);
        views.setTextViewText(R.id.punctuation5, punctuation);
        views.setTextViewText(R.id.punctuation6, punctuation);

        views.setViewVisibility(R.id.punctuation1, visibility);
        views.setViewVisibility(R.id.punctuation2, visibility);
        views.setViewVisibility(R.id.punctuation3, visibility);
        views.setViewVisibility(R.id.punctuation4, visibility);
        views.setViewVisibility(R.id.punctuation5, visibility);
        views.setViewVisibility(R.id.punctuation6, visibility);

        return views;
    }

    /** Gets character for mTile background decoration based on notification content. */
    @VisibleForTesting
    String getBackgroundTextFromMessage(String message) {
        if (!ANY_DOUBLE_MARK_PATTERN.matcher(message).find()) {
            return null;
        }
        if (MIXED_MARK_PATTERN.matcher(message).find()) {
            return "!?";
        }
        Matcher doubleQuestionMatcher = DOUBLE_QUESTION_PATTERN.matcher(message);
        if (!doubleQuestionMatcher.find()) {
            return "!";
        }
        Matcher doubleExclamationMatcher = DOUBLE_EXCLAMATION_PATTERN.matcher(message);
        if (!doubleExclamationMatcher.find()) {
            return "?";
        }
        // If we have both "!!" and "??", return the one that comes first.
        if (doubleQuestionMatcher.start() < doubleExclamationMatcher.start()) {
            return "?";
        }
        return "!";
    }

    private RemoteViews getViewForContentLayout() {
        RemoteViews views = new RemoteViews(mContext.getPackageName(),
                getLayoutForContent());
        if (mLayoutSize == LAYOUT_SMALL) {
            views.setViewVisibility(R.id.predefined_icon, View.VISIBLE);
            views.setViewVisibility(R.id.name, View.GONE);
        } else {
            views.setViewVisibility(R.id.predefined_icon, View.GONE);
            views.setViewVisibility(R.id.name, View.VISIBLE);
            views.setViewVisibility(R.id.text_content, View.VISIBLE);
            views.setViewVisibility(R.id.subtext, View.GONE);
        }
        return views;
    }

    private RemoteViews createLastInteractionRemoteViews() {
        RemoteViews views = new RemoteViews(mContext.getPackageName(), getEmptyLayout());
        if (mLayoutSize == LAYOUT_SMALL) {
            views.setViewVisibility(R.id.name, View.VISIBLE);
            views.setViewVisibility(R.id.predefined_icon, View.GONE);
        }
        String status = PeopleSpaceUtils.getLastInteractionString(mContext,
                mTile.getLastInteractionTimestamp());
        views.setTextViewText(R.id.last_interaction, status);
        return views;
    }

    private int getEmptyLayout() {
        switch (mLayoutSize) {
            case LAYOUT_MEDIUM:
                return R.layout.people_tile_medium_empty;
            case LAYOUT_LARGE:
                return R.layout.people_tile_large_empty;
            case LAYOUT_SMALL:
            default:
                return R.layout.people_tile_small;
        }
    }

    private int getLayoutForContent() {
        switch (mLayoutSize) {
            case LAYOUT_MEDIUM:
                return R.layout.people_tile_medium_with_content;
            case LAYOUT_LARGE:
                return R.layout.people_tile_large_with_content;
            case LAYOUT_SMALL:
            default:
                return R.layout.people_tile_small;
        }
    }

    /** Returns a bitmap with the user icon and package icon. */
    public static Bitmap getPersonIconBitmap(
            Context context, PeopleSpaceTile tile, int maxAvatarSize) {
        boolean hasNewStory =
                tile.getStatuses() != null && tile.getStatuses().stream().anyMatch(
                        c -> c.getActivity() == ACTIVITY_NEW_STORY);

        Icon icon = tile.getUserIcon();
        PeopleStoryIconFactory storyIcon = new PeopleStoryIconFactory(context,
                context.getPackageManager(),
                IconDrawableFactory.newInstance(context, false),
                maxAvatarSize);
        Drawable drawable = icon.loadDrawable(context);
        Drawable personDrawable = storyIcon.getPeopleTileDrawable(drawable,
                tile.getPackageName(), getUserId(tile), tile.isImportantConversation(),
                hasNewStory);
        return convertDrawableToBitmap(personDrawable);
    }
}
