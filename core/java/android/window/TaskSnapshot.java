/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Surface;
import android.view.WindowInsetsController;

/**
 * Represents a task snapshot.
 * @hide
 */
public class TaskSnapshot implements Parcelable {
    // Identifier of this snapshot
    private final long mId;
    // Top activity in task when snapshot was taken
    private final ComponentName mTopActivityComponent;
    private final HardwareBuffer mSnapshot;
    /** Indicates whether task was in landscape or portrait */
    @Configuration.Orientation
    private final int mOrientation;
    /** See {@link android.view.Surface.Rotation} */
    @Surface.Rotation
    private final int mRotation;
    /** The size of the snapshot before scaling */
    private final Point mTaskSize;
    private final Rect mContentInsets;
    private final Rect mLetterboxInsets;
    // Whether this snapshot is a down-sampled version of the high resolution snapshot, used
    // mainly for loading snapshots quickly from disk when user is flinging fast
    private final boolean mIsLowResolution;
    // Whether or not the snapshot is a real snapshot or an app-theme generated snapshot due to
    // the task having a secure window or having previews disabled
    private final boolean mIsRealSnapshot;
    private final int mWindowingMode;
    private final @WindowInsetsController.Appearance
    int mAppearance;
    private final boolean mIsTranslucent;
    private final boolean mHasImeSurface;
    // Must be one of the named color spaces, otherwise, always use SRGB color space.
    private final ColorSpace mColorSpace;

    public TaskSnapshot(long id,
            @NonNull ComponentName topActivityComponent, HardwareBuffer snapshot,
            @NonNull ColorSpace colorSpace, int orientation, int rotation, Point taskSize,
            Rect contentInsets, Rect letterboxInsets, boolean isLowResolution,
            boolean isRealSnapshot, int windowingMode,
            @WindowInsetsController.Appearance int appearance, boolean isTranslucent,
            boolean hasImeSurface) {
        mId = id;
        mTopActivityComponent = topActivityComponent;
        mSnapshot = snapshot;
        mColorSpace = colorSpace.getId() < 0
                ? ColorSpace.get(ColorSpace.Named.SRGB) : colorSpace;
        mOrientation = orientation;
        mRotation = rotation;
        mTaskSize = new Point(taskSize);
        mContentInsets = new Rect(contentInsets);
        mLetterboxInsets = new Rect(letterboxInsets);
        mIsLowResolution = isLowResolution;
        mIsRealSnapshot = isRealSnapshot;
        mWindowingMode = windowingMode;
        mAppearance = appearance;
        mIsTranslucent = isTranslucent;
        mHasImeSurface = hasImeSurface;
    }

    private TaskSnapshot(Parcel source) {
        mId = source.readLong();
        mTopActivityComponent = ComponentName.readFromParcel(source);
        mSnapshot = source.readTypedObject(HardwareBuffer.CREATOR);
        int colorSpaceId = source.readInt();
        mColorSpace = colorSpaceId >= 0 && colorSpaceId < ColorSpace.Named.values().length
                ? ColorSpace.get(ColorSpace.Named.values()[colorSpaceId])
                : ColorSpace.get(ColorSpace.Named.SRGB);
        mOrientation = source.readInt();
        mRotation = source.readInt();
        mTaskSize = source.readTypedObject(Point.CREATOR);
        mContentInsets = source.readTypedObject(Rect.CREATOR);
        mLetterboxInsets = source.readTypedObject(Rect.CREATOR);
        mIsLowResolution = source.readBoolean();
        mIsRealSnapshot = source.readBoolean();
        mWindowingMode = source.readInt();
        mAppearance = source.readInt();
        mIsTranslucent = source.readBoolean();
        mHasImeSurface = source.readBoolean();
    }

    /**
     * @return Identifier of this snapshot.
     */
    public long getId() {
        return mId;
    }

    /**
     * @return The top activity component for the task at the point this snapshot was taken.
     */
    public ComponentName getTopActivityComponent() {
        return mTopActivityComponent;
    }

