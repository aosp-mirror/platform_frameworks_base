/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Stores Camera Compat information about a particular Task.
 * @hide
 */
public class CameraCompatTaskInfo implements Parcelable {
    /**
     * Camera compat control isn't shown because it's not requested by heuristics.
     */
    public static final int CAMERA_COMPAT_CONTROL_HIDDEN = 0;

    /**
     * Camera compat control is shown with the treatment suggested.
     */
    public static final int CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED = 1;

    /**
     * Camera compat control is shown to allow reverting the applied treatment.
     */
    public static final int CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED = 2;

    /**
     * Camera compat control is dismissed by user.
     */
    public static final int CAMERA_COMPAT_CONTROL_DISMISSED = 3;

    /**
     * Enum for the Camera app compat control states.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "CAMERA_COMPAT_CONTROL_" }, value = {
            CAMERA_COMPAT_CONTROL_HIDDEN,
            CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED,
            CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED,
            CAMERA_COMPAT_CONTROL_DISMISSED,
    })
    public @interface CameraCompatControlState {}

    /**
     * State of the Camera app compat control which is used to correct stretched viewfinder
     * in apps that don't handle all possible configurations and changes between them correctly.
     */
    @CameraCompatControlState
    public int cameraCompatControlState = CAMERA_COMPAT_CONTROL_HIDDEN;

    /**
     * The value to use when no camera compat treatment should be applied to a windowed task.
     */
    public static final int CAMERA_COMPAT_FREEFORM_NONE = 0;

    /**
     * The value to use when portrait camera compat treatment should be applied to a windowed task.
     */
    public static final int CAMERA_COMPAT_FREEFORM_PORTRAIT = 1;

    /**
     * The value to use when landscape camera compat treatment should be applied to a windowed task.
     */
    public static final int CAMERA_COMPAT_FREEFORM_LANDSCAPE = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "CAMERA_COMPAT_FREEFORM_" }, value = {
            CAMERA_COMPAT_FREEFORM_NONE,
            CAMERA_COMPAT_FREEFORM_PORTRAIT,
            CAMERA_COMPAT_FREEFORM_LANDSCAPE,
    })
    public @interface FreeformCameraCompatMode {}

    /**
     * Whether the camera activity is letterboxed in freeform windowing mode to emulate expected
     * aspect ratio for fixed-orientation apps.
     *
     * <p>This field is used by the WM and the camera framework, to coordinate camera compat mode
     * setup.
     */
    @FreeformCameraCompatMode
    public int freeformCameraCompatMode;

    private CameraCompatTaskInfo() {
        // Do nothing
    }

    @NonNull
    static CameraCompatTaskInfo create() {
        return new CameraCompatTaskInfo();
    }

    private CameraCompatTaskInfo(Parcel source) {
        readFromParcel(source);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<CameraCompatTaskInfo> CREATOR =
            new Creator<>() {
                @Override
                public CameraCompatTaskInfo createFromParcel(Parcel in) {
                    return new CameraCompatTaskInfo(in);
                }

                @Override
                public CameraCompatTaskInfo[] newArray(int size) {
                    return new CameraCompatTaskInfo[size];
                }
            };

    /**
     * Reads the CameraCompatTaskInfo from a parcel.
     */
    void readFromParcel(Parcel source) {
        cameraCompatControlState = source.readInt();
        freeformCameraCompatMode = source.readInt();
    }

    /**
     * Writes the CameraCompatTaskInfo to a parcel.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(cameraCompatControlState);
        dest.writeInt(freeformCameraCompatMode);
    }

    /**
     * @return {@value true} if the task has camera compat controls.
     */
    public boolean hasCameraCompatControl() {
        return cameraCompatControlState != CAMERA_COMPAT_CONTROL_HIDDEN
                && cameraCompatControlState != CAMERA_COMPAT_CONTROL_DISMISSED;
    }

    /**
     * @return {@value true} if the task has some compat ui.
     */
    public boolean hasCameraCompatUI() {
        return hasCameraCompatControl();
    }

    /**
     * @return  {@code true} if the camera compat parameters that are important for task organizers
     * are equal.
     */
    public boolean equalsForTaskOrganizer(@Nullable CameraCompatTaskInfo that) {
        if (that == null) {
            return false;
        }
        return freeformCameraCompatMode == that.freeformCameraCompatMode;
    }

    /**
     * @return {@code true} if parameters that are important for size compat have changed.
     */
    public boolean equalsForCompatUi(@Nullable CameraCompatTaskInfo that) {
        if (that == null) {
            return false;
        }
        return cameraCompatControlState == that.cameraCompatControlState
                && freeformCameraCompatMode == that.freeformCameraCompatMode;
    }

    @Override
    public String toString() {
        return "CameraCompatTaskInfo { cameraCompatControlState="
                + cameraCompatControlStateToString(cameraCompatControlState)
                + " freeformCameraCompatMode="
                + freeformCameraCompatModeToString(freeformCameraCompatMode)
                + "}";
    }

    /** Human readable version of the camera control state. */
    @NonNull
    public static String cameraCompatControlStateToString(
            @CameraCompatControlState int cameraCompatControlState) {
        return switch (cameraCompatControlState) {
            case CAMERA_COMPAT_CONTROL_HIDDEN -> "hidden";
            case CAMERA_COMPAT_CONTROL_TREATMENT_SUGGESTED -> "treatment-suggested";
            case CAMERA_COMPAT_CONTROL_TREATMENT_APPLIED -> "treatment-applied";
            case CAMERA_COMPAT_CONTROL_DISMISSED -> "dismissed";
            default -> throw new AssertionError(
                    "Unexpected camera compat control state: " + cameraCompatControlState);
        };
    }

    /** Human readable version of the freeform camera compat mode. */
    @NonNull
    public static String freeformCameraCompatModeToString(
            @FreeformCameraCompatMode int freeformCameraCompatMode) {
        return switch (freeformCameraCompatMode) {
            case CAMERA_COMPAT_FREEFORM_NONE -> "inactive";
            case CAMERA_COMPAT_FREEFORM_PORTRAIT -> "portrait";
            case CAMERA_COMPAT_FREEFORM_LANDSCAPE -> "landscape";
            default -> throw new AssertionError(
                    "Unexpected camera compat mode: " + freeformCameraCompatMode);
        };
    }
}
