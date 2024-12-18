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

package com.android.internal.util;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.os.UserHandle.USER_NULL;
import static android.view.WindowManager.TAKE_SCREENSHOT_FULLSCREEN;
import static android.view.WindowManager.TAKE_SCREENSHOT_PROVIDED_IMAGE;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Insets;
import android.graphics.ParcelableColorSpace;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

import java.util.Objects;

/**
 * Describes a screenshot request.
 */
public class ScreenshotRequest implements Parcelable {
    private static final String TAG = "ScreenshotRequest";

    @WindowManager.ScreenshotType
    private final int mType;
    @WindowManager.ScreenshotSource
    private final int mSource;
    private final ComponentName mTopComponent;
    private final int mTaskId;
    private final int mUserId;
    private final Bitmap mBitmap;
    private final Rect mBoundsInScreen;
    private final Insets mInsets;
    private final int mDisplayId;

    private ScreenshotRequest(
            @WindowManager.ScreenshotType int type, @WindowManager.ScreenshotSource int source,
            ComponentName topComponent, int taskId, int userId,
            Bitmap bitmap, Rect boundsInScreen, Insets insets, int displayId) {
        mType = type;
        mSource = source;
        mTopComponent = topComponent;
        mTaskId = taskId;
        mUserId = userId;
        mBitmap = bitmap;
        mBoundsInScreen = boundsInScreen;
        mInsets = insets;
        mDisplayId = displayId;
    }

    ScreenshotRequest(Parcel in) {
        mType = in.readInt();
        mSource = in.readInt();
        mTopComponent = in.readTypedObject(ComponentName.CREATOR);
        mTaskId = in.readInt();
        mUserId = in.readInt();
        mBitmap = HardwareBitmapBundler.bundleToHardwareBitmap(in.readTypedObject(Bundle.CREATOR));
        mBoundsInScreen = in.readTypedObject(Rect.CREATOR);
        mInsets = in.readTypedObject(Insets.CREATOR);
        mDisplayId = in.readInt();
    }

    @WindowManager.ScreenshotType
    public int getType() {
        return mType;
    }

    @WindowManager.ScreenshotSource
    public int getSource() {
        return mSource;
    }

    public Bitmap getBitmap() {
        return mBitmap;
    }

    public Rect getBoundsInScreen() {
        return mBoundsInScreen;
    }

    public Insets getInsets() {
        return mInsets;
    }

    public int getTaskId() {
        return mTaskId;
    }

    public int getUserId() {
        return mUserId;
    }

    public ComponentName getTopComponent() {
        return mTopComponent;
    }

