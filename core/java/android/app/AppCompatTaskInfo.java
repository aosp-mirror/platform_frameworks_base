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

package android.app;

import static android.app.TaskInfo.PROPERTY_VALUE_UNSET;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Stores App Compat information about a particular Task.
 * @hide
 */
public class AppCompatTaskInfo implements Parcelable {
    /**
     * If {@link #isLetterboxDoubleTapEnabled} it contains the current letterbox vertical position
     * or {@link TaskInfo#PROPERTY_VALUE_UNSET} otherwise.
     */
    public int topActivityLetterboxVerticalPosition = PROPERTY_VALUE_UNSET;

    /**
     * If {@link #isLetterboxDoubleTapEnabled} it contains the current letterbox vertical position
     * or {@link TaskInfo#PROPERTY_VALUE_UNSET} otherwise.
     */
    public int topActivityLetterboxHorizontalPosition = PROPERTY_VALUE_UNSET;

    /**
     * If {@link #isLetterboxDoubleTapEnabled} it contains the current width of the letterboxed
     * activity or {@link TaskInfo#PROPERTY_VALUE_UNSET} otherwise.
     */
    public int topActivityLetterboxWidth = PROPERTY_VALUE_UNSET;

    /**
     * If {@link #isLetterboxDoubleTapEnabled} it contains the current height of the letterboxed
     * activity or {@link TaskInfo#PROPERTY_VALUE_UNSET} otherwise.
     */
    public int topActivityLetterboxHeight = PROPERTY_VALUE_UNSET;

    /**
     * Contains the current app height of the letterboxed activity if available or
     * {@link TaskInfo#PROPERTY_VALUE_UNSET} otherwise.
     */
    public int topActivityLetterboxAppHeight = PROPERTY_VALUE_UNSET;

    /**
     * Contains the current app  width of the letterboxed activity if available or
     * {@link TaskInfo#PROPERTY_VALUE_UNSET} otherwise.
     */
    public int topActivityLetterboxAppWidth = PROPERTY_VALUE_UNSET;

    /**
     * Stores camera-related app compat information about a particular Task.
     */
    public CameraCompatTaskInfo cameraCompatTaskInfo = CameraCompatTaskInfo.create();