    /**
     * @return The graphic buffer representing the screenshot.
     *
     * Note: Prefer {@link #getHardwareBuffer}, which returns the internal object. This version
     * creates a new object.
     */
    @UnsupportedAppUsage
    public GraphicBuffer getSnapshot() {
        return GraphicBuffer.createFromHardwareBuffer(mSnapshot);
    }

    /**
     * @return The hardware buffer representing the screenshot.
     */
    public HardwareBuffer getHardwareBuffer() {
        return mSnapshot;
    }

    /**
     * @return The color space of hardware buffer representing the screenshot.
     */
    public ColorSpace getColorSpace() {
        return mColorSpace;
    }

    /**
     * @return The screen orientation the screenshot was taken in.
     */
    @UnsupportedAppUsage
    public int getOrientation() {
        return mOrientation;
    }

    /**
     * @return The screen rotation the screenshot was taken in.
     */
    public int getRotation() {
        return mRotation;
    }

    /**
     * @return The size of the task at the point this snapshot was taken.
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public Point getTaskSize() {
        return mTaskSize;
    }

    /**
     * @return The system/content insets on the snapshot. These can be clipped off in order to
     *         remove any areas behind system bars in the snapshot.
     */
    @UnsupportedAppUsage
    public Rect getContentInsets() {
        return mContentInsets;
    }

    /**
     * @return The letterbox insets on the snapshot. These can be clipped off in order to
     *         remove any letterbox areas in the snapshot.
     */
    public Rect getLetterboxInsets() {
        return mLetterboxInsets;
    }

    /**
     * @return Whether this snapshot is a down-sampled version of the full resolution.
     */
    @UnsupportedAppUsage
    public boolean isLowResolution() {
        return mIsLowResolution;
    }

    /**
     * @return Whether or not the snapshot is a real snapshot or an app-theme generated snapshot
     * due to the task having a secure window or having previews disabled.
     */
    @UnsupportedAppUsage
    public boolean isRealSnapshot() {
        return mIsRealSnapshot;
    }

    /**
     * @return Whether or not the snapshot is of a translucent app window (non-fullscreen or has
     * a non-opaque pixel format).
     */
    public boolean isTranslucent() {
        return mIsTranslucent;
    }

    /**
     * @return Whether or not the snapshot has the IME surface.
     */
    public boolean hasImeSurface() {
        return mHasImeSurface;
    }

    /**
     * @return The windowing mode of the task when this snapshot was taken.
     */
    public int getWindowingMode() {
        return mWindowingMode;
    }

