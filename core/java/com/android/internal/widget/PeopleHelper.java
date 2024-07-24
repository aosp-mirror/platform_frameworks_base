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

package com.android.internal.widget;

import static com.android.internal.widget.MessagingPropertyAnimator.ALPHA_IN;
import static com.android.internal.widget.MessagingPropertyAnimator.ALPHA_OUT;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.view.View;

import com.android.internal.R;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.util.ContrastColorUtil;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * This class provides some methods used by both the {@link ConversationLayout} and
 * {@link CallLayout} which both use the visual design originally created for conversations in R.
 */
public class PeopleHelper {

    private static final float COLOR_SHIFT_AMOUNT = 60;
    /**
     * Pattern for filter some ignorable characters.
     * p{Z} for any kind of whitespace or invisible separator.
     * p{C} for any kind of punctuation character.
     */
    private static final Pattern IGNORABLE_CHAR_PATTERN = Pattern.compile("[\\p{C}\\p{Z}]");
    private static final Pattern SPECIAL_CHAR_PATTERN =
            Pattern.compile("[!@#$%&*()_+=|<>?{}\\[\\]~-]");

    private Context mContext;
    private int mAvatarSize;
    private Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint mTextPaint = new Paint();

    /**
     * Call this when the view is inflated to provide a context and initialize the helper
     */
    public void init(Context context) {
        mContext = context;
        mAvatarSize = context.getResources().getDimensionPixelSize(R.dimen.messaging_avatar_size);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mTextPaint.setAntiAlias(true);
    }

    /**
     * A utility for animating CachingIconViews away when hidden.
     */
    public void animateViewForceHidden(CachingIconView view, boolean forceHidden) {
        boolean nowForceHidden = view.willBeForceHidden() || view.isForceHidden();
        if (forceHidden == nowForceHidden) {
            // We are either already forceHidden or will be
            return;
        }
        view.animate().cancel();
        view.setWillBeForceHidden(forceHidden);
        view.animate()
                .scaleX(forceHidden ? 0.5f : 1.0f)
                .scaleY(forceHidden ? 0.5f : 1.0f)
                .alpha(forceHidden ? 0.0f : 1.0f)
                .setInterpolator(forceHidden ? ALPHA_OUT : ALPHA_IN)
                .setDuration(160);
        if (view.getVisibility() != View.VISIBLE) {
            view.setForceHidden(forceHidden);
        } else {
            view.animate().withEndAction(() -> view.setForceHidden(forceHidden));
        }
        view.animate().start();
    }