    /** Constant indicating no top activity flag has been set. */
    private static final int FLAG_UNDEFINED = 0x0;
    /** Constant base value for top activity flag. */
    private static final int FLAG_BASE = 0x1;
    /** Top activity flag for whether letterbox education is enabled. */
    private static final int FLAG_LETTERBOX_EDU_ENABLED = FLAG_BASE;
    /** Top activity flag for whether activity is eligible for letterbox education. */
    private static final int FLAG_ELIGIBLE_FOR_LETTERBOX_EDU = FLAG_BASE << 1;
    /** Top activity flag for whether activity bounds are letterboxed. */
    private static final int FLAG_LETTERBOXED = FLAG_BASE << 2;
    /** Top activity flag for whether activity is in size compat mode. */
    private static final int FLAG_IN_SIZE_COMPAT = FLAG_BASE << 3;
    /** Top activity flag for whether letterbox double tap is enabled. */
    private static final int FLAG_LETTERBOX_DOUBLE_TAP_ENABLED = FLAG_BASE << 4;
    /** Top activity flag for whether the update comes from a letterbox double tap action. */
    private static final int FLAG_IS_FROM_LETTERBOX_DOUBLE_TAP = FLAG_BASE << 5;
    /** Top activity flag for whether activity is eligible for user aspect ratio button. */
    private static final int FLAG_ELIGIBLE_FOR_USER_ASPECT_RATIO_BUTTON = FLAG_BASE << 6;
    /** Top activity flag for whether has activity has been overridden to fullscreen by system. */
    private static final int FLAG_FULLSCREEN_OVERRIDE_SYSTEM = FLAG_BASE << 7;
    /** Top activity flag for whether has activity has been overridden to fullscreen by user. */
    private static final int FLAG_FULLSCREEN_OVERRIDE_USER = FLAG_BASE << 8;
    /** Top activity flag for whether min aspect ratio of the activity has been overridden.*/
    public static final int FLAG_HAS_MIN_ASPECT_RATIO_OVERRIDE = FLAG_BASE << 9;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            FLAG_UNDEFINED,
            FLAG_BASE,
            FLAG_LETTERBOX_EDU_ENABLED,
            FLAG_ELIGIBLE_FOR_LETTERBOX_EDU,
            FLAG_LETTERBOXED,
            FLAG_IN_SIZE_COMPAT,
            FLAG_LETTERBOX_DOUBLE_TAP_ENABLED,
            FLAG_IS_FROM_LETTERBOX_DOUBLE_TAP,
            FLAG_ELIGIBLE_FOR_USER_ASPECT_RATIO_BUTTON,
            FLAG_FULLSCREEN_OVERRIDE_SYSTEM,
            FLAG_FULLSCREEN_OVERRIDE_USER,
            FLAG_HAS_MIN_ASPECT_RATIO_OVERRIDE
    })
    public @interface TopActivityFlag {}

    @TopActivityFlag
    private int mTopActivityFlags;

    @TopActivityFlag
    private static final int FLAGS_ORGANIZER_INTERESTED = FLAG_IS_FROM_LETTERBOX_DOUBLE_TAP
            | FLAG_ELIGIBLE_FOR_USER_ASPECT_RATIO_BUTTON | FLAG_FULLSCREEN_OVERRIDE_SYSTEM
            | FLAG_FULLSCREEN_OVERRIDE_USER | FLAG_HAS_MIN_ASPECT_RATIO_OVERRIDE;

    @TopActivityFlag
    private static final int FLAGS_COMPAT_UI_INTERESTED = FLAGS_ORGANIZER_INTERESTED
            | FLAG_IN_SIZE_COMPAT | FLAG_ELIGIBLE_FOR_LETTERBOX_EDU | FLAG_LETTERBOX_EDU_ENABLED;

    private AppCompatTaskInfo() {
        // Do nothing
    }

    @NonNull
    static AppCompatTaskInfo create() {
        return new AppCompatTaskInfo();
    }

    private AppCompatTaskInfo(Parcel source) {
        readFromParcel(source);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<AppCompatTaskInfo> CREATOR =
            new Creator<>() {
                @Override
                public AppCompatTaskInfo createFromParcel(Parcel in) {
                    return new AppCompatTaskInfo(in);
                }

                @Override
                public AppCompatTaskInfo[] newArray(int size) {
                    return new AppCompatTaskInfo[size];
                }
            };

    /**
     * @return {@code true} if the task has some compat ui.
     */
    public boolean hasCompatUI() {
        return isTopActivityInSizeCompat() || eligibleForLetterboxEducation()
                || isLetterboxDoubleTapEnabled() || eligibleForUserAspectRatioButton();
    }

    /**
     * @return {@code true} if top activity is pillarboxed.
     */
    public boolean isTopActivityPillarboxed() {
        return topActivityLetterboxWidth < topActivityLetterboxHeight;
    }

    /**
     * @return {@code true} if the letterbox education is enabled.
     */
    public boolean isLetterboxEducationEnabled() {
        return isTopActivityFlagEnabled(FLAG_LETTERBOX_EDU_ENABLED);
    }

    /**
     * Sets the top activity flag for whether letterbox education is enabled.
     */
    public void setLetterboxEducationEnabled(boolean enable) {
        setTopActivityFlag(FLAG_LETTERBOX_EDU_ENABLED, enable);
    }

    /**
     * @return {@code true} if the direct top activity is eligible for letterbox education.
     */
    public boolean eligibleForLetterboxEducation() {
        return isTopActivityFlagEnabled(FLAG_ELIGIBLE_FOR_LETTERBOX_EDU);
    }

    /**
     * Sets the top activity flag to be eligible for letterbox education.
     */
    public void setEligibleForLetterboxEducation(boolean enable) {
        setTopActivityFlag(FLAG_ELIGIBLE_FOR_LETTERBOX_EDU, enable);
    }

    /**
     * @return {@code true} if the direct top activity is eligible for the user aspect ratio
     * settings button.
     */
    public boolean eligibleForUserAspectRatioButton() {
        return isTopActivityFlagEnabled(FLAG_ELIGIBLE_FOR_USER_ASPECT_RATIO_BUTTON);
    }

    /**
     * Sets the top activity flag to be eligible for the user aspect ratio settings button.
     */
    public void setEligibleForUserAspectRatioButton(boolean enable) {
        setTopActivityFlag(FLAG_ELIGIBLE_FOR_USER_ASPECT_RATIO_BUTTON, enable);
    }

    /**
     * @return {@code true} if double tap to reposition letterboxed app is enabled.
     */
    public boolean isLetterboxDoubleTapEnabled() {
        return isTopActivityFlagEnabled(FLAG_LETTERBOX_DOUBLE_TAP_ENABLED);
    }

    /**
     * Sets the top activity flag to enable double tap to reposition letterboxed app.
     */
    public void setLetterboxDoubleTapEnabled(boolean enable) {
        setTopActivityFlag(FLAG_LETTERBOX_DOUBLE_TAP_ENABLED, enable);
    }

    /**
     * @return {@code true} if the update comes from a letterbox double-tap action from the user.
     */
    public boolean isFromLetterboxDoubleTap() {
        return isTopActivityFlagEnabled(FLAG_IS_FROM_LETTERBOX_DOUBLE_TAP);
    }

    /**
     * Sets the top activity flag for whether the update comes from a letterbox double-tap action
     * from the user.
     */
    public void setIsFromLetterboxDoubleTap(boolean enable) {
        setTopActivityFlag(FLAG_IS_FROM_LETTERBOX_DOUBLE_TAP, enable);
    }

    /**
     * @return {@code true} if the user has forced the activity to be fullscreen through the
     * user aspect ratio settings.
     */
    public boolean isUserFullscreenOverrideEnabled() {
        return isTopActivityFlagEnabled(FLAG_FULLSCREEN_OVERRIDE_USER);
    }

    /**
     * Sets the top activity flag for whether the user has forced the activity to be fullscreen
     * through the user aspect ratio settings.
     */
    public void setUserFullscreenOverrideEnabled(boolean enable) {
        setTopActivityFlag(FLAG_FULLSCREEN_OVERRIDE_USER, enable);
    }

    /**
     * @return {@code true} if the system has forced the activity to be fullscreen.
     */
    public boolean isSystemFullscreenOverrideEnabled() {
        return isTopActivityFlagEnabled(FLAG_FULLSCREEN_OVERRIDE_SYSTEM);
    }

    /**
     * Sets the top activity flag for whether the system has forced the activity to be fullscreen.
     */
    public void setSystemFullscreenOverrideEnabled(boolean enable) {
        setTopActivityFlag(FLAG_FULLSCREEN_OVERRIDE_SYSTEM, enable);
    }

    /**
     * @return {@code true} if the direct top activity is in size compat mode on foreground.
     */
    public boolean isTopActivityInSizeCompat() {
        return isTopActivityFlagEnabled(FLAG_IN_SIZE_COMPAT);
    }

    /**
     * Sets the top activity flag for whether the direct top activity is in size compat mode
     * on foreground.
     */
    public void setTopActivityInSizeCompat(boolean enable) {
        setTopActivityFlag(FLAG_IN_SIZE_COMPAT, enable);
    }

    /**
     * @return {@code true} if the top activity bounds are letterboxed.
     */
    public boolean isTopActivityLetterboxed() {
        return isTopActivityFlagEnabled(FLAG_LETTERBOXED);
    }

    /**
     * Sets the top activity flag for whether the top activity bounds are letterboxed.
     */
    public void setTopActivityLetterboxed(boolean enable) {
        setTopActivityFlag(FLAG_LETTERBOXED, enable);
    }

    /**
     * @return {@code true} if the top activity's min aspect ratio has been overridden.
     */
    public boolean hasMinAspectRatioOverride() {
        return isTopActivityFlagEnabled(FLAG_HAS_MIN_ASPECT_RATIO_OVERRIDE);
    }

    /**
     * Sets the top activity flag for whether the min aspect ratio of the activity has been
     * overridden.
     */
    public void setHasMinAspectRatioOverride(boolean enable) {
        setTopActivityFlag(FLAG_HAS_MIN_ASPECT_RATIO_OVERRIDE, enable);
    }

    /** Clear all top activity flags and set to false. */
    public void clearTopActivityFlags() {
        mTopActivityFlags = FLAG_UNDEFINED;
    }

    /**
     * @return {@code true} if the app compat parameters that are important for task organizers
     * are equal.
     */
    public boolean equalsForTaskOrganizer(@Nullable AppCompatTaskInfo that) {
        if (that == null) {
            return false;
        }
        return (mTopActivityFlags & FLAGS_ORGANIZER_INTERESTED)
                    == (that.mTopActivityFlags & FLAGS_ORGANIZER_INTERESTED)
                && topActivityLetterboxVerticalPosition == that.topActivityLetterboxVerticalPosition
                && topActivityLetterboxWidth == that.topActivityLetterboxWidth
                && topActivityLetterboxHeight == that.topActivityLetterboxHeight
                && topActivityLetterboxAppWidth == that.topActivityLetterboxAppWidth
                && topActivityLetterboxAppHeight == that.topActivityLetterboxAppHeight
                && topActivityLetterboxHorizontalPosition
                    == that.topActivityLetterboxHorizontalPosition
                && cameraCompatTaskInfo.equalsForTaskOrganizer(that.cameraCompatTaskInfo);
    }

    /**
     * @return {@code true} if parameters that are important for size compat have changed.
     */
    public boolean equalsForCompatUi(@Nullable AppCompatTaskInfo that) {
        if (that == null) {
            return false;
        }
        return (mTopActivityFlags & FLAGS_COMPAT_UI_INTERESTED)
                    == (that.mTopActivityFlags & FLAGS_COMPAT_UI_INTERESTED)
                && topActivityLetterboxVerticalPosition == that.topActivityLetterboxVerticalPosition
                && topActivityLetterboxHorizontalPosition
                    == that.topActivityLetterboxHorizontalPosition
                && topActivityLetterboxWidth == that.topActivityLetterboxWidth
                && topActivityLetterboxHeight == that.topActivityLetterboxHeight
                && topActivityLetterboxAppWidth == that.topActivityLetterboxAppWidth
                && topActivityLetterboxAppHeight == that.topActivityLetterboxAppHeight
                && cameraCompatTaskInfo.equalsForCompatUi(that.cameraCompatTaskInfo);
    }

    /**
     * Reads the AppCompatTaskInfo from a parcel.
     */
    void readFromParcel(Parcel source) {
        mTopActivityFlags = source.readInt();
        topActivityLetterboxVerticalPosition = source.readInt();
        topActivityLetterboxHorizontalPosition = source.readInt();
        topActivityLetterboxWidth = source.readInt();
        topActivityLetterboxHeight = source.readInt();
        topActivityLetterboxAppWidth = source.readInt();
        topActivityLetterboxAppHeight = source.readInt();
        cameraCompatTaskInfo = source.readTypedObject(CameraCompatTaskInfo.CREATOR);
    }

    /**
     * Writes the AppCompatTaskInfo to a parcel.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mTopActivityFlags);
        dest.writeInt(topActivityLetterboxVerticalPosition);
        dest.writeInt(topActivityLetterboxHorizontalPosition);
        dest.writeInt(topActivityLetterboxWidth);
        dest.writeInt(topActivityLetterboxHeight);
        dest.writeInt(topActivityLetterboxAppWidth);
        dest.writeInt(topActivityLetterboxAppHeight);
        dest.writeTypedObject(cameraCompatTaskInfo, flags);
    }

    @Override
    public String toString() {
        return "AppCompatTaskInfo { topActivityInSizeCompat=" + isTopActivityInSizeCompat()
                + " eligibleForLetterboxEducation= " + eligibleForLetterboxEducation()
                + " isLetterboxEducationEnabled= " + isLetterboxEducationEnabled()
                + " isLetterboxDoubleTapEnabled= " + isLetterboxDoubleTapEnabled()
                + " eligibleForUserAspectRatioButton= " + eligibleForUserAspectRatioButton()
                + " topActivityBoundsLetterboxed= " + isTopActivityLetterboxed()
                + " isFromLetterboxDoubleTap= " + isFromLetterboxDoubleTap()
                + " topActivityLetterboxVerticalPosition= " + topActivityLetterboxVerticalPosition
                + " topActivityLetterboxHorizontalPosition= "
                + topActivityLetterboxHorizontalPosition
                + " topActivityLetterboxWidth=" + topActivityLetterboxWidth
                + " topActivityLetterboxHeight=" + topActivityLetterboxHeight
                + " topActivityLetterboxAppWidth=" + topActivityLetterboxAppWidth
                + " topActivityLetterboxAppHeight=" + topActivityLetterboxAppHeight
                + " isUserFullscreenOverrideEnabled=" + isUserFullscreenOverrideEnabled()
                + " isSystemFullscreenOverrideEnabled=" + isSystemFullscreenOverrideEnabled()
                + " hasMinAspectRatioOverride=" + hasMinAspectRatioOverride()
                + " cameraCompatTaskInfo=" + cameraCompatTaskInfo.toString()
                + "}";
    }

    private void setTopActivityFlag(@TopActivityFlag int flag, boolean enable) {
        mTopActivityFlags = enable ? (mTopActivityFlags | flag) : (mTopActivityFlags & ~flag);
    }

    private boolean isTopActivityFlagEnabled(@TopActivityFlag int flag) {
        return (mTopActivityFlags & flag) == flag;
    }
}
