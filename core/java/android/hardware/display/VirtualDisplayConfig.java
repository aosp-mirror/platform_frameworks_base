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

package android.hardware.display;

import static android.view.Display.DEFAULT_DISPLAY;

import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.hardware.display.DisplayManager.VirtualDisplayFlag;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.view.Display;
import android.view.Surface;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Holds configuration used to create {@link VirtualDisplay} instances.
 *
 * @see DisplayManager#createVirtualDisplay(VirtualDisplayConfig, Handler, VirtualDisplay.Callback)
 * @see MediaProjection#createVirtualDisplay(String, int, int, int, int, Surface,
 * VirtualDisplay.Callback, Handler)
 */
public final class VirtualDisplayConfig implements Parcelable {

    private final String mName;
    private final int mWidth;
    private final int mHeight;
    private final int mDensityDpi;
    private final int mFlags;
    private final Surface mSurface;
    private final String mUniqueId;
    private final int mDisplayIdToMirror;
    private final boolean mWindowManagerMirroringEnabled;
    private ArraySet<String> mDisplayCategories = null;
    private final float mRequestedRefreshRate;
    private final boolean mIsHomeSupported;

    private VirtualDisplayConfig(
            @NonNull String name,
            @IntRange(from = 1) int width,
            @IntRange(from = 1) int height,
            @IntRange(from = 1) int densityDpi,
            @VirtualDisplayFlag int flags,
            @Nullable Surface surface,
            @Nullable String uniqueId,
            int displayIdToMirror,
            boolean windowManagerMirroringEnabled,
            @NonNull ArraySet<String> displayCategories,
            float requestedRefreshRate,
            boolean isHomeSupported) {
        mName = name;
        mWidth = width;
        mHeight = height;
        mDensityDpi = densityDpi;
        mFlags = flags;
        mSurface = surface;
        mUniqueId = uniqueId;
        mDisplayIdToMirror = displayIdToMirror;
        mWindowManagerMirroringEnabled = windowManagerMirroringEnabled;
        mDisplayCategories = displayCategories;
        mRequestedRefreshRate = requestedRefreshRate;
        mIsHomeSupported = isHomeSupported;
    }

    /**
     * Returns the name of the virtual display.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Returns the width of the virtual display in pixels.
     */
    public int getWidth() {
        return mWidth;
    }

    /**
     * Returns the height of the virtual display in pixels.
     */
    public int getHeight() {
        return mHeight;
    }

    /**
     * Returns the density of the virtual display in dpi.
     */
    public int getDensityDpi() {
        return mDensityDpi;
    }

    /**
     * Returns the virtual display flags.
     *
     * @see Builder#setFlags
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Returns the surface to which the content of the virtual display should be rendered, if any.
     *
     * @see Builder#setSurface
     */
    @Nullable
    public Surface getSurface() {
        return mSurface;
    }

    /**
     * Returns the unique identifier for the display. Shouldn't be displayed to the user.
     * @hide
     */
    @Nullable
    public String getUniqueId() {
        return mUniqueId;
    }

    /**
     * Returns the id of the display that the virtual display should mirror, or
     * {@link android.view.Display#DEFAULT_DISPLAY} if there is none.
     * @hide
     */
    public int getDisplayIdToMirror() {
        return mDisplayIdToMirror;
    }

    /**
     * Whether if WindowManager is responsible for mirroring content to this VirtualDisplay, or
     * if DisplayManager should record contents instead.
     * @hide
     */
    public boolean isWindowManagerMirroringEnabled() {
        return mWindowManagerMirroringEnabled;
    }

    /**
     * Whether this virtual display supports showing home activity and wallpaper.
     *
     * @see Builder#setHomeSupported
     * @hide
     */
    @FlaggedApi(android.companion.virtual.flags.Flags.FLAG_VDM_CUSTOM_HOME)
    @SystemApi
    public boolean isHomeSupported() {
        return android.companion.virtual.flags.Flags.vdmCustomHome() && mIsHomeSupported;
    }

    /**
     * Returns the display categories.
     *
     * @see Builder#setDisplayCategories
     */
    @NonNull
    public Set<String> getDisplayCategories() {
        return Collections.unmodifiableSet(mDisplayCategories);
    }