    public int getDisplayId() {
        return mDisplayId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mType);
        dest.writeInt(mSource);
        dest.writeTypedObject(mTopComponent, 0);
        dest.writeInt(mTaskId);
        dest.writeInt(mUserId);
        dest.writeTypedObject(HardwareBitmapBundler.hardwareBitmapToBundle(mBitmap), 0);
        dest.writeTypedObject(mBoundsInScreen, 0);
        dest.writeTypedObject(mInsets, 0);
        dest.writeInt(mDisplayId);
    }

    @NonNull
    public static final Parcelable.Creator<ScreenshotRequest> CREATOR =
            new Parcelable.Creator<ScreenshotRequest>() {

                @Override
                public ScreenshotRequest createFromParcel(Parcel source) {
                    return new ScreenshotRequest(source);
                }

                @Override
                public ScreenshotRequest[] newArray(int size) {
                    return new ScreenshotRequest[size];
                }
            };

    /**
     * Builder class for {@link ScreenshotRequest} objects.
     */
    public static class Builder {
        @WindowManager.ScreenshotType
        private final int mType;

        @WindowManager.ScreenshotSource
        private final int mSource;

        private Bitmap mBitmap;
        private Rect mBoundsInScreen;
        private Insets mInsets = Insets.NONE;
        private int mTaskId = INVALID_TASK_ID;
        private int mUserId = USER_NULL;
        private ComponentName mTopComponent;
        private int mDisplayId = Display.INVALID_DISPLAY;

        /**
         * Begin building a ScreenshotRequest.
         *
         * @param type   The type of the screenshot request, defined by {@link
         *               WindowManager.ScreenshotType}
         * @param source The source of the screenshot request, defined by {@link
         *               WindowManager.ScreenshotSource}
         */
        public Builder(
                @WindowManager.ScreenshotType int type,
                @WindowManager.ScreenshotSource int source) {
            if (type != TAKE_SCREENSHOT_FULLSCREEN && type != TAKE_SCREENSHOT_PROVIDED_IMAGE) {
                throw new IllegalArgumentException("Invalid screenshot type requested!");
            }
            mType = type;
            mSource = source;
        }

        /**
         * Construct a new {@link ScreenshotRequest} with the set parameters.
         */
        public ScreenshotRequest build() {
            if (mType == TAKE_SCREENSHOT_FULLSCREEN && mBitmap != null) {
                Log.w(TAG, "Bitmap provided, but request is fullscreen. Bitmap will be ignored.");
            }
            if (mType == TAKE_SCREENSHOT_PROVIDED_IMAGE && mBitmap == null) {
                throw new IllegalStateException(
                        "Request is PROVIDED_IMAGE, but no bitmap is provided!");
            }

            return new ScreenshotRequest(mType, mSource, mTopComponent, mTaskId, mUserId, mBitmap,
                    mBoundsInScreen, mInsets, mDisplayId);
        }

        /**
         * Set the top component associated with this request.
         *
         * @param topComponent The component name of the top component running in the task.
         */
        public Builder setTopComponent(ComponentName topComponent) {
            mTopComponent = topComponent;
            return this;
        }

        /**
         * Set the task id associated with this request.
         *
         * @param taskId The taskId of the task that the screenshot was taken of.
         */
        public Builder setTaskId(int taskId) {
            mTaskId = taskId;
            return this;
        }

        /**
         * Set the user id associated with this request.
         *
         * @param userId The userId of user running the task provided in taskId.
         */
        public Builder setUserId(int userId) {
            mUserId = userId;
            return this;
        }

        /**
         * Set the bitmap associated with this request.
         *
         * @param bitmap The provided screenshot.
         */
        public Builder setBitmap(Bitmap bitmap) {
            mBitmap = bitmap;
            return this;
        }

        /**
         * Set the bounds for the provided bitmap.
         *
         * @param bounds The bounds in screen coordinates that the bitmap originated from.
         */
        public Builder setBoundsOnScreen(Rect bounds) {
            mBoundsInScreen = bounds;
            return this;
        }

        /**
         * Set the insets for the provided bitmap.
         *
         * @param insets The insets that the image was shown with, inside the screen bounds.
         */
        public Builder setInsets(@NonNull Insets insets) {
            mInsets = insets;
            return this;
        }

        /**
         * Set the display ID for this request.
         *
         * @param displayId see {@link Display}
         */
        public Builder setDisplayId(int displayId) {
            mDisplayId = displayId;
            return this;
        }
    }

    /**
     * Bundler used to convert between a hardware bitmap and a bundle without copying the internal
     * content. This is used together with a fully-defined ScreenshotRequest to handle a hardware
     * bitmap as a screenshot.
     */
    private static final class HardwareBitmapBundler {
        private static final String KEY_BUFFER = "bitmap_util_buffer";
        private static final String KEY_COLOR_SPACE = "bitmap_util_color_space";

        private HardwareBitmapBundler() {
        }

        /**
         * Creates a Bundle that represents the given Bitmap.
         * <p>The Bundle will contain a wrapped version of the Bitmaps HardwareBuffer, so will
         * avoid
         * copies when passing across processes, only pass to processes you trust.
         *
         * <p>Returns a new Bundle rather than modifying an exiting one to avoid key collisions,
         * the
         * returned Bundle should be treated as a standalone object.
         *
         * @param bitmap to convert to bundle
         * @return a Bundle representing the bitmap, should only be parsed by
         * {@link #bundleToHardwareBitmap(Bundle)}
         */
        private static Bundle hardwareBitmapToBundle(Bitmap bitmap) {
            if (bitmap == null) {
                return null;
            }
            if (bitmap.getConfig() != Bitmap.Config.HARDWARE) {
                throw new IllegalArgumentException(
                        "Passed bitmap must have hardware config, found: "
                                + bitmap.getConfig());
            }

            // Bitmap assumes SRGB for null color space
            ParcelableColorSpace colorSpace =
                    bitmap.getColorSpace() == null
                            ? new ParcelableColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
                            : new ParcelableColorSpace(bitmap.getColorSpace());

            Bundle bundle = new Bundle();
            bundle.putParcelable(KEY_BUFFER, bitmap.getHardwareBuffer());
            bundle.putParcelable(KEY_COLOR_SPACE, colorSpace);

            return bundle;
        }

        /**
         * Extracts the Bitmap added to a Bundle with {@link #hardwareBitmapToBundle(Bitmap)}.
         *
         * <p>This Bitmap contains the HardwareBuffer from the original caller, be careful
         * passing
         * this Bitmap on to any other source.
         *
         * @param bundle containing the bitmap
         * @return a hardware Bitmap
         */
        private static Bitmap bundleToHardwareBitmap(Bundle bundle) {
            if (bundle == null) {
                return null;
            }
            if (!bundle.containsKey(KEY_BUFFER) || !bundle.containsKey(KEY_COLOR_SPACE)) {
                throw new IllegalArgumentException("Bundle does not contain a hardware bitmap");
            }

            HardwareBuffer buffer = bundle.getParcelable(KEY_BUFFER, HardwareBuffer.class);
            ParcelableColorSpace colorSpace = bundle.getParcelable(KEY_COLOR_SPACE,
                    ParcelableColorSpace.class);

            return Bitmap.wrapHardwareBuffer(Objects.requireNonNull(buffer),
                    colorSpace.getColorSpace());
        }
    }
}
