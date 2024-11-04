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
     * The value to use when no camera compat treatment should be applied to a windowed task.
     */
    public static final int CAMERA_COMPAT_FREEFORM_NONE = 0;

    /**
     * The value to use when camera compat treatment should be applied to an activity requesting
     * portrait orientation, while a device is in landscape. Applies only to freeform tasks.
     */
    public static final int CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_LANDSCAPE = 1;

    /**
     * The value to use when camera compat treatment should be applied to an activity requesting
     * landscape orientation, while a device is in landscape. Applies only to freeform tasks.
     */
    public static final int CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_LANDSCAPE = 2;

    /**
     * The value to use when camera compat treatment should be applied to an activity requesting
     * portrait orientation, while a device is in portrait. Applies only to freeform tasks.
     */
    public static final int CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_PORTRAIT = 3;

    /**
     * The value to use when camera compat treatment should be applied to an activity requesting
     * landscape orientation, while a device is in portrait. Applies only to freeform tasks.
     */
    public static final int CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_PORTRAIT = 4;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "CAMERA_COMPAT_FREEFORM_" }, value = {
            CAMERA_COMPAT_FREEFORM_NONE,
            CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_LANDSCAPE,
            CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_LANDSCAPE,
            CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_PORTRAIT,
            CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_PORTRAIT,
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
        freeformCameraCompatMode = source.readInt();
    }

    /**
     * Writes the CameraCompatTaskInfo to a parcel.
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(freeformCameraCompatMode);
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
        return freeformCameraCompatMode == that.freeformCameraCompatMode;
    }

    @Override
    public String toString() {
        return "CameraCompatTaskInfo { freeformCameraCompatMode="
                + freeformCameraCompatModeToString(freeformCameraCompatMode)
                + "}";
    }

    /** Human readable version of the freeform camera compat mode. */
    @NonNull
    public static String freeformCameraCompatModeToString(
            @FreeformCameraCompatMode int freeformCameraCompatMode) {
        return switch (freeformCameraCompatMode) {
            case CAMERA_COMPAT_FREEFORM_NONE -> "inactive";
            case CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_LANDSCAPE ->
                    "app-portrait-device-landscape";
            case CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_LANDSCAPE ->
                    "app-landscape-device-landscape";
            case CAMERA_COMPAT_FREEFORM_PORTRAIT_DEVICE_IN_PORTRAIT ->
                    "app-portrait-device-portrait";
            case CAMERA_COMPAT_FREEFORM_LANDSCAPE_DEVICE_IN_PORTRAIT ->
                    "app-landscape-device-portrait";
            default -> throw new AssertionError(
                    "Unexpected camera compat mode: " + freeformCameraCompatMode);
        };
    }
}
