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

package com.android.systemui.keyguard;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.View;

/**
 * Data class containing display information (message, icon, styling) for indication to show at
 * the bottom of the keyguard.
 *
 * See {@link com.android.systemui.statusbar.phone.KeyguardBottomAreaView}.
 */
public class KeyguardIndication {
    @Nullable
    private final CharSequence mMessage;
    @NonNull
    private final ColorStateList mTextColor;
    @Nullable
    private final Drawable mIcon;
    @Nullable
    private final View.OnClickListener mOnClickListener;
    @Nullable
    private final Drawable mBackground;
    @Nullable
    private final Long mMinVisibilityMillis; // in milliseconds

    private KeyguardIndication(
            CharSequence message,
            ColorStateList textColor,
            Drawable icon,
            View.OnClickListener onClickListener,
            Drawable background,
            Long minVisibilityMillis) {
        mMessage = message;
        mTextColor = textColor;
        mIcon = icon;
        mOnClickListener = onClickListener;
        mBackground = background;
        mMinVisibilityMillis = minVisibilityMillis;
    }

    /**
     * Message to display
     */
    public @Nullable CharSequence getMessage() {
        return mMessage;
    }

    /**
     * TextColor to display the message.
     */
    public @NonNull ColorStateList getTextColor() {
        return mTextColor;
    }

    /**
     * Icon to display.
     */
    public @Nullable Drawable getIcon() {
        return mIcon;
    }

    /**
     * Click listener for messsage.
     */
    public @Nullable View.OnClickListener getClickListener() {
        return mOnClickListener;
    }

    /**
     * Background for textView.
     */
    public @Nullable Drawable getBackground() {
        return mBackground;
    }

    /**
     * Minimum time to show text in milliseconds.
     * @return null if unspecified
     */
    public @Nullable Long getMinVisibilityMillis() {
        return mMinVisibilityMillis;
    }

    @Override
    public String toString() {
        String str = "KeyguardIndication{";
        if (!TextUtils.isEmpty(mMessage)) str += "mMessage=" + mMessage;
        if (mIcon != null) str += " mIcon=" + mIcon;
        if (mOnClickListener != null) str += " mOnClickListener=" + mOnClickListener;
        if (mBackground != null) str += " mBackground=" + mBackground;
        if (mMinVisibilityMillis != null) str += " mMinVisibilityMillis=" + mMinVisibilityMillis;
        str += "}";
        return str;
    }

    /**
     * KeyguardIndication Builder
     */
    public static class Builder {
        private CharSequence mMessage;
        private Drawable mIcon;
        private View.OnClickListener mOnClickListener;
        private ColorStateList mTextColor;
        private Drawable mBackground;
        private Long mMinVisibilityMillis;

        public Builder() { }

        /**
         * Message to display. Indication requires a non-null message or icon.
         */
        public Builder setMessage(@NonNull CharSequence message) {
            this.mMessage = message;
            return this;
        }

        /**
         * Required field. Text color to use to display the message.
         */
        public Builder setTextColor(@NonNull ColorStateList textColor) {
            this.mTextColor = textColor;
            return this;
        }

        /**
         * Icon to show next to the text. Indication requires a non-null icon or message.
         * Icon location changes based on language display direction. For LTR, icon shows to the
         * left of the message. For RTL, icon shows to the right of the message.
         */
        public Builder setIcon(Drawable icon) {
            this.mIcon = icon;
            return this;
        }

        /**
         * Optional. Set a click listener on the message.
         */
        public Builder setClickListener(View.OnClickListener onClickListener) {
            this.mOnClickListener = onClickListener;
            return this;
        }

        /**
         * Optional. Set a custom background on the TextView.
         */
        public Builder setBackground(Drawable background) {
            this.mBackground = background;
            return this;
        }

        /**
         * Optional. Set a required minimum visibility time in milliseconds for the text
         * to show.
         */
        public Builder setMinVisibilityMillis(Long minVisibilityMillis) {
            this.mMinVisibilityMillis = minVisibilityMillis;
            return this;
        }

        /**
         * Build the KeyguardIndication.
         */
        public KeyguardIndication build() {
            if (TextUtils.isEmpty(mMessage) && mIcon == null) {
                throw new IllegalStateException("message or icon must be set");
            }
            if (mTextColor == null) {
                throw new IllegalStateException("text color must be set");
            }

            return new KeyguardIndication(
                    mMessage, mTextColor, mIcon, mOnClickListener, mBackground,
                    mMinVisibilityMillis);
        }
    }
}
