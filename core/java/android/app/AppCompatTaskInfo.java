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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Stores App Compat information about a particular Task.
 * @hide
 */
public class AppCompatTaskInfo implements Parcelable {
    /**
     * Whether the direct top activity is eligible for letterbox education.
     */
    public boolean topActivityEligibleForLetterboxEducation;

    /**
     * Whether the direct top activity is in size compat mode on foreground.
     */
    public boolean topActivityInSizeCompat;

    /**
     * Whether the double tap is enabled.
     */
    public boolean isLetterboxDoubleTapEnabled;

    /**
     * Whether the user aspect ratio settings button is enabled.
     */
    public boolean topActivityEligibleForUserAspectRatioButton;

    /**
     * Whether the user has forced the activity to be fullscreen through the user aspect ratio
     * settings.
     */
    public boolean isUserFullscreenOverrideEnabled;

    /**
     * Whether the system has forced the activity to be fullscreen
     */
    public boolean isSystemFullscreenOverrideEnabled;

    /**
     * Hint about the letterbox state of the top activity.
     */
    public boolean topActivityBoundsLetterboxed;

    /**
     * Whether the update comes from a letterbox double-tap action from the user or not.
     */
    public boolean isFromLetterboxDoubleTap;

    /**
     * If {@link isLetterboxDoubleTapEnabled} it contains the current letterbox vertical position or
     * {@link TaskInfo.PROPERTY_VALUE_UNSET} otherwise.
     */
    public int topActivityLetterboxVerticalPosition;

    /**
     * If {@link isLetterboxDoubleTapEnabled} it contains the current letterbox vertical position or
     * {@link TaskInfo.PROPERTY_VALUE_UNSET} otherwise.
     */
    public int topActivityLetterboxHorizontalPosition;

    /**
     * If {@link isLetterboxDoubleTapEnabled} it contains the current width of the letterboxed
     * activity or {@link TaskInfo.PROPERTY_VALUE_UNSET} otherwise.
     */
    public int topActivityLetterboxWidth;

    /**
     * If {@link isLetterboxDoubleTapEnabled} it contains the current height of the letterboxed
     * activity or {@link TaskInfo.PROPERTY_VALUE_UNSET} otherwise.
     */
    public int topActivityLetterboxHeight;

    /**
     * Stores camera-related app compat information about a particular Task.
     */
    public CameraCompatTaskInfo cameraCompatTaskInfo = CameraCompatTaskInfo.create();

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
     * @return {@value true} if the task has some compat ui.
     */
    public boolean hasCompatUI() {
        return cameraCompatTaskInfo.hasCameraCompatUI() || topActivityInSizeCompat
                || topActivityEligibleForLetterboxEducation
                || isLetterboxDoubleTapEnabled
                || topActivityEligibleForUserAspectRatioButton;
    }

    /**
     * @return {@value true} if top activity is pillarboxed.
     */
    public boolean isTopActivityPillarboxed() {
        return topActivityLetterboxWidth < topActivityLetterboxHeight;
    }

    /**
     * @return  {@code true} if the app compat parameters that are important for task organizers
     * are equal.
     */
    public boolean equalsForTaskOrganizer(@Nullable AppCompatTaskInfo that) {
        if (that == null) {
            return false;
        }
        return isFromLetterboxDoubleTap == that.isFromLetterboxDoubleTap
                && topActivityEligibleForUserAspectRatioButton
                    == that.topActivityEligibleForUserAspectRatioButton
                && topActivityLetterboxVerticalPosition == that.topActivityLetterboxVerticalPosition
                && topActivityLetterboxWidth == that.topActivityLetterboxWidth
                && topActivityLetterboxHeight == that.topActivityLetterboxHeight
                && topActivityLetterboxHorizontalPosition
                    == that.topActivityLetterboxHorizontalPosition
                && isUserFullscreenOverrideEnabled == that.isUserFullscreenOverrideEnabled
                && isSystemFullscreenOverrideEnabled == that.isSystemFullscreenOverrideEnabled
                && cameraCompatTaskInfo.equalsForTaskOrganizer(that.cameraCompatTaskInfo);
    }

