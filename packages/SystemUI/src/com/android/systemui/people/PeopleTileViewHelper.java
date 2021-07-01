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
import static android.appwidget.AppWidgetManager.OPTION_APPWIDGET_SIZES;
import static android.util.TypedValue.COMPLEX_UNIT_DIP;
import static android.util.TypedValue.COMPLEX_UNIT_PX;

import static com.android.systemui.people.PeopleSpaceUtils.STARRED_CONTACT;
import static com.android.systemui.people.PeopleSpaceUtils.VALID_CONTACT;
import static com.android.systemui.people.PeopleSpaceUtils.convertDrawableToBitmap;
import static com.android.systemui.people.PeopleSpaceUtils.getUserId;

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.app.people.ConversationStatus;
import android.app.people.PeopleSpaceTile;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.graphics.text.LineBreaker;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.IconDrawableFactory;
import android.util.Log;
import android.util.Pair;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;

import androidx.annotation.DimenRes;
import androidx.annotation.Px;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.core.math.MathUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.launcher3.icons.FastBitmapDrawable;
import com.android.systemui.R;
import com.android.systemui.people.widget.LaunchConversationActivity;
import com.android.systemui.people.widget.PeopleSpaceWidgetProvider;
import com.android.systemui.people.widget.PeopleTileKey;

import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Functions that help creating the People tile layouts. */
public class PeopleTileViewHelper {
    /** Turns on debugging information about People Space. */
    public static final boolean DEBUG = true;
    private static final String TAG = "PeopleTileView";

    private static final int DAYS_IN_A_WEEK = 7;
    private static final int ONE_DAY = 1;

    public static final int LAYOUT_SMALL = 0;
    public static final int LAYOUT_MEDIUM = 1;
    public static final int LAYOUT_LARGE = 2;

    private static final int MIN_CONTENT_MAX_LINES = 2;
    private static final int NAME_MAX_LINES_WITHOUT_LAST_INTERACTION = 3;
    private static final int NAME_MAX_LINES_WITH_LAST_INTERACTION = 1;

    private static final int FIXED_HEIGHT_DIMENS_FOR_LARGE_NOTIF_CONTENT = 16 + 22 + 8 + 16;
    private static final int FIXED_HEIGHT_DIMENS_FOR_LARGE_STATUS_CONTENT = 16 + 16 + 24 + 4 + 16;
    private static final int MIN_MEDIUM_VERTICAL_PADDING = 4;
    private static final int MAX_MEDIUM_PADDING = 16;
    private static final int FIXED_HEIGHT_DIMENS_FOR_MEDIUM_CONTENT_BEFORE_PADDING = 8 + 4;
    private static final int FIXED_HEIGHT_DIMENS_FOR_SMALL = 6 + 4 + 8;
    private static final int FIXED_WIDTH_DIMENS_FOR_SMALL = 4 + 4;

    private static final int MESSAGES_COUNT_OVERFLOW = 6;

    private static final CharSequence EMOJI_CAKE = "\ud83c\udf82";

    private static final Pattern DOUBLE_EXCLAMATION_PATTERN = Pattern.compile("[!][!]+");
    private static final Pattern DOUBLE_QUESTION_PATTERN = Pattern.compile("[?][?]+");
    private static final Pattern ANY_DOUBLE_MARK_PATTERN = Pattern.compile("[!?][!?]+");
    private static final Pattern MIXED_MARK_PATTERN = Pattern.compile("![?].*|.*[?]!");

    // This regex can be used to match Unicode emoji characters and character sequences. It's from
    // the official Unicode site (https://unicode.org/reports/tr51/#EBNF_and_Regex) with minor
    // changes to fit our needs. It should be updated once new emoji categories are added.
    //
    // Emoji categories that can be matched by this regex:
    // - Country flags. "\p{RI}\p{RI}" matches country flags since they always consist of 2 Unicode
    //   scalars.
    // - Single-Character Emoji. "\p{Emoji}" matches Single-Character Emojis.
    // - Emoji with modifiers. E.g. Emojis with different skin tones. "\p{Emoji}\p{EMod}" matches
    //   them.
    // - Emoji Presentation. Those are characters which can normally be drawn as either text or as
    //   Emoji. "\p{Emoji}\x{FE0F}" matches them.
    // - Emoji Keycap. E.g. Emojis for number 0 to 9. "\p{Emoji}\x{FE0F}\x{20E3}" matches them.
    // - Emoji tag sequence. "\p{Emoji}[\x{E0020}-\x{E007E}]+\x{E007F}" matches them.
    // - Emoji Zero-Width Joiner (ZWJ) Sequence. A ZWJ emoji is actually multiple emojis joined by
    //   the jointer "0x200D".
    //
    // Note: since "\p{Emoji}" also matches some ASCII characters like digits 0-9, we use
    // "\p{Emoji}&&\p{So}" to exclude them. This is the change we made from the official emoji
    // regex.
    private static final String UNICODE_EMOJI_REGEX =
            "\\p{RI}\\p{RI}|"
                    + "("
                    + "\\p{Emoji}(\\p{EMod}|\\x{FE0F}\\x{20E3}?|[\\x{E0020}-\\x{E007E}]+\\x{E007F})"
                    + "|[\\p{Emoji}&&\\p{So}]"
                    + ")"
                    + "("
                    + "\\x{200D}"
                    + "\\p{Emoji}(\\p{EMod}|\\x{FE0F}\\x{20E3}?|[\\x{E0020}-\\x{E007E}]+\\x{E007F})"
                    + "?)*";

