/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.window;

import static android.window.TransitionInfo.AnimationOptions.DEFAULT_ANIMATION_RESOURCES_ID;

import android.annotation.AnimRes;
import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Data object for animation related override of TaskFragment.
 * @hide
 */
public final class TaskFragmentAnimationParams implements Parcelable {

    /** The default {@link TaskFragmentAnimationParams} to use when there is no app override. */
    public static final TaskFragmentAnimationParams DEFAULT =
            new TaskFragmentAnimationParams.Builder().build();

    /**
     * The default value for animation background color, which means to use the theme window
     * background color.
     */
    @ColorInt
    public static final int DEFAULT_ANIMATION_BACKGROUND_COLOR = 0;

    @ColorInt
    private final int mAnimationBackgroundColor;

    @AnimRes
    private final int mOpenAnimationResId;

    @AnimRes
    private final int mChangeAnimationResId;

    @AnimRes
    private final int mCloseAnimationResId;

    private TaskFragmentAnimationParams(@ColorInt int animationBackgroundColor,
            @AnimRes int openAnimationResId, @AnimRes int changeAnimationResId,
            @AnimRes int closeAnimationResId) {
        mAnimationBackgroundColor = animationBackgroundColor;
        mOpenAnimationResId = openAnimationResId;
        mChangeAnimationResId = changeAnimationResId;
        mCloseAnimationResId = closeAnimationResId;
    }

    /**
     * The {@link ColorInt} to use for the background during the animation with this TaskFragment if
     * the animation requires a background.
     *
     * The default value is {@code 0}, which is to use the theme window background.
     */
    @ColorInt
    public int getAnimationBackgroundColor() {
        return mAnimationBackgroundColor;
    }

    /**
     * Returns the resources ID of open animation that applies to this TaskFragment.
     * <p>
     * The default value is {@link DEFAULT_ANIMATION_RESOURCES_ID}, which is to use the system
     * default animation.
     */
    @AnimRes
    public int getOpenAnimationResId() {
        return mOpenAnimationResId;
    }

    /**
     * Returns the resources ID of change animation that applies to this TaskFragment.
     * <p>
     * The default value is {@link DEFAULT_ANIMATION_RESOURCES_ID}, which is to use the system
     * default animation.
     */
    @AnimRes
    public int getChangeAnimationResId() {
        return mChangeAnimationResId;
    }

    /**
     * Returns the resources ID of close animation that applies to this TaskFragment.
     * <p>
     * The default value is {@link DEFAULT_ANIMATION_RESOURCES_ID}, which is to use the system
     * default animation.
     */
    @AnimRes
    public int getCloseAnimationResId() {
        return mCloseAnimationResId;
    }

    private TaskFragmentAnimationParams(Parcel in) {
        mAnimationBackgroundColor = in.readInt();
        mOpenAnimationResId = in.readInt();
        mChangeAnimationResId = in.readInt();
        mCloseAnimationResId = in.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAnimationBackgroundColor);
        dest.writeInt(mOpenAnimationResId);
        dest.writeInt(mChangeAnimationResId);
        dest.writeInt(mCloseAnimationResId);
    }

    @NonNull
    public static final Creator<TaskFragmentAnimationParams> CREATOR =
            new Creator<TaskFragmentAnimationParams>() {
                @Override
                public TaskFragmentAnimationParams createFromParcel(Parcel in) {
                    return new TaskFragmentAnimationParams(in);
                }

                @Override
                public TaskFragmentAnimationParams[] newArray(int size) {
                    return new TaskFragmentAnimationParams[size];
                }
            };

    @Override
    public String toString() {
        return "TaskFragmentAnimationParams{"
                + " animationBgColor=" + Integer.toHexString(mAnimationBackgroundColor)
                + " openAnimResId=" + mOpenAnimationResId
                + " changeAnimResId=" + mChangeAnimationResId
                + " closeAnimResId=" + mCloseAnimationResId
                + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAnimationBackgroundColor, mOpenAnimationResId, mChangeAnimationResId,
                mCloseAnimationResId);
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof TaskFragmentAnimationParams other)) {
            return false;
        }
        return mAnimationBackgroundColor == other.mAnimationBackgroundColor
                && mOpenAnimationResId == other.mOpenAnimationResId
                && mChangeAnimationResId == other.mChangeAnimationResId
                && mCloseAnimationResId == other.mCloseAnimationResId;
    }

    /**
     * Returns {@code true} if one of {@link #getOpenAnimationResId()},
     * {@link #getChangeAnimationResId()} or {@link #getCloseAnimationResId()} is specified.
     */
    public boolean hasOverrideAnimation() {
        return mOpenAnimationResId != DEFAULT_ANIMATION_RESOURCES_ID
                || mChangeAnimationResId != DEFAULT_ANIMATION_BACKGROUND_COLOR
                || mCloseAnimationResId != DEFAULT_ANIMATION_RESOURCES_ID;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** Builder to construct the {@link TaskFragmentAnimationParams}. */
    public static final class Builder {

        @ColorInt
        private int mAnimationBackgroundColor = DEFAULT_ANIMATION_BACKGROUND_COLOR;

        @AnimRes
        private int mOpenAnimationResId = DEFAULT_ANIMATION_RESOURCES_ID;

        @AnimRes
        private int mChangeAnimationResId = DEFAULT_ANIMATION_RESOURCES_ID;

        @AnimRes
        private int mCloseAnimationResId = DEFAULT_ANIMATION_RESOURCES_ID;

        /**
         * Sets the {@link ColorInt} to use for the background during the animation with this
         * TaskFragment if the animation requires a background. The default value is
         * {@link #DEFAULT_ANIMATION_BACKGROUND_COLOR}, which is to use the theme window background
         * color.
         *
         * @param color a packed color int, {@code AARRGGBB}, for the animation background color.
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder setAnimationBackgroundColor(@ColorInt int color) {
            mAnimationBackgroundColor = color;
            return this;
        }

        /**
         * Sets the open animation resources ID this TaskFragment. The default value is
         * {@link DEFAULT_ANIMATION_RESOURCES_ID}, which is to use the system default animation.
         *
         * @param resId the open animation resources ID.
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder setOpenAnimationResId(@AnimRes int resId) {
            mOpenAnimationResId = resId;
            return this;
        }

        /**
         * Sets the change animation resources ID this TaskFragment. The default value is
         * {@link DEFAULT_ANIMATION_RESOURCES_ID}, which is to use the system default animation.
         *
         * @param resId the change animation resources ID.
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder setChangeAnimationResId(@AnimRes int resId) {
            mChangeAnimationResId = resId;
            return this;
        }

        /**
         * Sets the close animation resources ID this TaskFragment. The default value is
         * {@link DEFAULT_ANIMATION_RESOURCES_ID}, which is to use the system default animation.
         *
         * @param resId the close animation resources ID.
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder setCloseAnimationResId(@AnimRes int resId) {
            mCloseAnimationResId = resId;
            return this;
        }

        /** Constructs the {@link TaskFragmentAnimationParams}. */
        @NonNull
        public TaskFragmentAnimationParams build() {
            return new TaskFragmentAnimationParams(mAnimationBackgroundColor,
                    mOpenAnimationResId, mChangeAnimationResId, mCloseAnimationResId);
        }
    }
}
