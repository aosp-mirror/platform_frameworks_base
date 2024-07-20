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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.TestApi;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Rational;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
        private Rational mExpandedAspectRatio;

        @Nullable
        private List<RemoteAction> mUserActions;

        @Nullable
        private RemoteAction mCloseAction;

        @Nullable
        private Rect mSourceRectHint;

        private Boolean mAutoEnterEnabled;

        private Boolean mSeamlessResizeEnabled;

        private CharSequence mTitle;

        private CharSequence mSubtitle;

        private Boolean mIsLaunchIntoPip;

        /** Default constructor */
        public Builder() {}

        /**
         * Copy constructor
         * @param original {@link PictureInPictureParams} instance this builder is built upon.
         */
        public Builder(@NonNull PictureInPictureParams original) {
            mAspectRatio = original.mAspectRatio;
            mUserActions = original.mUserActions;
            mCloseAction = original.mCloseAction;
            mSourceRectHint = original.mSourceRectHint;
            mAutoEnterEnabled = original.mAutoEnterEnabled;
            mSeamlessResizeEnabled = original.mSeamlessResizeEnabled;
            mTitle = original.mTitle;
            mSubtitle = original.mSubtitle;
            mIsLaunchIntoPip = original.mIsLaunchIntoPip;
        }

        /**
         * Sets the aspect ratio.  This aspect ratio is defined as the desired width / height, and
         * does not change upon device rotation.
         *
         * @param aspectRatio the new aspect ratio for the activity in picture-in-picture, must be
         *                    between 2.39:1 and 1:2.39 (inclusive).
         * @return this builder instance.
         */
        public Builder setAspectRatio(Rational aspectRatio) {
            mAspectRatio = aspectRatio;
            return this;
        }

        /**
         * Sets the aspect ratio for the expanded picture-in-picture mode. The aspect ratio is
         * defined as the desired width / height. <br/>
         * The aspect ratio cannot be changed from horizontal to vertical or vertical to horizontal
         * while the PIP is shown. Any such changes will be ignored. <br/>
         *
         * Setting the expanded ratio shows the activity's support for expanded mode.
         *
         * @param expandedAspectRatio must not be between 2.39:1 and 1:2.39 (inclusive). If {@code
         *                            null}, expanded picture-in-picture mode is not supported.
         * @return this builder instance.
         */
        @RequiresFeature(PackageManager.FEATURE_EXPANDED_PICTURE_IN_PICTURE)
        public @NonNull Builder setExpandedAspectRatio(@Nullable Rational expandedAspectRatio) {
            mExpandedAspectRatio = expandedAspectRatio;
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
         * Sets a close action that should be invoked before the default close PiP action. The
         * custom action must close the activity quickly using {@link Activity#finish()}.
         * Otherwise, the system will forcibly close the PiP as if no custom close action was
         * provided.
         *
         * If the action matches one set via {@link PictureInPictureParams.Builder#setActions(List)}
         * it may be shown in place of that custom action in the menu.
         *
         * @param action to replace the system close action
         * @return this builder instance.
         * @see RemoteAction
         */
        @NonNull
        public Builder setCloseAction(@Nullable RemoteAction action) {
            mCloseAction = action;
            return this;
        }

        /**
         * Sets the window-coordinate bounds of an activity transitioning to picture-in-picture.
         * The bounds is the area of an activity that will be visible in the transition to
         * picture-in-picture mode. For the best effect, these bounds should also match the
         * aspect ratio in the arguments.
         *
         * In Android 12+ these bounds are also reused to improve the exit transition from 
         * picture-in-picture mode. See
         * <a href="{@docRoot}develop/ui/views/picture-in-picture#smoother-exit">Support
         * smoother animations when exiting out of PiP mode</a> for more details.
         *
         * @param launchBounds window-coordinate bounds indicating the area of the activity that
         * will still be visible following the transition into picture-in-picture (e.g. the video
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
         * Sets whether the system will automatically put the activity in picture-in-picture mode
         * without needing/waiting for the activity to call
         * {@link Activity#enterPictureInPictureMode(PictureInPictureParams)}.
         *
         * If true, {@link Activity#onPictureInPictureRequested()} will never be called.
         *
         * This property is {@code false} by default.
         * @param autoEnterEnabled {@code true} if the system will automatically put the activity
         *                                     in picture-in-picture mode.
         *
         * @return this builder instance.
         */
        @NonNull
        public Builder setAutoEnterEnabled(boolean autoEnterEnabled) {
            mAutoEnterEnabled = autoEnterEnabled;
            return this;
        }

        /**
         * Sets whether the system can seamlessly resize the window while the activity is in
         * picture-in-picture mode. This should normally be the case for video content and
         * when it's set to {@code false}, system will perform transitions to overcome the
         * artifacts due to resize.
         *
         * This property is {@code true} by default for backwards compatibility.
         * @param seamlessResizeEnabled {@code true} if the system can seamlessly resize the window
         *                                          while activity is in picture-in-picture mode.
         * @return this builder instance.
         */
        @NonNull
        public Builder setSeamlessResizeEnabled(boolean seamlessResizeEnabled) {
            mSeamlessResizeEnabled = seamlessResizeEnabled;
            return this;
        }

        /**
         * Sets a title for the picture-in-picture window, which may be displayed by the system to
         * give the user information about what this PIP is generally being used for.
         *
         * @param title General information about the PIP content
         * @return this builder instance.
         */
        @NonNull
        public Builder setTitle(@Nullable CharSequence title) {
            mTitle = title;
            return this;
        }

        /**
         * Sets a subtitle for the picture-in-picture window, which may be displayed by the system
         * to give the user more detailed information about what this PIP is displaying.<br/>
         *
         * Setting a title via {@link PictureInPictureParams.Builder#setTitle(CharSequence)} should
         * be prioritized.
         *
         * @param subtitle Details about the PIP content.
         * @return this builder instance
         */
        @NonNull
        public Builder setSubtitle(@Nullable CharSequence subtitle) {
            mSubtitle = subtitle;
            return this;
        }

        /**
         * Sets whether the built {@link PictureInPictureParams} represents a launch into
         * picture-in-picture request.
         *
         * This property is {@code false} by default.
         * @param isLaunchIntoPip {@code true} if the built instance represents a launch into
         *                                 picture-in-picture request
         * @return this builder instance.
         */
        @NonNull
        Builder setIsLaunchIntoPip(boolean isLaunchIntoPip) {
            mIsLaunchIntoPip = isLaunchIntoPip;
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
            PictureInPictureParams params = new PictureInPictureParams(mAspectRatio,
                    mExpandedAspectRatio, mUserActions, mCloseAction, mSourceRectHint,
                    mAutoEnterEnabled, mSeamlessResizeEnabled, mTitle, mSubtitle,
                    mIsLaunchIntoPip);
            return params;
        }
    }

    /**
     * The expected aspect ratio of the picture-in-picture.
     */
    @Nullable
    private Rational mAspectRatio;

    /**
     * The expected aspect ratio of the expanded picture-in-picture window.
     */
    @Nullable
    private Rational mExpandedAspectRatio;

    /**
     * The set of actions that are associated with this activity when in picture-in-picture.
     */
    @Nullable
    private List<RemoteAction> mUserActions;

    /**
     * Action to replace the system close action.
     */
    @Nullable
    private RemoteAction mCloseAction;

    /**
     * The source bounds hint used when entering picture-in-picture, relative to the window bounds.
     * We can use this internally for the transition into picture-in-picture to ensure that a
     * particular source rect is visible throughout the whole transition.
     */
    @Nullable
    private Rect mSourceRectHint;

    /**
     * Whether the system is allowed to automatically put the activity in picture-in-picture mode.
     * {@link #isAutoEnterEnabled()} defaults to {@code false} if this is not set.
     */
    private Boolean mAutoEnterEnabled;

    /**
     * Whether system can seamlessly resize the window when activity is in picture-in-picture mode.
     * {@link #isSeamlessResizeEnabled()} defaults to {@code true} if this is not set for
     * backwards compatibility.
     */
    private Boolean mSeamlessResizeEnabled;

    /**
     * Title of the picture-in-picture window to be displayed to the user.
     */
    @Nullable
    private CharSequence mTitle;

    /**
     * Subtitle for the picture-in-picture window to be displayed to the user.
     */
    @Nullable
    private CharSequence mSubtitle;

    /**
     * Whether this {@link PictureInPictureParams} represents a launch into
     * picture-in-picture request.
     * {@link #isLaunchIntoPip()} defaults to {@code false} is this is not set.
     */
    private Boolean mIsLaunchIntoPip;

    /** {@hide} */
    PictureInPictureParams() {
    }

    /** {@hide} */
    PictureInPictureParams(Parcel in) {
        mAspectRatio = readRationalFromParcel(in);
        mExpandedAspectRatio = readRationalFromParcel(in);
        if (in.readInt() != 0) {
            mUserActions = new ArrayList<>();
            in.readTypedList(mUserActions, RemoteAction.CREATOR);
        }
        mCloseAction = in.readTypedObject(RemoteAction.CREATOR);
        if (in.readInt() != 0) {
            mSourceRectHint = Rect.CREATOR.createFromParcel(in);
        }
        if (in.readInt() != 0) {
            mAutoEnterEnabled = in.readBoolean();
        }
        if (in.readInt() != 0) {
            mSeamlessResizeEnabled = in.readBoolean();
        }
        if (in.readInt() != 0) {
            mTitle = in.readCharSequence();
        }
        if (in.readInt() != 0) {
            mSubtitle = in.readCharSequence();
        }
        if (in.readInt() != 0) {
            mIsLaunchIntoPip = in.readBoolean();
        }
    }

    /** {@hide} */
    PictureInPictureParams(Rational aspectRatio, Rational expandedAspectRatio,
            List<RemoteAction> actions, RemoteAction closeAction, Rect sourceRectHint,
            Boolean autoEnterEnabled, Boolean seamlessResizeEnabled, CharSequence title,
            CharSequence subtitle, Boolean isLaunchIntoPip) {
        mAspectRatio = aspectRatio;
        mExpandedAspectRatio = expandedAspectRatio;
        mUserActions = actions;
        mCloseAction = closeAction;
        mSourceRectHint = sourceRectHint;
        mAutoEnterEnabled = autoEnterEnabled;
        mSeamlessResizeEnabled = seamlessResizeEnabled;
        mTitle = title;
        mSubtitle = subtitle;
        mIsLaunchIntoPip = isLaunchIntoPip;
    }

    /**
     * Makes a copy from the other picture-in-picture args.
     * @hide
     */
    public PictureInPictureParams(PictureInPictureParams other) {
        this(other.mAspectRatio, other.mExpandedAspectRatio, other.mUserActions, other.mCloseAction,
                other.hasSourceBoundsHint() ? new Rect(other.getSourceRectHint()) : null,
                other.mAutoEnterEnabled, other.mSeamlessResizeEnabled,
                other.mTitle, other.mSubtitle, other.mIsLaunchIntoPip);
    }

    /**
     * Copies the set parameters from the other picture-in-picture args.
     * @hide
     */
    public void copyOnlySet(PictureInPictureParams otherArgs) {
        if (otherArgs.hasSetAspectRatio()) {
            mAspectRatio = otherArgs.mAspectRatio;
        }

        // Copy either way because null can be used to explicitly unset the value
        mExpandedAspectRatio = otherArgs.mExpandedAspectRatio;

        if (otherArgs.hasSetActions()) {
            mUserActions = otherArgs.mUserActions;
        }
        if (otherArgs.hasSetCloseAction()) {
            mCloseAction = otherArgs.mCloseAction;
        }
        if (otherArgs.hasSourceBoundsHint()) {
            mSourceRectHint = new Rect(otherArgs.getSourceRectHint());
        }
        if (otherArgs.mAutoEnterEnabled != null) {
            mAutoEnterEnabled = otherArgs.mAutoEnterEnabled;
        }
        if (otherArgs.mSeamlessResizeEnabled != null) {
            mSeamlessResizeEnabled = otherArgs.mSeamlessResizeEnabled;
        }
        if (otherArgs.hasSetTitle()) {
            mTitle = otherArgs.mTitle;
        }
        if (otherArgs.hasSetSubtitle()) {
            mSubtitle = otherArgs.mSubtitle;
        }
        if (otherArgs.mIsLaunchIntoPip != null) {
            mIsLaunchIntoPip = otherArgs.mIsLaunchIntoPip;
        }
    }

    /**
     * @return the aspect ratio. If none is set, return 0.
     * @hide
     */
    @TestApi
    public float getAspectRatioFloat() {
        if (mAspectRatio != null) {
            return mAspectRatio.floatValue();
        }
        return 0f;
    }

    /**
     * Returns the expected aspect ratio of the picture-in-picture window.
     *
     * @return aspect ratio as the desired width / height or {@code null} if not set.
     * @see PictureInPictureParams.Builder#setAspectRatio(Rational)
     */
    @Nullable
    public Rational getAspectRatio() {
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
     * @return the expanded aspect ratio. If none is set, return 0.
     * @hide
     */
    @TestApi
    public float getExpandedAspectRatioFloat() {
        if (mExpandedAspectRatio != null) {
            return mExpandedAspectRatio.floatValue();
        }
        return 0f;
    }

    /**
     * Returns the desired aspect ratio of the expanded picture-in-picture window.
     *
     * @return aspect ratio as the desired width / height or {@code null} if not set.
     * @see PictureInPictureParams.Builder#setExpandedAspectRatio(Rational)
     */
    @Nullable
    public Rational getExpandedAspectRatio() {
        return mExpandedAspectRatio;
    }

    /**
     * @return whether the expanded aspect ratio is set
     * @hide
     */
    public boolean hasSetExpandedAspectRatio() {
        return mExpandedAspectRatio != null;
    }

    /**
     * Returns the list of user actions that are associated with the activity when in
     * picture-in-picture mode.
     *
     * @return the user actions in a new list.
     * @see PictureInPictureParams.Builder#setActions(List)
     */
    @NonNull
    public List<RemoteAction> getActions() {
        if (mUserActions == null) {
            return new ArrayList<>();
        }
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
     * Returns the action that is to replace the system close action.
     *
     * @return the close action or {@code null} if not set.
     * @see PictureInPictureParams.Builder#setCloseAction(RemoteAction)
     */
    @Nullable
    public RemoteAction getCloseAction() {
        return mCloseAction;
    }

    /**
     * @return whether the close action was set.
     * @hide
     */
    public boolean hasSetCloseAction() {
        return mCloseAction != null;
    }

    /**
     * Truncates the set of actions to the given {@param size}.
     *
     * @hide
     */
    public void truncateActions(int size) {
        if (hasSetActions()) {
            mUserActions = mUserActions.subList(0, Math.min(mUserActions.size(), size));
        }
    }

    /**
     * Returns the source rect hint.
     *
     * @return the source rect hint also known as launch bounds or {@code null} if not set.
     * @see PictureInPictureParams.Builder#setSourceRectHint(Rect)
     */
    @Nullable
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

    /**
     * Returns whether auto enter picture-in-picture is enabled.
     *
     * @return {@code true} if the system will automatically put the activity in
     * picture-in-picture mode.
     * @see PictureInPictureParams.Builder#setAutoEnterEnabled(boolean)
     */
    public boolean isAutoEnterEnabled() {
        return mAutoEnterEnabled == null ? false : mAutoEnterEnabled;
    }

    /**
     * Returns whether seamless resize is enabled.
     *
     * @return true if the system can seamlessly resize the window while activity is in
     * picture-in-picture mode.
     * @see PictureInPictureParams.Builder#setSeamlessResizeEnabled(boolean)
     */
    public boolean isSeamlessResizeEnabled() {
        return mSeamlessResizeEnabled == null ? true : mSeamlessResizeEnabled;
    }

    /**
     * @return whether a title was set.
     * @hide
     */
    public boolean hasSetTitle() {
        return mTitle != null;
    }

    /**
     * Returns the title of the picture-in-picture window that may be displayed to the user.
     *
     * @return title of the picture-in-picture window.
     * @see PictureInPictureParams.Builder#setTitle(CharSequence)
     */
    @Nullable
    public CharSequence getTitle() {
        return mTitle;
    }

    /**
     * @return whether a subtitle was set.
     * @hide
     */
    public boolean hasSetSubtitle() {
        return mSubtitle != null;
    }

    /**
     * Returns the subtitle of the picture-in-picture window that may be displayed to the user.
     *
     * @return subtitle of the picture-in-picture window.
     * @see PictureInPictureParams.Builder#setSubtitle(CharSequence)
     */
    @Nullable
    public CharSequence getSubtitle() {
        return mSubtitle;
    }

    /**
     * @return whether this {@link PictureInPictureParams} represents a launch into pip request.
     * @hide
     */
    public boolean isLaunchIntoPip() {
        return mIsLaunchIntoPip == null ? false : mIsLaunchIntoPip;
    }

    /**
     * @return True if no parameters are set
     * @hide
     */
    public boolean empty() {
        return !hasSourceBoundsHint() && !hasSetActions() && !hasSetCloseAction()
                && !hasSetAspectRatio() && !hasSetExpandedAspectRatio() && mAutoEnterEnabled == null
                && mSeamlessResizeEnabled == null && !hasSetTitle()
                && !hasSetSubtitle() && mIsLaunchIntoPip == null;
    }

    /**
     * Compare a given {@link Rect} against the aspect ratio, with rounding error tolerance.
     * @param bounds The {@link Rect} represents the source rect hint, this check is not needed
     *               if app provides a null source rect hint.
     * @param aspectRatio {@link Rational} representation of aspect ratio, this check is not needed
     *                    if app provides a null aspect ratio.
     * @return {@code true} if the given {@link Rect} matches the aspect ratio.
     * @hide
     */
    @SuppressWarnings("UnflaggedApi")
    @TestApi
    public static boolean isSameAspectRatio(@NonNull Rect bounds, @NonNull Rational aspectRatio) {
        // Validations
        if (bounds.isEmpty() || aspectRatio.floatValue() <= 0) {
            return false;
        }
        // Check against both the width and height.
        final int exactWidth = (aspectRatio.getNumerator() * bounds.height())
                / aspectRatio.getDenominator();
        if (Math.abs(exactWidth - bounds.width()) <= 1) {
            return true;
        }
        final int exactHeight = (aspectRatio.getDenominator() * bounds.width())
                / aspectRatio.getNumerator();
        return Math.abs(exactHeight - bounds.height()) <= 1;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PictureInPictureParams)) return false;
        PictureInPictureParams that = (PictureInPictureParams) o;
        return Objects.equals(mAutoEnterEnabled, that.mAutoEnterEnabled)
                && Objects.equals(mSeamlessResizeEnabled, that.mSeamlessResizeEnabled)
                && Objects.equals(mAspectRatio, that.mAspectRatio)
                && Objects.equals(mExpandedAspectRatio, that.mExpandedAspectRatio)
                && Objects.equals(mUserActions, that.mUserActions)
                && Objects.equals(mCloseAction, that.mCloseAction)
                && Objects.equals(mSourceRectHint, that.mSourceRectHint)
                && Objects.equals(mTitle, that.mTitle)
                && Objects.equals(mSubtitle, that.mSubtitle)
                && Objects.equals(mIsLaunchIntoPip, that.mIsLaunchIntoPip);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAspectRatio, mExpandedAspectRatio, mUserActions, mCloseAction,
                mSourceRectHint, mAutoEnterEnabled, mSeamlessResizeEnabled, mTitle, mSubtitle,
                mIsLaunchIntoPip);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        writeRationalToParcel(mAspectRatio, out);
        writeRationalToParcel(mExpandedAspectRatio, out);
        if (mUserActions != null) {
            out.writeInt(1);
            out.writeTypedList(mUserActions, 0);
        } else {
            out.writeInt(0);
        }

        out.writeTypedObject(mCloseAction, 0);

        if (mSourceRectHint != null) {
            out.writeInt(1);
            mSourceRectHint.writeToParcel(out, 0);
        } else {
            out.writeInt(0);
        }
        if (mAutoEnterEnabled != null) {
            out.writeInt(1);
            out.writeBoolean(mAutoEnterEnabled);
        } else {
            out.writeInt(0);
        }
        if (mSeamlessResizeEnabled != null) {
            out.writeInt(1);
            out.writeBoolean(mSeamlessResizeEnabled);
        } else {
            out.writeInt(0);
        }
        if (mTitle != null) {
            out.writeInt(1);
            out.writeCharSequence(mTitle);
        } else {
            out.writeInt(0);
        }
        if (mSubtitle != null) {
            out.writeInt(1);
            out.writeCharSequence(mSubtitle);
        } else {
            out.writeInt(0);
        }
        if (mIsLaunchIntoPip != null) {
            out.writeInt(1);
            out.writeBoolean(mIsLaunchIntoPip);
        } else {
            out.writeInt(0);
        }
    }

    private void writeRationalToParcel(Rational rational, Parcel out) {
        if (rational != null) {
            out.writeInt(1);
            out.writeInt(rational.getNumerator());
            out.writeInt(rational.getDenominator());
        } else {
            out.writeInt(0);
        }
    }

    private Rational readRationalFromParcel(Parcel in) {
        if (in.readInt() != 0) {
            return new Rational(in.readInt(), in.readInt());
        }
        return null;
    }

    @Override
    public String toString() {
        return "PictureInPictureParams("
                + " aspectRatio=" + getAspectRatio()
                + " expandedAspectRatio=" + mExpandedAspectRatio
                + " sourceRectHint=" + getSourceRectHint()
                + " hasSetActions=" + hasSetActions()
                + " hasSetCloseAction=" + hasSetCloseAction()
                + " isAutoPipEnabled=" + isAutoEnterEnabled()
                + " isSeamlessResizeEnabled=" + isSeamlessResizeEnabled()
                + " title=" + getTitle()
                + " subtitle=" + getSubtitle()
                + " isLaunchIntoPip=" + isLaunchIntoPip()
                + ")";
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