    private static final Pattern EMOJI_PATTERN = Pattern.compile(UNICODE_EMOJI_REGEX);

    public static final String EMPTY_STRING = "";

    private int mMediumVerticalPadding;

    private Context mContext;
    @Nullable
    private PeopleSpaceTile mTile;
    private PeopleTileKey mKey;
    private float mDensity;
    private int mAppWidgetId;
    private int mWidth;
    private int mHeight;
    private int mLayoutSize;

    private Locale mLocale;
    private NumberFormat mIntegerFormat;

    PeopleTileViewHelper(Context context, @Nullable PeopleSpaceTile tile,
            int appWidgetId, int width, int height, PeopleTileKey key) {
        mContext = context;
        mTile = tile;
        mKey = key;
        mAppWidgetId = appWidgetId;
        mDensity = mContext.getResources().getDisplayMetrics().density;
        mWidth = width;
        mHeight = height;
        mLayoutSize = getLayoutSize();
    }

    /**
     * Creates a {@link RemoteViews} for the specified arguments. The RemoteViews will support all
     * the sizes present in {@code options.}.
     */
    public static RemoteViews createRemoteViews(Context context, @Nullable PeopleSpaceTile tile,
            int appWidgetId, Bundle options, PeopleTileKey key) {
        List<SizeF> widgetSizes = getWidgetSizes(context, options);
        Map<SizeF, RemoteViews> sizeToRemoteView =
                widgetSizes
                        .stream()
                        .distinct()
                        .collect(Collectors.toMap(
                                Function.identity(),
                                size -> new PeopleTileViewHelper(
                                        context, tile, appWidgetId,
                                        (int) size.getWidth(),
                                        (int) size.getHeight(),
                                        key)
                                        .getViews()));
        return new RemoteViews(sizeToRemoteView);
    }

    private static List<SizeF> getWidgetSizes(Context context, Bundle options) {
        float density = context.getResources().getDisplayMetrics().density;
        List<SizeF> widgetSizes = options.getParcelableArrayList(OPTION_APPWIDGET_SIZES);
        // If the full list of sizes was provided in the options bundle, use that.
        if (widgetSizes != null && !widgetSizes.isEmpty()) return widgetSizes;

        // Otherwise, create a list using the portrait/landscape sizes.
        int defaultWidth = getSizeInDp(context, R.dimen.default_width, density);
        int defaultHeight =  getSizeInDp(context, R.dimen.default_height, density);
        widgetSizes = new ArrayList<>(2);

        int portraitWidth = options.getInt(OPTION_APPWIDGET_MIN_WIDTH, defaultWidth);
        int portraitHeight = options.getInt(OPTION_APPWIDGET_MAX_HEIGHT, defaultHeight);
        widgetSizes.add(new SizeF(portraitWidth, portraitHeight));

        int landscapeWidth = options.getInt(OPTION_APPWIDGET_MAX_WIDTH, defaultWidth);
        int landscapeHeight = options.getInt(OPTION_APPWIDGET_MIN_HEIGHT, defaultHeight);
        widgetSizes.add(new SizeF(landscapeWidth, landscapeHeight));

        return widgetSizes;
    }

    @VisibleForTesting
    RemoteViews getViews() {
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
        if (DEBUG) Log.d(TAG, "Creating view for tile key: " + mKey.toString());
        if (mTile == null || mTile.isPackageSuspended() || mTile.isUserQuieted()) {
            if (DEBUG) Log.d(TAG, "Create suppressed view: " + mTile);
            return createSuppressedView();
        }

        if (isDndBlockingTileData(mTile)) {
            if (DEBUG) Log.d(TAG, "Create dnd view");
            return createDndRemoteViews().mRemoteViews;
        }

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
            ConversationStatus mostRecentlyStartedStatus = statusesForEntireView.stream().max(
                    Comparator.comparing(s -> s.getStartTimeMillis())).get();
            return createStatusRemoteViews(mostRecentlyStartedStatus);
        }

