/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app;

import android.annotation.Nullable;
import android.annotation.TestApi;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Rational;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a set of parameters used to initialize and update an Activity in picture-in-picture
 * mode.
 */
public final class PictureInPictureParams implements Parcelable {

    /**
     * Builder class for {@link PictureInPictureParams} objects.
     */
    public static class Builder {

        @Nullable
        private Rational mAspectRatio;

        @Nullable
        private List<RemoteAction> mUserActions;

        @Nullable
        private Rect mSourceRectHint;

        /**
         * Sets the aspect ratio.  This aspect ratio is defined as the desired width / height, and
         * does not change upon device rotation.
         *
         * @param aspectRatio the new aspect ratio for the activity in picture-in-picture, must be
         * between 2.39:1 and 1:2.39 (inclusive).
         *
         * @return this builder instance.
         */
        public Builder setAspectRatio(Rational aspectRatio) {
            mAspectRatio = aspectRatio;
            return this;
        }

        /**
         * Sets the user actions.  If there are more than
         * {@link Activity#getMaxNumPictureInPictureActions()} actions, then the input list
         * will be truncated to that number.
         *
         * @param actions the new actions to show in the picture-in-picture menu.
         *
         * @return this builder instance.
         *
         * @see RemoteAction
         */
        public Builder setActions(List<RemoteAction> actions) {
            if (mUserActions != null) {
                mUserActions = null;
            }
            if (actions != null) {
                mUserActions = new ArrayList<>(actions);
            }
            return this;
        }

        /**
         * Sets the source bounds hint. These bounds are only used when an activity first enters
         * picture-in-picture, and describe the bounds in window coordinates of activity entering
         * picture-in-picture that will be visible following the transition. For the best effect,
         * these bounds should also match the aspect ratio in the arguments.
         *
         * @param launchBounds window-coordinate bounds indicating the area of the activity that
         * will still be visible following the transition into picture-in-picture (eg. the video
         * view bounds in a video player)
         *
         * @return this builder instance.
         */
        public Builder setSourceRectHint(Rect launchBounds) {
            if (launchBounds == null) {
                mSourceRectHint = null;
            } else {
                mSourceRectHint = new Rect(launchBounds);
            }
            return this;
        }

        /**
         * @return an immutable {@link PictureInPictureParams} to be used when entering or updating
         * the activity in picture-in-picture.
         *
         * @see Activity#enterPictureInPictureMode(PictureInPictureParams)
         * @see Activity#setPictureInPictureParams(PictureInPictureParams)
         */
        public PictureInPictureParams build() {
            PictureInPictureParams params = new PictureInPictureParams(mAspectRatio, mUserActions,
                    mSourceRectHint);
            return params;
        }
    }

    /**
     * The expected aspect ratio of the picture-in-picture.
     */
    @Nullable
    private Rational mAspectRatio;

    /**
     * The set of actions that are associated with this activity when in picture-in-picture.
     */
    @Nullable
    private List<RemoteAction> mUserActions;

    /**
     * The source bounds hint used when entering picture-in-picture, relative to the window bounds.
     * We can use this internally for the transition into picture-in-picture to ensure that a
     * particular source rect is visible throughout the whole transition.
     */
    @Nullable
    private Rect mSourceRectHint;

    /** {@hide} */
    PictureInPictureParams() {
    }

    /** {@hide} */
    PictureInPictureParams(Parcel in) {
        if (in.readInt() != 0) {
            mAspectRatio = new Rational(in.readInt(), in.readInt());
        }
        if (in.readInt() != 0) {
            mUserActions = new ArrayList<>();
            in.readParcelableList(mUserActions, RemoteAction.class.getClassLoader());
        }
        if (in.readInt() != 0) {
            mSourceRectHint = Rect.CREATOR.createFromParcel(in);
        }
    }

    /** {@hide} */
    PictureInPictureParams(Rational aspectRatio, List<RemoteAction> actions,
            Rect sourceRectHint) {
        mAspectRatio = aspectRatio;
        mUserActions = actions;
        mSourceRectHint = sourceRectHint;
    }

    /**
     * Copies the set parameters from the other picture-in-picture args.
     * @hide
     */
    public void copyOnlySet(PictureInPictureParams otherArgs) {
        if (otherArgs.hasSetAspectRatio()) {
            mAspectRatio = otherArgs.mAspectRatio;
        }
        if (otherArgs.hasSetActions()) {
            mUserActions = otherArgs.mUserActions;
        }
        if (otherArgs.hasSourceBoundsHint()) {
            mSourceRectHint = new Rect(otherArgs.getSourceRectHint());
        }
    }

    /**
     * @return the aspect ratio. If none is set, return 0.
     * @hide
     */
    @TestApi
    public float getAspectRatio() {
        if (mAspectRatio != null) {
            return mAspectRatio.floatValue();
        }
        return 0f;
    }

    /** @hide */
    public Rational getAspectRatioRational() {
        return mAspectRatio;
    }

    /**
     * @return whether the aspect ratio is set.
     * @hide
     */
    public boolean hasSetAspectRatio() {
        return mAspectRatio != null;
    }

    /**
     * @return the set of user actions.
     * @hide
     */
    @TestApi
    public List<RemoteAction> getActions() {
        return mUserActions;
    }

    /**
     * @return whether the user actions are set.
     * @hide
     */
    public boolean hasSetActions() {
        return mUserActions != null;
    }

    /**
     * Truncates the set of actions to the given {@param size}.
     * @hide
     */
    public void truncateActions(int size) {
        if (hasSetActions()) {
            mUserActions = mUserActions.subList(0, Math.min(mUserActions.size(), size));
        }
    }

    /**
     * @return the source rect hint
     * @hide
     */
    @TestApi
    public Rect getSourceRectHint() {
        return mSourceRectHint;
    }

    /**
     * @return whether there are launch bounds set
     * @hide
     */
    public boolean hasSourceBoundsHint() {
        return mSourceRectHint != null && !mSourceRectHint.isEmpty();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        if (mAspectRatio != null) {
            out.writeInt(1);
            out.writeInt(mAspectRatio.getNumerator());
            out.writeInt(mAspectRatio.getDenominator());
        } else {
            out.writeInt(0);
        }
        if (mUserActions != null) {
            out.writeInt(1);
            out.writeParcelableList(mUserActions, 0);
        } else {
            out.writeInt(0);
        }
        if (mSourceRectHint != null) {
            out.writeInt(1);
            mSourceRectHint.writeToParcel(out, 0);
        } else {
            out.writeInt(0);
        }
    }

    public static final @android.annotation.NonNull Creator<PictureInPictureParams> CREATOR =
            new Creator<PictureInPictureParams>() {
                public PictureInPictureParams createFromParcel(Parcel in) {
                    return new PictureInPictureParams(in);
                }
                public PictureInPictureParams[] newArray(int size) {
                    return new PictureInPictureParams[size];
                }
            };
}