    /**
     * Returns the refresh rate of a virtual display in frames per second, or zero if it is using a
     * default refresh rate chosen by the system.
     *
     * @see Builder#setRequestedRefreshRate
     */
    public float getRequestedRefreshRate() {
        return mRequestedRefreshRate;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mName);
        dest.writeInt(mWidth);
        dest.writeInt(mHeight);
        dest.writeInt(mDensityDpi);
        dest.writeInt(mFlags);
        dest.writeTypedObject(mSurface, flags);
        dest.writeString8(mUniqueId);
        dest.writeInt(mDisplayIdToMirror);
        dest.writeBoolean(mWindowManagerMirroringEnabled);
        dest.writeArraySet(mDisplayCategories);
        dest.writeFloat(mRequestedRefreshRate);
        dest.writeBoolean(mIsHomeSupported);
    }

    @Override
    public int describeContents() { return 0; }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VirtualDisplayConfig)) {
            return false;
        }
        VirtualDisplayConfig that = (VirtualDisplayConfig) o;
        return Objects.equals(mName, that.mName)
                && mWidth == that.mWidth
                && mHeight == that.mHeight
                && mDensityDpi == that.mDensityDpi
                && mFlags == that.mFlags
                && Objects.equals(mSurface, that.mSurface)
                && Objects.equals(mUniqueId, that.mUniqueId)
                && mDisplayIdToMirror == that.mDisplayIdToMirror
                && mWindowManagerMirroringEnabled == that.mWindowManagerMirroringEnabled
                && Objects.equals(mDisplayCategories, that.mDisplayCategories)
                && mRequestedRefreshRate == that.mRequestedRefreshRate
                && mIsHomeSupported == that.mIsHomeSupported;
    }

    @Override
    public int hashCode() {
        int hashCode = Objects.hash(
                mName, mWidth, mHeight, mDensityDpi, mFlags, mSurface, mUniqueId,
                mDisplayIdToMirror, mWindowManagerMirroringEnabled, mDisplayCategories,
                mRequestedRefreshRate, mIsHomeSupported);
        return hashCode;
    }

    @Override
    @NonNull
    public String toString() {
        return "VirtualDisplayConfig("
                + " mName=" + mName
                + " mHeight=" + mHeight
                + " mWidth=" + mWidth
                + " mDensityDpi=" + mDensityDpi
                + " mFlags=" + mFlags
                + " mSurface=" + mSurface
                + " mUniqueId=" + mUniqueId
                + " mDisplayIdToMirror=" + mDisplayIdToMirror
                + " mWindowManagerMirroringEnabled=" + mWindowManagerMirroringEnabled
                + " mDisplayCategories=" + mDisplayCategories
                + " mRequestedRefreshRate=" + mRequestedRefreshRate
                + " mIsHomeSupported=" + mIsHomeSupported
                + ")";
    }

    private VirtualDisplayConfig(@NonNull Parcel in) {
        mName = in.readString8();
        mWidth = in.readInt();
        mHeight = in.readInt();
        mDensityDpi = in.readInt();
        mFlags = in.readInt();
        mSurface = in.readTypedObject(Surface.CREATOR);
        mUniqueId = in.readString8();
        mDisplayIdToMirror = in.readInt();
        mWindowManagerMirroringEnabled = in.readBoolean();
        mDisplayCategories = (ArraySet<String>) in.readArraySet(null);
        mRequestedRefreshRate = in.readFloat();
        mIsHomeSupported = in.readBoolean();
    }

    @NonNull
    public static final Parcelable.Creator<VirtualDisplayConfig> CREATOR
            = new Parcelable.Creator<VirtualDisplayConfig>() {
        @Override
        public VirtualDisplayConfig[] newArray(int size) {
            return new VirtualDisplayConfig[size];
        }

        @Override
        public VirtualDisplayConfig createFromParcel(@NonNull Parcel in) {
            return new VirtualDisplayConfig(in);
        }
    };

    /**
     * A builder for {@link VirtualDisplayConfig}.
     */
    public static final class Builder {
        private final String mName;
        private final int mWidth;
        private final int mHeight;
        private final int mDensityDpi;
        private int mFlags = 0;
        private Surface mSurface = null;
        private String mUniqueId = null;
        private int mDisplayIdToMirror = DEFAULT_DISPLAY;
        private boolean mWindowManagerMirroringEnabled = false;
        private ArraySet<String> mDisplayCategories = new ArraySet<>();
        private float mRequestedRefreshRate = 0.0f;
        private boolean mIsHomeSupported = false;

        /**
         * Creates a new Builder.
         *
         * @param name The name of the virtual display, must be non-empty.
         * @param width The width of the virtual display in pixels. Must be greater than 0.
         * @param height The height of the virtual display in pixels. Must be greater than 0.
         * @param densityDpi The density of the virtual display in dpi. Must be greater than 0.
         */
        public Builder(
                @NonNull String name,
                @IntRange(from = 1) int width,
                @IntRange(from = 1) int height,
                @IntRange(from = 1) int densityDpi) {
            if (name == null) {
                throw new IllegalArgumentException("Virtual display name is required");
            }
            if (width <= 0) {
                throw new IllegalArgumentException("Virtual display width must be positive");
            }
            if (height <= 0) {
                throw new IllegalArgumentException("Virtual display height must be positive");
            }
            if (densityDpi <= 0) {
                throw new IllegalArgumentException("Virtual display density must be positive");
            }
            mName = name;
            mWidth = width;
            mHeight = height;
            mDensityDpi = densityDpi;
        }

        /**
         * Sets the virtual display flags, a combination of
         * {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_PUBLIC},
         * {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_PRESENTATION},
         * {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_SECURE},
         * {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY},
         * or {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR}.
         */
        @NonNull
        public Builder setFlags(@VirtualDisplayFlag int flags) {
            mFlags = flags;
            return this;
        }

        /**
         * Sets the surface to which the content of the virtual display should be rendered.
         *
         * <p>The surface can also be set after the display creation using
         * {@link VirtualDisplay#setSurface(Surface)}.
         */
        @NonNull
        public Builder setSurface(@Nullable Surface surface) {
            mSurface = surface;
            return this;
        }

        /**
         * Sets the unique identifier for the display.
         * @hide
         */
        @NonNull
        public Builder setUniqueId(@Nullable String uniqueId) {
            mUniqueId = uniqueId;
            return this;
        }

        /**
         * Sets the id of the display that the virtual display should mirror.
         * @hide
         */
        @NonNull
        public Builder setDisplayIdToMirror(int displayIdToMirror) {
            mDisplayIdToMirror = displayIdToMirror;
            return this;
        }

        /**
         * Sets whether WindowManager is responsible for mirroring content to this VirtualDisplay.
         * If unset or false, DisplayManager should record contents instead.
         * @hide
         */
        @NonNull
        public Builder setWindowManagerMirroringEnabled(boolean windowManagerMirroringEnabled) {
            mWindowManagerMirroringEnabled = windowManagerMirroringEnabled;
            return this;
        }

        /**
         * Sets the display categories.
         *
         * <p>The categories of the display indicate the type of activities allowed to run on that
         * display. Activities can declare a display category using
         * {@link android.content.pm.ActivityInfo#requiredDisplayCategory}.
         */
        @NonNull
        public Builder setDisplayCategories(@NonNull Set<String> displayCategories) {
            mDisplayCategories.clear();
            mDisplayCategories.addAll(Objects.requireNonNull(displayCategories));
            return this;
        }

        /**
         * Adds a display category.
         *
         * @see #setDisplayCategories
         */
        @NonNull
        public Builder addDisplayCategory(@NonNull String displayCategory) {
            mDisplayCategories.add(Objects.requireNonNull(displayCategory));
            return this;
        }

        /**
         * Sets the refresh rate of a virtual display in frames per second.
         *
         * <p>For best results, specify a divisor of the physical refresh rate, e.g., 30 or 60 on
         * a 120hz display. If an arbitrary refresh rate is specified, the rate will be rounded up
         * to a divisor of the physical display. If unset or zero, the virtual display will be
         * refreshed at the physical display refresh rate.
         *
         * @see Display#getRefreshRate()
         */
        @NonNull
        public Builder setRequestedRefreshRate(
                @FloatRange(from = 0.0f) float requestedRefreshRate) {
            if (requestedRefreshRate < 0.0f) {
                throw new IllegalArgumentException(
                        "Virtual display requested refresh rate must be non-negative");
            }
            mRequestedRefreshRate = requestedRefreshRate;
            return this;
        }

        /**
         * Sets whether this display supports showing home activities and wallpaper.
         *
         * <p>If set to {@code true}, then the home activity relevant to this display will be
         * automatically launched upon the display creation. If unset or set to {@code false}, the
         * display will not host any activities upon creation.</p>
         *
         * <p>Note: setting to {@code true} requires the display to be trusted. If the display is
         * not trusted, this property is ignored.</p>
         *
         * @param isHomeSupported whether home activities are supported on the display
         * @see DisplayManager#VIRTUAL_DISPLAY_FLAG_TRUSTED
         * @hide
         */
        @FlaggedApi(android.companion.virtual.flags.Flags.FLAG_VDM_CUSTOM_HOME)
        @SystemApi
        @NonNull
        public Builder setHomeSupported(boolean isHomeSupported) {
            mIsHomeSupported = isHomeSupported;
            return this;
        }

        /**
         * Builds the {@link VirtualDisplayConfig} instance.
         */
        @NonNull
        public VirtualDisplayConfig build() {
            return new VirtualDisplayConfig(
                    mName,
                    mWidth,
                    mHeight,
                    mDensityDpi,
                    mFlags,
                    mSurface,
                    mUniqueId,
                    mDisplayIdToMirror,
                    mWindowManagerMirroringEnabled,
                    mDisplayCategories,
                    mRequestedRefreshRate,
                    mIsHomeSupported);
        }
    }
}