        return createLastInteractionRemoteViews();
    }

    private static boolean isDndBlockingTileData(@Nullable PeopleSpaceTile tile) {
        if (tile == null) return false;

        int notificationPolicyState = tile.getNotificationPolicyState();
        if ((notificationPolicyState & PeopleSpaceTile.SHOW_CONVERSATIONS) != 0) {
            // Not in DND, or all conversations
            if (DEBUG) Log.d(TAG, "Tile can show all data: " + tile.getUserName());
            return false;
        }
        if ((notificationPolicyState & PeopleSpaceTile.SHOW_IMPORTANT_CONVERSATIONS) != 0
                && tile.isImportantConversation()) {
            if (DEBUG) Log.d(TAG, "Tile can show important: " + tile.getUserName());
            return false;
        }
        if ((notificationPolicyState & PeopleSpaceTile.SHOW_STARRED_CONTACTS) != 0
                && tile.getContactAffinity() == STARRED_CONTACT) {
            if (DEBUG) Log.d(TAG, "Tile can show starred: " + tile.getUserName());
            return false;
        }
        if ((notificationPolicyState & PeopleSpaceTile.SHOW_CONTACTS) != 0
                && (tile.getContactAffinity() == VALID_CONTACT
                || tile.getContactAffinity() == STARRED_CONTACT)) {
            if (DEBUG) Log.d(TAG, "Tile can show contacts: " + tile.getUserName());
            return false;
        }
        if (DEBUG) Log.d(TAG, "Tile can show if can bypass DND: " + tile.getUserName());
        return !tile.canBypassDnd();
    }

    private RemoteViews createSuppressedView() {
        RemoteViews views;
        if (mTile != null && mTile.isUserQuieted()) {
            views = new RemoteViews(mContext.getPackageName(),
                    R.layout.people_tile_work_profile_quiet_layout);
        } else {
            views = new RemoteViews(mContext.getPackageName(),
                    R.layout.people_tile_suppressed_layout);
        }
        Drawable appIcon = mContext.getDrawable(R.drawable.ic_conversation_icon);
        Bitmap disabledBitmap = convertDrawableToDisabledBitmap(appIcon);
        views.setImageViewBitmap(R.id.icon, disabledBitmap);
        return views;
    }

    private void setMaxLines(RemoteViews views, boolean showSender) {
        int textSizeResId;
        int nameHeight;
        if (mLayoutSize == LAYOUT_LARGE) {
            textSizeResId = R.dimen.content_text_size_for_large;
            nameHeight = getLineHeightFromResource(R.dimen.name_text_size_for_large_content);
        } else {
            textSizeResId = R.dimen.content_text_size_for_medium;
            nameHeight = getLineHeightFromResource(R.dimen.name_text_size_for_medium_content);
        }
        boolean isStatusLayout =
                views.getLayoutId() == R.layout.people_tile_large_with_status_content;
        int contentHeight = getContentHeightForLayout(nameHeight, isStatusLayout);
        int lineHeight = getLineHeightFromResource(textSizeResId);
        int maxAdaptiveLines = Math.floorDiv(contentHeight, lineHeight);
        int maxLines = Math.max(MIN_CONTENT_MAX_LINES, maxAdaptiveLines);

        // Save a line for sender's name, if present.
        if (showSender) maxLines--;
        views.setInt(R.id.text_content, "setMaxLines", maxLines);
    }

    private int getLineHeightFromResource(int resId) {
        try {
            TextView text = new TextView(mContext);
            text.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    mContext.getResources().getDimension(resId));
            text.setTextAppearance(android.R.style.TextAppearance_DeviceDefault);
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

    private int getContentHeightForLayout(int lineHeight, boolean hasPredefinedIcon) {
        switch (mLayoutSize) {
            case LAYOUT_MEDIUM:
                return mHeight - (lineHeight + FIXED_HEIGHT_DIMENS_FOR_MEDIUM_CONTENT_BEFORE_PADDING
                        + mMediumVerticalPadding * 2);
            case LAYOUT_LARGE:
                int fixedHeight = hasPredefinedIcon ? FIXED_HEIGHT_DIMENS_FOR_LARGE_STATUS_CONTENT
                        : FIXED_HEIGHT_DIMENS_FOR_LARGE_NOTIF_CONTENT;
                return mHeight - (getSizeInDp(
                        R.dimen.max_people_avatar_size_for_large_content) + lineHeight
                        + fixedHeight);
            default:
                return -1;
        }
    }

    /** Calculates the best layout relative to the size in {@code options}. */
    private int getLayoutSize() {
        if (mHeight >= getSizeInDp(R.dimen.required_height_for_large)
                && mWidth >= getSizeInDp(R.dimen.required_width_for_large)) {
            if (DEBUG) Log.d(TAG, "Large view for mWidth: " + mWidth + " mHeight: " + mHeight);
            return LAYOUT_LARGE;
        }
        // Small layout used below a certain minimum mWidth with any mHeight.
        if (mWidth >= getSizeInDp(R.dimen.required_width_for_medium)) {
            int spaceAvailableForPadding =
                    mHeight - (getSizeInDp(R.dimen.avatar_size_for_medium)
                            + 4 + getLineHeightFromResource(
                            R.dimen.name_text_size_for_medium_content));
            if (DEBUG) {
                Log.d(TAG, "Medium view for mWidth: " + mWidth + " mHeight: " + mHeight
                        + " with padding space: " + spaceAvailableForPadding);
            }
            int maxVerticalPadding = Math.min(Math.floorDiv(spaceAvailableForPadding, 2),
                    MAX_MEDIUM_PADDING);
            mMediumVerticalPadding = Math.max(MIN_MEDIUM_VERTICAL_PADDING, maxVerticalPadding);
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
                    getLineHeightFromResource(
                            R.dimen.name_text_size_for_small)));
            int avatarWidthSpace = mWidth - FIXED_WIDTH_DIMENS_FOR_SMALL;
            avatarSize = Math.min(avatarHeightSpace, avatarWidthSpace);
        }

        if (layoutId == R.layout.people_tile_large_with_notification_content) {
            avatarSize = mHeight - (FIXED_HEIGHT_DIMENS_FOR_LARGE_NOTIF_CONTENT + (
                    getLineHeightFromResource(
                            R.dimen.content_text_size_for_large)
                            * 3));
            return Math.min(avatarSize, getSizeInDp(
                    R.dimen.max_people_avatar_size_for_large_content));
        } else if (layoutId == R.layout.people_tile_large_with_status_content) {
            avatarSize = mHeight - (FIXED_HEIGHT_DIMENS_FOR_LARGE_STATUS_CONTENT + (
                    getLineHeightFromResource(R.dimen.content_text_size_for_large)
                            * 3));
            return Math.min(avatarSize, getSizeInDp(
                    R.dimen.max_people_avatar_size_for_large_content));
        }

        if (layoutId == R.layout.people_tile_large_empty) {
            int avatarHeightSpace = mHeight - (14 + 14 + getLineHeightFromResource(
                    R.dimen.name_text_size_for_large)
                    + getLineHeightFromResource(R.dimen.content_text_size_for_large)
                    + 16 + 10 + 16);
            int avatarWidthSpace = mWidth - (14 + 14);
            avatarSize = Math.min(avatarHeightSpace, avatarWidthSpace);
        }

        if (isDndBlockingTileData(mTile)) {
            avatarSize = createDndRemoteViews().mAvatarSize;
        }

        return Math.min(avatarSize,
                getSizeInDp(R.dimen.max_people_avatar_size));
    }

    private RemoteViews setCommonRemoteViewsFields(RemoteViews views,
            int maxAvatarSize) {
        try {
            if (mTile == null) {
                return views;
            }
            boolean isAvailable =
                    mTile.getStatuses() != null && mTile.getStatuses().stream().anyMatch(
                            c -> c.getAvailability() == AVAILABILITY_AVAILABLE);
            if (isAvailable) {
                views.setViewVisibility(R.id.availability, View.VISIBLE);
            } else {
                views.setViewVisibility(R.id.availability, View.GONE);
            }

            views.setImageViewBitmap(R.id.person_icon,
                    getPersonIconBitmap(mContext, mTile, maxAvatarSize));
            return views;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set common fields: " + e);
        }
        return views;
    }

    private RemoteViews setLaunchIntents(RemoteViews views) {
        if (!PeopleTileKey.isValid(mKey) || mTile == null) {
            if (DEBUG) Log.d(TAG, "Skipping launch intent, Null tile or invalid key: " + mKey);
            return views;
        }

        try {
            Intent activityIntent = new Intent(mContext, LaunchConversationActivity.class);
            activityIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK
                            | Intent.FLAG_ACTIVITY_NO_HISTORY
                            | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            activityIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_TILE_ID, mKey.getShortcutId());
            activityIntent.putExtra(
                    PeopleSpaceWidgetProvider.EXTRA_PACKAGE_NAME, mKey.getPackageName());
            activityIntent.putExtra(PeopleSpaceWidgetProvider.EXTRA_USER_HANDLE,
                    new UserHandle(mKey.getUserId()));
            if (mTile != null) {
                activityIntent.putExtra(
                        PeopleSpaceWidgetProvider.EXTRA_NOTIFICATION_KEY,
                        mTile.getNotificationKey());
            }
            views.setOnClickPendingIntent(android.R.id.background, PendingIntent.getActivity(
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

    private RemoteViewsAndSizes createDndRemoteViews() {
        boolean isHorizontal = mLayoutSize == LAYOUT_MEDIUM;
        int layoutId = isHorizontal
                ? R.layout.people_tile_with_suppression_detail_content_horizontal
                : R.layout.people_tile_with_suppression_detail_content_vertical;
        RemoteViews views = new RemoteViews(mContext.getPackageName(), layoutId);

        int outerPadding = mLayoutSize == LAYOUT_LARGE ? 16 : 8;
        int outerPaddingPx = dpToPx(outerPadding);
        views.setViewPadding(
                android.R.id.background,
                outerPaddingPx,
                outerPaddingPx,
                outerPaddingPx,
                outerPaddingPx);

        int mediumAvatarSize = getSizeInDp(R.dimen.avatar_size_for_medium);
        int maxAvatarSize = getSizeInDp(R.dimen.max_people_avatar_size);

        String text = mContext.getString(R.string.paused_by_dnd);
        views.setTextViewText(R.id.text_content, text);

        int textSizeResId =
                mLayoutSize == LAYOUT_LARGE
                        ? R.dimen.content_text_size_for_large
                        : R.dimen.content_text_size_for_medium;
        float textSizePx = mContext.getResources().getDimension(textSizeResId);
        views.setTextViewTextSize(R.id.text_content, COMPLEX_UNIT_PX, textSizePx);
        int lineHeight = getLineHeightFromResource(textSizeResId);

        int avatarSize;
        if (isHorizontal) {
            int maxTextHeight = mHeight - outerPadding;
            views.setInt(R.id.text_content, "setMaxLines", maxTextHeight / lineHeight);
            avatarSize = mediumAvatarSize;
        } else {
            int iconSize =
                    getSizeInDp(
                            mLayoutSize == LAYOUT_SMALL
                                    ? R.dimen.regular_predefined_icon
                                    : R.dimen.largest_predefined_icon);
            int heightWithoutIcon = mHeight - 2 * outerPadding - iconSize;
            int paddingBetweenElements =
                    getSizeInDp(R.dimen.padding_between_suppressed_layout_items);
            int maxTextWidth = mWidth - outerPadding * 2;
            int maxTextHeight = heightWithoutIcon - mediumAvatarSize - paddingBetweenElements * 2;

            int availableAvatarHeight;
            int textHeight = estimateTextHeight(text, textSizeResId, maxTextWidth);
            if (textHeight <= maxTextHeight) {
                // If the text will fit, then display it and deduct its height from the space we
                // have for the avatar.
                availableAvatarHeight = heightWithoutIcon - textHeight - paddingBetweenElements * 2;
                views.setViewVisibility(R.id.text_content, View.VISIBLE);
                views.setInt(R.id.text_content, "setMaxLines", maxTextHeight / lineHeight);
                views.setContentDescription(R.id.predefined_icon, null);
            } else {
                // If the height doesn't fit, then hide it. The dnd icon will still show.
                availableAvatarHeight = heightWithoutIcon - paddingBetweenElements;
                views.setViewVisibility(R.id.text_content, View.GONE);
                // If we don't show the dnd text, set it as the content description on the icon
                // for a11y.
                views.setContentDescription(R.id.predefined_icon, text);
            }

            int availableAvatarWidth = mWidth - outerPadding * 2;
            avatarSize =
                    MathUtils.clamp(
                            /* value= */ Math.min(availableAvatarWidth, availableAvatarHeight),
                            /* min= */ dpToPx(10),
                            /* max= */ maxAvatarSize);

            views.setViewLayoutWidth(R.id.predefined_icon, iconSize, COMPLEX_UNIT_DIP);
            views.setViewLayoutHeight(R.id.predefined_icon, iconSize, COMPLEX_UNIT_DIP);
            views.setImageViewResource(R.id.predefined_icon, R.drawable.ic_qs_dnd_on);
        }

        return new RemoteViewsAndSizes(views, avatarSize);
    }


    private RemoteViews createMissedCallRemoteViews() {
        RemoteViews views = setViewForContentLayout(new RemoteViews(mContext.getPackageName(),
                getLayoutForContent()));
        views.setViewVisibility(R.id.predefined_icon, View.VISIBLE);
        views.setViewVisibility(R.id.text_content, View.VISIBLE);
        views.setViewVisibility(R.id.messages_count, View.GONE);
        setMaxLines(views, false);
        views.setTextViewText(R.id.text_content, mTile.getNotificationContent());
        views.setColorAttr(R.id.text_content, "setTextColor", android.R.attr.colorError);
        views.setColorAttr(R.id.predefined_icon, "setColorFilter", android.R.attr.colorError);
        views.setImageViewResource(R.id.predefined_icon, R.drawable.ic_phone_missed);
        if (mLayoutSize == LAYOUT_LARGE) {
            views.setInt(R.id.content, "setGravity", Gravity.BOTTOM);
            views.setViewLayoutHeightDimen(R.id.predefined_icon, R.dimen.larger_predefined_icon);
            views.setViewLayoutWidthDimen(R.id.predefined_icon, R.dimen.larger_predefined_icon);
        }
        setAvailabilityDotPadding(views, R.dimen.availability_dot_notification_padding);
        return views;
    }

    private RemoteViews createNotificationRemoteViews() {
        RemoteViews views = setViewForContentLayout(new RemoteViews(mContext.getPackageName(),
                getLayoutForNotificationContent()));
        CharSequence sender = mTile.getNotificationSender();
        Uri image = mTile.getNotificationDataUri();
        if (image != null) {
            // TODO: Use NotificationInlineImageCache
            views.setImageViewUri(R.id.image, image);
            views.setViewVisibility(R.id.image, View.VISIBLE);
            views.setViewVisibility(R.id.text_content, View.GONE);
            views.setImageViewResource(R.id.predefined_icon, R.drawable.ic_photo_camera);
        } else {
            setMaxLines(views, !TextUtils.isEmpty(sender));
            CharSequence content = mTile.getNotificationContent();
            views = decorateBackground(views, content);
            views.setColorAttr(R.id.text_content, "setTextColor", android.R.attr.textColorPrimary);
            views.setTextViewText(R.id.text_content, mTile.getNotificationContent());
            if (mLayoutSize == LAYOUT_LARGE) {
                views.setViewPadding(R.id.name, 0, 0, 0,
                        mContext.getResources().getDimensionPixelSize(
                                R.dimen.above_notification_text_padding));
            }
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
        if (!TextUtils.isEmpty(sender)) {
            views.setViewVisibility(R.id.subtext, View.VISIBLE);
            views.setTextViewText(R.id.subtext, sender);
        } else {
            views.setViewVisibility(R.id.subtext, View.GONE);
        }
        setAvailabilityDotPadding(views, R.dimen.availability_dot_notification_padding);
        return views;
    }

    // Some messaging apps only include up to 6 messages in their notifications.
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
        RemoteViews views = setViewForContentLayout(new RemoteViews(mContext.getPackageName(),
                getLayoutForContent()));
        CharSequence statusText = status.getDescription();
        if (TextUtils.isEmpty(statusText)) {
            statusText = getStatusTextByType(status.getActivity());
        }
        views.setViewVisibility(R.id.predefined_icon, View.VISIBLE);
        views.setTextViewText(R.id.text_content, statusText);

        if (status.getActivity() == ACTIVITY_BIRTHDAY
            || status.getActivity() == ACTIVITY_UPCOMING_BIRTHDAY) {
            setEmojiBackground(views, EMOJI_CAKE);
        }

        Icon statusIcon = status.getIcon();
        if (statusIcon != null) {
            // No text content styled text on medium or large.
            views.setViewVisibility(R.id.scrim_layout, View.VISIBLE);
            views.setImageViewIcon(R.id.status_icon, statusIcon);
            // Show 1-line subtext on large layout with status images.
            if (mLayoutSize == LAYOUT_LARGE) {
                if (DEBUG) Log.d(TAG, "Remove name for large");
                views.setInt(R.id.content, "setGravity", Gravity.BOTTOM);
                views.setViewVisibility(R.id.name, View.GONE);
                views.setColorAttr(R.id.text_content, "setTextColor",
                        android.R.attr.textColorPrimary);
            } else if (mLayoutSize == LAYOUT_MEDIUM) {
                views.setViewVisibility(R.id.text_content, View.GONE);
                views.setTextViewText(R.id.name, statusText);
            }
        } else {
            // Secondary text color for statuses without icons.
            views.setColorAttr(R.id.text_content, "setTextColor",
                    android.R.attr.textColorSecondary);
            setMaxLines(views, false);
        }
        setAvailabilityDotPadding(views, R.dimen.availability_dot_status_padding);
        views.setImageViewResource(R.id.predefined_icon, getDrawableForStatus(status));
        return views;
    }

    private int getDrawableForStatus(ConversationStatus status) {
        switch (status.getActivity()) {
            case ACTIVITY_NEW_STORY:
                return R.drawable.ic_pages;
            case ACTIVITY_ANNIVERSARY:
                return R.drawable.ic_celebration;
            case ACTIVITY_UPCOMING_BIRTHDAY:
                return R.drawable.ic_gift;
            case ACTIVITY_BIRTHDAY:
                return R.drawable.ic_cake;
            case ACTIVITY_LOCATION:
                return R.drawable.ic_location;
            case ACTIVITY_GAME:
                return R.drawable.ic_play_games;
            case ACTIVITY_VIDEO:
                return R.drawable.ic_video;
            case ACTIVITY_AUDIO:
                return R.drawable.ic_music_note;
            default:
                return R.drawable.ic_person;
        }
    }

    /**
     * Update the padding of the availability dot. The padding on the availability dot decreases
     * on the status layouts compared to all other layouts.
     */
    private void setAvailabilityDotPadding(RemoteViews views, int resId) {
        boolean isLeftToRight = TextUtils.getLayoutDirectionFromLocale(Locale.getDefault())
                == View.LAYOUT_DIRECTION_LTR;
        int startPadding = mContext.getResources().getDimensionPixelSize(resId);
        int bottomPadding = mContext.getResources().getDimensionPixelSize(
                R.dimen.medium_content_padding_above_name);
        views.setViewPadding(R.id.medium_content,
                isLeftToRight ? startPadding : 0, 0, isLeftToRight ? 0 : startPadding,
                bottomPadding);
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

    private RemoteViews decorateBackground(RemoteViews views, CharSequence content) {
        CharSequence emoji = getDoubleEmoji(content);
        if (!TextUtils.isEmpty(emoji)) {
            setEmojiBackground(views, emoji);
            setPunctuationBackground(views, null);
            return views;
        }

        CharSequence punctuation = getDoublePunctuation(content);
        setEmojiBackground(views, null);
        setPunctuationBackground(views, punctuation);
        return views;
    }

    private RemoteViews setEmojiBackground(RemoteViews views, CharSequence content) {
        if (TextUtils.isEmpty(content)) {
            views.setViewVisibility(R.id.emojis, View.GONE);
            return views;
        }
        views.setTextViewText(R.id.emoji1, content);
        views.setTextViewText(R.id.emoji2, content);
        views.setTextViewText(R.id.emoji3, content);

        views.setViewVisibility(R.id.emojis, View.VISIBLE);
        return views;
    }

    private RemoteViews setPunctuationBackground(RemoteViews views, CharSequence content) {
        if (TextUtils.isEmpty(content)) {
            views.setViewVisibility(R.id.punctuations, View.GONE);
            return views;
        }
        views.setTextViewText(R.id.punctuation1, content);
        views.setTextViewText(R.id.punctuation2, content);
        views.setTextViewText(R.id.punctuation3, content);
        views.setTextViewText(R.id.punctuation4, content);
        views.setTextViewText(R.id.punctuation5, content);
        views.setTextViewText(R.id.punctuation6, content);

        views.setViewVisibility(R.id.punctuations, View.VISIBLE);
        return views;
    }

    /** Returns punctuation character(s) if {@code message} has double punctuation ("!" or "?"). */
    @VisibleForTesting
    CharSequence getDoublePunctuation(CharSequence message) {
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

    /** Returns emoji if {@code message} has two of the same emoji in sequence. */
    @VisibleForTesting
    CharSequence getDoubleEmoji(CharSequence message) {
        Matcher unicodeEmojiMatcher = EMOJI_PATTERN.matcher(message);
        // Stores the start and end indices of each matched emoji.
        List<Pair<Integer, Integer>> emojiIndices = new ArrayList<>();
        // Stores each emoji text.
        List<CharSequence> emojiTexts = new ArrayList<>();

        // Scan message for emojis
        while (unicodeEmojiMatcher.find()) {
            int start = unicodeEmojiMatcher.start();
            int end = unicodeEmojiMatcher.end();
            emojiIndices.add(new Pair(start, end));
            emojiTexts.add(message.subSequence(start, end));
        }

        if (DEBUG) Log.d(TAG, "Number of emojis in the message: " + emojiIndices.size());
        if (emojiIndices.size() < 2) {
            return null;
        }

        for (int i = 1; i < emojiIndices.size(); ++i) {
            Pair<Integer, Integer> second = emojiIndices.get(i);
            Pair<Integer, Integer> first = emojiIndices.get(i - 1);

            // Check if second emoji starts right after first starts
            if (second.first == first.second) {
                // Check if emojis in sequence are the same
                if (Objects.equals(emojiTexts.get(i), emojiTexts.get(i - 1))) {
                    if (DEBUG) {
                        Log.d(TAG, "Two of the same emojis in sequence: " + emojiTexts.get(i));
                    }
                    return emojiTexts.get(i);
                }
            }
        }

        // No equal emojis in sequence.
        return null;
    }

    private RemoteViews setViewForContentLayout(RemoteViews views) {
        views = decorateBackground(views, "");
        if (mLayoutSize == LAYOUT_SMALL) {
            views.setViewVisibility(R.id.predefined_icon, View.VISIBLE);
            views.setViewVisibility(R.id.name, View.GONE);
        } else {
            views.setViewVisibility(R.id.predefined_icon, View.GONE);
            views.setViewVisibility(R.id.name, View.VISIBLE);
            views.setViewVisibility(R.id.text_content, View.VISIBLE);
            views.setViewVisibility(R.id.subtext, View.GONE);
            views.setViewVisibility(R.id.image, View.GONE);
            views.setViewVisibility(R.id.scrim_layout, View.GONE);
        }

        if (mLayoutSize == LAYOUT_MEDIUM) {
            // Maximize vertical padding with an avatar size of 48dp and name on medium.
            if (DEBUG) Log.d(TAG, "Set vertical padding: " + mMediumVerticalPadding);
            int horizontalPadding = (int) Math.floor(MAX_MEDIUM_PADDING * mDensity);
            int verticalPadding = (int) Math.floor(mMediumVerticalPadding * mDensity);
            views.setViewPadding(R.id.content, horizontalPadding, verticalPadding,
                    horizontalPadding,
                    verticalPadding);
            // Expand the name font on medium if there's space.
            int heightRequiredForMaxContentText = (int) (mContext.getResources().getDimension(
                    R.dimen.medium_height_for_max_name_text_size) / mDensity);
            if (mHeight > heightRequiredForMaxContentText) {
                views.setTextViewTextSize(R.id.name, TypedValue.COMPLEX_UNIT_PX,
                        (int) mContext.getResources().getDimension(
                                R.dimen.max_name_text_size_for_medium));
            }
        }

        if (mLayoutSize == LAYOUT_LARGE) {
            // Decrease the view padding below the name on all layouts besides notification "text".
            views.setViewPadding(R.id.name, 0, 0, 0,
                    mContext.getResources().getDimensionPixelSize(
                            R.dimen.below_name_text_padding));
            // All large layouts besides missed calls & statuses with images, have gravity top.
            views.setInt(R.id.content, "setGravity", Gravity.TOP);
        }

        // For all layouts except Missed Calls, ensure predefined icon is regular sized.
        views.setViewLayoutHeightDimen(R.id.predefined_icon, R.dimen.regular_predefined_icon);
        views.setViewLayoutWidthDimen(R.id.predefined_icon, R.dimen.regular_predefined_icon);

        views.setViewVisibility(R.id.messages_count, View.GONE);
        if (mTile.getUserName() != null) {
            views.setTextViewText(R.id.name, mTile.getUserName());
        }

        return views;
    }

    private RemoteViews createLastInteractionRemoteViews() {
        RemoteViews views = new RemoteViews(mContext.getPackageName(), getEmptyLayout());
        views.setInt(R.id.name, "setMaxLines", NAME_MAX_LINES_WITH_LAST_INTERACTION);
        if (mLayoutSize == LAYOUT_SMALL) {
            views.setViewVisibility(R.id.name, View.VISIBLE);
            views.setViewVisibility(R.id.predefined_icon, View.GONE);
            views.setViewVisibility(R.id.messages_count, View.GONE);
        }
        if (mTile.getUserName() != null) {
            views.setTextViewText(R.id.name, mTile.getUserName());
        }
        String status = getLastInteractionString(mContext,
                mTile.getLastInteractionTimestamp());
        if (status != null) {
            if (DEBUG) Log.d(TAG, "Show last interaction");
            views.setViewVisibility(R.id.last_interaction, View.VISIBLE);
            views.setTextViewText(R.id.last_interaction, status);
        } else {
            if (DEBUG) Log.d(TAG, "Hide last interaction");
            views.setViewVisibility(R.id.last_interaction, View.GONE);
            if (mLayoutSize == LAYOUT_MEDIUM) {
                views.setInt(R.id.name, "setMaxLines", NAME_MAX_LINES_WITHOUT_LAST_INTERACTION);
            }
        }
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

    private int getLayoutForNotificationContent() {
        switch (mLayoutSize) {
            case LAYOUT_MEDIUM:
                return R.layout.people_tile_medium_with_content;
            case LAYOUT_LARGE:
                return R.layout.people_tile_large_with_notification_content;
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
                return R.layout.people_tile_large_with_status_content;
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
        if (icon == null) {
            Drawable placeholder = context.getDrawable(R.drawable.ic_avatar_with_badge);
            return convertDrawableToDisabledBitmap(placeholder);
        }
        PeopleStoryIconFactory storyIcon = new PeopleStoryIconFactory(context,
                context.getPackageManager(),
                IconDrawableFactory.newInstance(context, false),
                maxAvatarSize);
        RoundedBitmapDrawable roundedDrawable = RoundedBitmapDrawableFactory.create(
                context.getResources(), icon.getBitmap());
        Drawable personDrawable = storyIcon.getPeopleTileDrawable(roundedDrawable,
                tile.getPackageName(), getUserId(tile), tile.isImportantConversation(),
                hasNewStory);

        if (isDndBlockingTileData(tile)) {
            // If DND is blocking the conversation, then display the icon in grayscale.
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0);
            personDrawable.setColorFilter(new ColorMatrixColorFilter(colorMatrix));
        }

        return convertDrawableToBitmap(personDrawable);
    }

    /** Returns a readable status describing the {@code lastInteraction}. */
    @Nullable
    public static String getLastInteractionString(Context context, long lastInteraction) {
        if (lastInteraction == 0L) {
            Log.e(TAG, "Could not get valid last interaction");
            return null;
        }
        long now = System.currentTimeMillis();
        Duration durationSinceLastInteraction = Duration.ofMillis(now - lastInteraction);
        if (durationSinceLastInteraction.toDays() <= ONE_DAY) {
            return null;
        } else if (durationSinceLastInteraction.toDays() < DAYS_IN_A_WEEK) {
            return context.getString(R.string.days_timestamp,
                    durationSinceLastInteraction.toDays());
        } else if (durationSinceLastInteraction.toDays() == DAYS_IN_A_WEEK) {
            return context.getString(R.string.one_week_timestamp);
        } else if (durationSinceLastInteraction.toDays() < DAYS_IN_A_WEEK * 2) {
            return context.getString(R.string.over_one_week_timestamp);
        } else if (durationSinceLastInteraction.toDays() == DAYS_IN_A_WEEK * 2) {
            return context.getString(R.string.two_weeks_timestamp);
        } else {
            // Over 2 weeks ago
            return context.getString(R.string.over_two_weeks_timestamp);
        }
    }

    /**
     * Estimates the height (in dp) which the text will have given the text size and the available
     * width. Returns Integer.MAX_VALUE if the estimation couldn't be obtained, as this is intended
     * to be used an estimate of the maximum.
     */
    private int estimateTextHeight(
            CharSequence text,
            @DimenRes int textSizeResId,
            int availableWidthDp) {
        StaticLayout staticLayout = buildStaticLayout(text, textSizeResId, availableWidthDp);
        if (staticLayout == null) {
            // Return max value (rather than e.g. -1) so the value can be used with <= bound checks.
            return Integer.MAX_VALUE;
        }
        return pxToDp(staticLayout.getHeight());
    }

    /**
     * Builds a StaticLayout for the text given the text size and available width. This can be used
     * to obtain information about how TextView will lay out the text. Returns null if any error
     * occurred creating a TextView.
     */
    @Nullable
    private StaticLayout buildStaticLayout(
            CharSequence text,
            @DimenRes int textSizeResId,
            int availableWidthDp) {
        try {
            TextView textView = new TextView(mContext);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    mContext.getResources().getDimension(textSizeResId));
            textView.setTextAppearance(android.R.style.TextAppearance_DeviceDefault);
            TextPaint paint = textView.getPaint();
            return StaticLayout.Builder.obtain(
                    text, 0, text.length(), paint, dpToPx(availableWidthDp))
                    // Simple break strategy avoids hyphenation unless there's a single word longer
                    // than the line width. We use this break strategy so that we consider text to
                    // "fit" only if it fits in a nice way (i.e. without hyphenation in the middle
                    // of words).
                    .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                    .build();
        } catch (Exception e) {
            Log.e(TAG, "Could not create static layout: " + e);
            return null;
        }
    }

    private int dpToPx(float dp) {
        return (int) (dp * mDensity);
    }

    private int pxToDp(@Px float px) {
        return (int) (px / mDensity);
    }

    private static final class RemoteViewsAndSizes {
        final RemoteViews mRemoteViews;
        final int mAvatarSize;

        RemoteViewsAndSizes(RemoteViews remoteViews, int avatarSize) {
            mRemoteViews = remoteViews;
            mAvatarSize = avatarSize;
        }
    }

    private static Bitmap convertDrawableToDisabledBitmap(Drawable icon) {
        Bitmap appIconAsBitmap = convertDrawableToBitmap(icon);
        FastBitmapDrawable drawable = new FastBitmapDrawable(appIconAsBitmap);
        drawable.setIsDisabled(true);
        return convertDrawableToBitmap(drawable);
    }
}