    /**
     * @return {@code true} if parameters that are important for size compat have changed.
     */
    public boolean equalsForCompatUi(@Nullable AppCompatTaskInfo that) {
        if (that == null) {
            return false;
        }
        return topActivityInSizeCompat == that.topActivityInSizeCompat
                && isFromLetterboxDoubleTap == that.isFromLetterboxDoubleTap
                && topActivityEligibleForUserAspectRatioButton
                    == that.topActivityEligibleForUserAspectRatioButton
                && topActivityEligibleForLetterboxEducation
                    == that.topActivityEligibleForLetterboxEducation
                && topActivityLetterboxVerticalPosition == that.topActivityLetterboxVerticalPosition
                && topActivityLetterboxHorizontalPosition
                    == that.topActivityLetterboxHorizontalPosition
                && topActivityLetterboxWidth == that.topActivityLetterboxWidth
                && topActivityLetterboxHeight == that.topActivityLetterboxHeight
                && isUserFullscreenOverrideEnabled == that.isUserFullscreenOverrideEnabled
                && isSystemFullscreenOverrideEnabled == that.isSystemFullscreenOverrideEnabled
                && cameraCompatTaskInfo.equalsForCompatUi(that.cameraCompatTaskInfo);
    }

    /**
     * Reads the AppCompatTaskInfo from a parcel.
     */
    void readFromParcel(Parcel source) {
        topActivityInSizeCompat = source.readBoolean();
        topActivityEligibleForLetterboxEducation = source.readBoolean();
        isLetterboxDoubleTapEnabled = source.readBoolean();
        topActivityEligibleForUserAspectRatioButton = source.readBoolean();
        topActivityBoundsLetterboxed = source.readBoolean();
        isFromLetterboxDoubleTap = source.readBoolean();
        topActivityLetterboxVerticalPosition = source.readInt();
        topActivityLetterboxHorizontalPosition = source.readInt();
        topActivityLetterboxWidth = source.readInt();
        topActivityLetterboxHeight = source.readInt();
        isUserFullscreenOverrideEnabled = source.readBoolean();
        isSystemFullscreenOverrideEnabled = source.readBoolean();
        cameraCompatTaskInfo = source.readTypedObject(CameraCompatTaskInfo.CREATOR);
    }

    /**
     * Writes the AppCompatTaskInfo to a parcel.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(topActivityInSizeCompat);
        dest.writeBoolean(topActivityEligibleForLetterboxEducation);
        dest.writeBoolean(isLetterboxDoubleTapEnabled);
        dest.writeBoolean(topActivityEligibleForUserAspectRatioButton);
        dest.writeBoolean(topActivityBoundsLetterboxed);
        dest.writeBoolean(isFromLetterboxDoubleTap);
        dest.writeInt(topActivityLetterboxVerticalPosition);
        dest.writeInt(topActivityLetterboxHorizontalPosition);
        dest.writeInt(topActivityLetterboxWidth);
        dest.writeInt(topActivityLetterboxHeight);
        dest.writeBoolean(isUserFullscreenOverrideEnabled);
        dest.writeBoolean(isSystemFullscreenOverrideEnabled);
        dest.writeTypedObject(cameraCompatTaskInfo, flags);
    }

    @Override
    public String toString() {
        return "AppCompatTaskInfo { topActivityInSizeCompat=" + topActivityInSizeCompat
                + " topActivityEligibleForLetterboxEducation= "
                + topActivityEligibleForLetterboxEducation
                + " isLetterboxDoubleTapEnabled= " + isLetterboxDoubleTapEnabled
                + " topActivityEligibleForUserAspectRatioButton= "
                + topActivityEligibleForUserAspectRatioButton
                + " topActivityBoundsLetterboxed= " + topActivityBoundsLetterboxed
                + " isFromLetterboxDoubleTap= " + isFromLetterboxDoubleTap
                + " topActivityLetterboxVerticalPosition= " + topActivityLetterboxVerticalPosition
                + " topActivityLetterboxHorizontalPosition= "
                + topActivityLetterboxHorizontalPosition
                + " topActivityLetterboxWidth=" + topActivityLetterboxWidth
                + " topActivityLetterboxHeight=" + topActivityLetterboxHeight
                + " isUserFullscreenOverrideEnabled=" + isUserFullscreenOverrideEnabled
                + " isSystemFullscreenOverrideEnabled=" + isSystemFullscreenOverrideEnabled
                + " cameraCompatTaskInfo=" + cameraCompatTaskInfo.toString()
                + "}";
    }
}