    /**
     * @return The {@link WindowInsetsController.Appearance} flags for the top most visible
     *         fullscreen window at the time that the snapshot was taken.
     */
    public @WindowInsetsController.Appearance
    int getAppearance() {
        return mAppearance;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mId);
        ComponentName.writeToParcel(mTopActivityComponent, dest);
        dest.writeTypedObject(mSnapshot != null && !mSnapshot.isClosed() ? mSnapshot : null, 0);
        dest.writeInt(mColorSpace.getId());
        dest.writeInt(mOrientation);
        dest.writeInt(mRotation);
        dest.writeTypedObject(mTaskSize, 0);
        dest.writeTypedObject(mContentInsets, 0);
        dest.writeTypedObject(mLetterboxInsets, 0);
        dest.writeBoolean(mIsLowResolution);
        dest.writeBoolean(mIsRealSnapshot);
        dest.writeInt(mWindowingMode);
        dest.writeInt(mAppearance);
        dest.writeBoolean(mIsTranslucent);
        dest.writeBoolean(mHasImeSurface);
    }

    @Override
    public String toString() {
        final int width = mSnapshot != null ? mSnapshot.getWidth() : 0;
        final int height = mSnapshot != null ? mSnapshot.getHeight() : 0;
        return "TaskSnapshot{"
                + " mId=" + mId
                + " mTopActivityComponent=" + mTopActivityComponent.flattenToShortString()
                + " mSnapshot=" + mSnapshot + " (" + width + "x" + height + ")"
                + " mColorSpace=" + mColorSpace.toString()
                + " mOrientation=" + mOrientation
                + " mRotation=" + mRotation
                + " mTaskSize=" + mTaskSize.toString()
                + " mContentInsets=" + mContentInsets.toShortString()
                + " mLetterboxInsets=" + mLetterboxInsets.toShortString()
                + " mIsLowResolution=" + mIsLowResolution
                + " mIsRealSnapshot=" + mIsRealSnapshot
                + " mWindowingMode=" + mWindowingMode
                + " mAppearance=" + mAppearance
                + " mIsTranslucent=" + mIsTranslucent
                + " mHasImeSurface=" + mHasImeSurface;
    }

    public static final @NonNull Creator<TaskSnapshot> CREATOR = new Creator<TaskSnapshot>() {
        public TaskSnapshot createFromParcel(Parcel source) {
            return new TaskSnapshot(source);
        }
        public TaskSnapshot[] newArray(int size) {
            return new TaskSnapshot[size];
        }
    };

    /** Builder for a {@link TaskSnapshot} object */
    public static final class Builder {
        private long mId;
        private ComponentName mTopActivity;
        private HardwareBuffer mSnapshot;
        private ColorSpace mColorSpace;
        private int mOrientation;
        private int mRotation;
        private Point mTaskSize;
        private Rect mContentInsets;
        private Rect mLetterboxInsets;
        private boolean mIsRealSnapshot;
        private int mWindowingMode;
        private @WindowInsetsController.Appearance
        int mAppearance;
        private boolean mIsTranslucent;
        private boolean mHasImeSurface;
        private int mPixelFormat;

        public Builder setId(long id) {
            mId = id;
            return this;
        }

        public Builder setTopActivityComponent(ComponentName name) {
            mTopActivity = name;
            return this;
        }

        public Builder setSnapshot(HardwareBuffer buffer) {
            mSnapshot = buffer;
            return this;
        }

        public Builder setColorSpace(ColorSpace colorSpace) {
            mColorSpace = colorSpace;
            return this;
        }

        public Builder setOrientation(int orientation) {
            mOrientation = orientation;
            return this;
        }

        public Builder setRotation(int rotation) {
            mRotation = rotation;
            return this;
        }

        /**
         * Sets the original size of the task
         */
        public Builder setTaskSize(Point size) {
            mTaskSize = size;
            return this;
        }

        public Builder setContentInsets(Rect contentInsets) {
            mContentInsets = contentInsets;
            return this;
        }

        public Builder setLetterboxInsets(Rect letterboxInsets) {
            mLetterboxInsets = letterboxInsets;
            return this;
        }

        public Builder setIsRealSnapshot(boolean realSnapshot) {
            mIsRealSnapshot = realSnapshot;
            return this;
        }

        public Builder setWindowingMode(int windowingMode) {
            mWindowingMode = windowingMode;
            return this;
        }

        public Builder setAppearance(@WindowInsetsController.Appearance int appearance) {
            mAppearance = appearance;
            return this;
        }

        public Builder setIsTranslucent(boolean isTranslucent) {
            mIsTranslucent = isTranslucent;
            return this;
        }

        /**
         * Sets the IME visibility when taking the snapshot of the task.
         */
        public Builder setHasImeSurface(boolean hasImeSurface) {
            mHasImeSurface = hasImeSurface;
            return this;
        }

        public int getPixelFormat() {
            return mPixelFormat;
        }

        public Builder setPixelFormat(int pixelFormat) {
            mPixelFormat = pixelFormat;
            return this;
        }

        public TaskSnapshot build() {
            return new TaskSnapshot(
                    mId,
                    mTopActivity,
                    mSnapshot,
                    mColorSpace,
                    mOrientation,
                    mRotation,
                    mTaskSize,
                    mContentInsets,
                    mLetterboxInsets,
                    // When building a TaskSnapshot with the Builder class, isLowResolution
                    // is always false. Low-res snapshots are only created when loading from
                    // disk.
                    false /* isLowResolution */,
                    mIsRealSnapshot,
                    mWindowingMode,
                    mAppearance,
                    mIsTranslucent,
                    mHasImeSurface);

        }
    }
}
