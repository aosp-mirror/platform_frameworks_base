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

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Data object for animation related override of TaskFragment.
 * @hide
 */
// TODO(b/206557124): Add more animation customization options.
public final class TaskFragmentAnimationParams implements Parcelable {

    /** The default {@link TaskFragmentAnimationParams} to use when there is no app override. */
    public static final TaskFragmentAnimationParams DEFAULT =
            new TaskFragmentAnimationParams.Builder().build();

    @ColorInt
    private final int mAnimationBackgroundColor;

    private TaskFragmentAnimationParams(@ColorInt int animationBackgroundColor) {
        mAnimationBackgroundColor = animationBackgroundColor;
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

    private TaskFragmentAnimationParams(Parcel in) {
        mAnimationBackgroundColor = in.readInt();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mAnimationBackgroundColor);
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
                + "}";
    }

    @Override
    public int hashCode() {
        return mAnimationBackgroundColor;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof TaskFragmentAnimationParams)) {
            return false;
        }
        final TaskFragmentAnimationParams other = (TaskFragmentAnimationParams) obj;
        return mAnimationBackgroundColor == other.mAnimationBackgroundColor;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** Builder to construct the {@link TaskFragmentAnimationParams}. */
    public static final class Builder {

        @ColorInt
        private int mAnimationBackgroundColor = 0;

        /**
         * Sets the {@link ColorInt} to use for the background during the animation with this
         * TaskFragment if the animation requires a background. The default value is
         * {@code 0}, which is to use the theme window background.
         *
         * @param color a packed color int, {@code AARRGGBB}, for the animation background color.
         * @return this {@link Builder}.
         */
        @NonNull
        public Builder setAnimationBackgroundColor(@ColorInt int color) {
            mAnimationBackgroundColor = color;
            return this;
        }

        /** Constructs the {@link TaskFragmentAnimationParams}. */
        @NonNull
        public TaskFragmentAnimationParams build() {
            return new TaskFragmentAnimationParams(mAnimationBackgroundColor);
        }
    }
}