    /**
     * This creates an avatar symbol for the given person or group
     *
     * @param name        the name of the person or group
     * @param symbol      a pre-chosen symbol for the person or group.  See
     *                    {@link #findNamePrefix(CharSequence, String)} or
     *                    {@link #findNameSplit(CharSequence)}
     * @param layoutColor the background color of the layout
     */
    @NonNull
    public Icon createAvatarSymbol(@NonNull CharSequence name, @NonNull String symbol,
            @ColorInt int layoutColor) {
        if (symbol.isEmpty() || TextUtils.isDigitsOnly(symbol)
                || SPECIAL_CHAR_PATTERN.matcher(symbol).find()) {
            Icon avatarIcon = Icon.createWithResource(mContext, R.drawable.messaging_user);
            avatarIcon.setTint(findColor(name, layoutColor));
            return avatarIcon;
        } else {
            Bitmap bitmap = Bitmap.createBitmap(mAvatarSize, mAvatarSize, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            float radius = mAvatarSize / 2.0f;
            int color = findColor(name, layoutColor);
            mPaint.setColor(color);
            canvas.drawCircle(radius, radius, radius, mPaint);
            boolean needDarkText = ColorUtils.calculateLuminance(color) > 0.5f;
            mTextPaint.setColor(needDarkText ? Color.BLACK : Color.WHITE);
            mTextPaint.setTextSize(symbol.length() == 1 ? mAvatarSize * 0.5f : mAvatarSize * 0.3f);
            int yPos = (int) (radius - ((mTextPaint.descent() + mTextPaint.ascent()) / 2));
            canvas.drawText(symbol, radius, yPos, mTextPaint);
            return Icon.createWithBitmap(bitmap);
        }
    }

    private int findColor(@NonNull CharSequence senderName, int layoutColor) {
        double luminance = ContrastColorUtil.calculateLuminance(layoutColor);
        float shift = Math.abs(senderName.hashCode()) % 5 / 4.0f - 0.5f;

        // we need to offset the range if the luminance is too close to the borders
        shift += Math.max(COLOR_SHIFT_AMOUNT / 2.0f / 100 - luminance, 0);
        shift -= Math.max(COLOR_SHIFT_AMOUNT / 2.0f / 100 - (1.0f - luminance), 0);
        return ContrastColorUtil.getShiftedColor(layoutColor,
                (int) (shift * COLOR_SHIFT_AMOUNT));
    }

    /**
     * Get the name with whitespace and punctuation characters removed
     */
    private String getPureName(@NonNull CharSequence name) {
        return IGNORABLE_CHAR_PATTERN.matcher(name).replaceAll("" /* replacement */);
    }

    /**
     * Gets a single character string prefix name for the person or group
     *
     * @param name     the name of the person or group
     * @param fallback the string to return if the name has no usable characters
     */
    public String findNamePrefix(@NonNull CharSequence name, String fallback) {
        String pureName = getPureName(name);
        if (pureName.isEmpty()) {
            return fallback;
        }
        try {
            return new String(Character.toChars(pureName.codePointAt(0)));
        } catch (RuntimeException ignore) {
            return fallback;
        }
    }

    /**
     * Find a 1 or 2 character prefix name for the person or group
     */
    public String findNameSplit(@NonNull CharSequence name) {
        String nameString = name instanceof String ? ((String) name) : name.toString();
        String[] split = nameString.trim().split("[ ]+");
        if (split.length > 1) {
            String first = findNamePrefix(split[0], null);
            String second = findNamePrefix(split[1], null);
            if (first != null && second != null) {
                return first + second;
            }
        }
        return findNamePrefix(name, "");
    }

    /**
     * Creates a mapping of the unique sender names in the groups to the string 1- or 2-character
     * prefix strings for the names, which are extracted as the initials, and should be used for
     * generating the avatar.  Senders not requiring a generated avatar, or with an empty name are
     * omitted.
     */
    public Map<CharSequence, String> mapUniqueNamesToPrefix(List<MessagingGroup> groups) {
        // Map of unique names to their prefix
        ArrayMap<CharSequence, String> uniqueNames = new ArrayMap<>();
        // Map of single-character string prefix to the only name which uses it, or null if multiple
        ArrayMap<String, CharSequence> uniqueCharacters = new ArrayMap<>();
        for (int i = 0; i < groups.size(); i++) {
            MessagingGroup group = groups.get(i);
            CharSequence senderName = group.getSenderName();
            if (!group.needsGeneratedAvatar() || TextUtils.isEmpty(senderName)) {
                continue;
            }
            if (!uniqueNames.containsKey(senderName)) {
                String charPrefix = findNamePrefix(senderName, null);
                if (charPrefix == null) {
                    continue;
                }
                if (uniqueCharacters.containsKey(charPrefix)) {
                    // this character was already used, lets make it more unique. We first need to
                    // resolve the existing character if it exists
                    CharSequence existingName = uniqueCharacters.get(charPrefix);
                    if (existingName != null) {
                        uniqueNames.put(existingName, findNameSplit(existingName));
                        uniqueCharacters.put(charPrefix, null);
                    }
                    uniqueNames.put(senderName, findNameSplit(senderName));
                } else {
                    uniqueNames.put(senderName, charPrefix);
                    uniqueCharacters.put(charPrefix, senderName);
                }
            }
        }
        return uniqueNames;
    }

    /**
     * A class that represents a map from unique sender names in the groups to the string 1- or
     * 2-character prefix strings for the names. This class uses the String value of the
     * CharSequence Names as the key.
     */
    public class NameToPrefixMap {
        Map<String, String> mMap;
        NameToPrefixMap(Map<String, String> map) {
            this.mMap = map;
        }

        /**
         * @param name the name
         * @return the prefix of the given name
         */
        public String getPrefix(CharSequence name) {
            return mMap.get(name.toString());
        }
    }

    /**
     * Same functionality as mapUniqueNamesToPrefix, but takes list-represented message groups as
     * the input. This method is better when inflating MessagingGroup from the UI thread is not
     * an option.
     * @param groups message groups represented by lists. A message group is some consecutive
     *               messages (>=3) from the same sender in a conversation.
     */
    public NameToPrefixMap mapUniqueNamesToPrefixWithGroupList(
            List<List<Notification.MessagingStyle.Message>> groups) {
        // Map of unique names to their prefix
        ArrayMap<String, String> uniqueNames = new ArrayMap<>();
        // Map of single-character string prefix to the only name which uses it, or null if multiple
        ArrayMap<String, CharSequence> uniqueCharacters = new ArrayMap<>();
        for (int i = 0; i < groups.size(); i++) {
            List<Notification.MessagingStyle.Message> group = groups.get(i);
            if (group.isEmpty()) continue;
            Person sender = group.get(0).getSenderPerson();
            if (sender == null) continue;
            CharSequence senderName = sender.getName();
            if (sender.getIcon() != null || TextUtils.isEmpty(senderName)) {
                continue;
            }
            String senderNameString = senderName.toString();
            if (!uniqueNames.containsKey(senderNameString)) {
                String charPrefix = findNamePrefix(senderName, null);
                if (charPrefix == null) {
                    continue;
                }
                if (uniqueCharacters.containsKey(charPrefix)) {
                    // this character was already used, lets make it more unique. We first need to
                    // resolve the existing character if it exists
                    CharSequence existingName = uniqueCharacters.get(charPrefix);
                    if (existingName != null) {
                        uniqueNames.put(existingName.toString(), findNameSplit(existingName));
                        uniqueCharacters.put(charPrefix, null);
                    }
                    uniqueNames.put(senderNameString, findNameSplit(senderName));
                } else {
                    uniqueNames.put(senderNameString, charPrefix);
                    uniqueCharacters.put(charPrefix, senderName);
                }
            }
        }
        return new NameToPrefixMap(uniqueNames);
    }

    /**
     * Update whether the groups can hide the sender if they are first
     * (happens only for 1:1 conversations where the given title matches the sender's name)
     */
    public void maybeHideFirstSenderName(@NonNull List<MessagingGroup> groups,
            boolean isOneToOne, @Nullable CharSequence conversationTitle) {
        for (int i = groups.size() - 1; i >= 0; i--) {
            MessagingGroup messagingGroup = groups.get(i);
            CharSequence messageSender = messagingGroup.getSenderName();
            boolean canHide = isOneToOne && TextUtils.equals(conversationTitle, messageSender);
            messagingGroup.setCanHideSenderIfFirst(canHide);
        }
    }
}
