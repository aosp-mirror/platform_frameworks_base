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

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.hardware.display.DisplayManager.VirtualDisplayFlag;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PowerManager;
import android.util.ArraySet;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Surface;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

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
    private final ArraySet<String> mDisplayCategories;
    private final float mRequestedRefreshRate;
    private final boolean mIsHomeSupported;
    private final DisplayCutout mDisplayCutout;
    private final boolean mIgnoreActivitySizeRestrictions;
    private final float mDefaultBrightness;
    private final float mDimBrightness;
    private final IBrightnessListener mBrightnessListener;

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
            boolean isHomeSupported,
            @Nullable DisplayCutout displayCutout,
            boolean ignoreActivitySizeRestrictions,
            @FloatRange(from = 0.0f, to = 1.0f) float defaultBrightness,
            @FloatRange(from = 0.0f, to = 1.0f) float dimBrightness,
            IBrightnessListener brightnessListener) {
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
        mDisplayCutout = displayCutout;
        mIgnoreActivitySizeRestrictions = ignoreActivitySizeRestrictions;
        mDefaultBrightness = defaultBrightness;
        mDimBrightness = dimBrightness;
        mBrightnessListener = brightnessListener;
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
     * Returns the cutout of this display.
     *
     * @return the cutout of the display or {@code null} if none is specified.
     *
     * @see Builder#setDisplayCutout
     * @hide
     */
    @FlaggedApi(android.companion.virtualdevice.flags.Flags.FLAG_VIRTUAL_DISPLAY_INSETS)
    @SystemApi
    @Nullable
    public DisplayCutout getDisplayCutout() {
        return mDisplayCutout;
    }

    /**
     * Returns the default brightness of the display.
     *
     * <p>Value of {@code 0.0} indicates the minimum supported brightness and value of {@code 1.0}
     * indicates the maximum supported brightness.</p>
     *
     * @see Builder#setDefaultBrightness(float)
     */
    @FlaggedApi(android.companion.virtualdevice.flags.Flags.FLAG_DEVICE_AWARE_DISPLAY_POWER)
    public @FloatRange(from = 0.0f, to = 1.0f) float getDefaultBrightness() {
        return mDefaultBrightness;
    }

    /**
     * Returns the dim brightness of the display.
     *
     * <p>Value of {@code 0.0} indicates the minimum supported brightness and value of {@code 1.0}
     * indicates the maximum supported brightness.</p>
     *
     * @see Builder#setDimBrightness(float)
     */
    @FlaggedApi(android.companion.virtualdevice.flags.Flags.FLAG_DEVICE_AWARE_DISPLAY_POWER)
    public @FloatRange(from = 0.0f, to = 1.0f) float getDimBrightness() {
        return mDimBrightness;
    }

    /**
     * Returns the listener to get notified about changes in the display brightness.
     * @hide
     */
    @Nullable
    public IBrightnessListener getBrightnessListener() {
        return mBrightnessListener;
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
     * Whether this virtual display ignores fixed orientation, aspect ratio and resizability
     * of apps.
     *
     * @see Builder#setIgnoreActivitySizeRestrictions(boolean)
     * @hide
     */
    @FlaggedApi(com.android.window.flags.Flags.FLAG_VDM_FORCE_APP_UNIVERSAL_RESIZABLE_API)
    @SystemApi
    public boolean isIgnoreActivitySizeRestrictions() {
        return mIgnoreActivitySizeRestrictions
                && com.android.window.flags.Flags.vdmForceAppUniversalResizableApi();
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
        DisplayCutout.ParcelableWrapper.writeCutoutToParcel(mDisplayCutout, dest, flags);
        dest.writeBoolean(mIgnoreActivitySizeRestrictions);
        dest.writeFloat(mDefaultBrightness);
        dest.writeFloat(mDimBrightness);
        dest.writeStrongBinder(mBrightnessListener != null ? mBrightnessListener.asBinder() : null);
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
                && mIsHomeSupported == that.mIsHomeSupported
                && mIgnoreActivitySizeRestrictions == that.mIgnoreActivitySizeRestrictions
                && Objects.equals(mDisplayCutout, that.mDisplayCutout)
                && mDefaultBrightness == that.mDefaultBrightness
                && mDimBrightness == that.mDimBrightness
                && Objects.equals(mBrightnessListener, that.mBrightnessListener);
    }

    @Override
    public int hashCode() {
        int hashCode = Objects.hash(
                mName, mWidth, mHeight, mDensityDpi, mFlags, mSurface, mUniqueId,
                mDisplayIdToMirror, mWindowManagerMirroringEnabled, mDisplayCategories,
                mRequestedRefreshRate, mIsHomeSupported, mDisplayCutout,
                mIgnoreActivitySizeRestrictions, mDefaultBrightness, mDimBrightness,
                mBrightnessListener);
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
                + " mDisplayCutout=" + mDisplayCutout
                + " mIgnoreActivitySizeRestrictions=" + mIgnoreActivitySizeRestrictions
                + " mDefaultBrightness=" + mDefaultBrightness
                + " mDimBrightness=" + mDimBrightness
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
        mDisplayCutout = DisplayCutout.ParcelableWrapper.readCutoutFromParcel(in);
        mIgnoreActivitySizeRestrictions = in.readBoolean();
        mDefaultBrightness = in.readFloat();
        mDimBrightness = in.readFloat();
        mBrightnessListener = IBrightnessListener.Stub.asInterface(in.readStrongBinder());
    }

    /**
     * Listener for display brightness changes.
     */
    @FlaggedApi(android.companion.virtualdevice.flags.Flags.FLAG_DEVICE_AWARE_DISPLAY_POWER)
    public interface BrightnessListener {

        /**
         * Called when the display's brightness has changed.
         *
         * @param brightness the new brightness of the display. Value of {@code 0.0} indicates the
         *   minimum supported brightness and value of {@code 1.0} indicates the maximum supported
         *   brightness.
         */
        void onBrightnessChanged(@FloatRange(from = 0.0f, to = 1.0f) float brightness);
    }

    private static class BrightnessListenerDelegate extends IBrightnessListener.Stub {

        @NonNull
        private final Executor mExecutor;
        @NonNull
        private final BrightnessListener mListener;

        BrightnessListenerDelegate(@NonNull @CallbackExecutor Executor executor,
                @NonNull BrightnessListener listener) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onBrightnessChanged(float brightness) {
            mExecutor.execute(() -> mListener.onBrightnessChanged(brightness));
        }
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
        private DisplayCutout mDisplayCutout = null;
        private boolean mIgnoreActivitySizeRestrictions = false;
        private float mDefaultBrightness = 0.0f;
        private float mDimBrightness = PowerManager.BRIGHTNESS_INVALID;
        private IBrightnessListener mBrightnessListener = null;

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
         * <p>Note: setting to {@code true} requires the display to be trusted and to not mirror
         * content of other displays. If the display is not trusted, or if it mirrors content of
         * other displays, this property is ignored.</p>
         *
         * @param isHomeSupported whether home activities are supported on the display
         * @see DisplayManager#VIRTUAL_DISPLAY_FLAG_TRUSTED
         * @see DisplayManager#VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
         * @see DisplayManager#VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
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
         * Sets the cutout of this display.
         *
         * @hide
         */
        @FlaggedApi(android.companion.virtualdevice.flags.Flags.FLAG_VIRTUAL_DISPLAY_INSETS)
        @SystemApi
        @NonNull
        public Builder setDisplayCutout(@Nullable DisplayCutout displayCutout) {
            mDisplayCutout = displayCutout;
            return this;
        }

        /**
         * Sets whether this display ignores fixed orientation, aspect ratio and resizability
         * of apps.
         *
         * <p>Note: setting to {@code true} requires the display to have
         * {@link DisplayManager#VIRTUAL_DISPLAY_FLAG_TRUSTED}. If this is false, this property
         * is ignored.</p>
         *
         * @hide
         */
        @FlaggedApi(com.android.window.flags.Flags.FLAG_VDM_FORCE_APP_UNIVERSAL_RESIZABLE_API)
        @SystemApi
        @NonNull
        public Builder setIgnoreActivitySizeRestrictions(boolean enabled) {
            mIgnoreActivitySizeRestrictions = enabled;
            return this;
        }

        /**
         * Sets the default brightness of the display.
         *
         * <p>The system will use this brightness value whenever the display should be bright, i.e.
         * it is powered on and not modified due to user activity or app activity.</p>
         *
         * <p>Value of {@code 0.0} indicates the minimum supported brightness and value of
         * {@code 1.0} indicates the maximum supported brightness.</p>
         *
         * <p>If unset, defaults to {@code 0.0}</p>
         *
         * @throws IllegalArgumentException if the brightness is outside the valid range [0.0, 1.0]
         * @see android.view.View#setKeepScreenOn(boolean)
         * @see #setBrightnessListener(Executor, BrightnessListener)
         */
        @FlaggedApi(android.companion.virtualdevice.flags.Flags.FLAG_DEVICE_AWARE_DISPLAY_POWER)
        @NonNull
        public Builder setDefaultBrightness(@FloatRange(from = 0.0f, to = 1.0f) float brightness) {
            if (!isValidBrightness(brightness)) {
                throw new IllegalArgumentException(
                        "Virtual display default brightness must be in range [0.0, 1.0]");
            }
            mDefaultBrightness = brightness;
            return this;
        }

        /**
         * Sets the dim brightness of the display.
         *
         * <p>The system will use this brightness value whenever the display should be dim, i.e.
         * it is powered on and dimmed due to user activity or app activity.</p>
         *
         * <p>Value of {@code 0.0} indicates the minimum supported brightness and value of
         * {@code 1.0} indicates the maximum supported brightness.</p>
         *
         * <p>If set, the default brightness must also be set to a value greater or equal to the
         * dim brightness. If unset, defaults to the system default.</p>
         *
         * @throws IllegalArgumentException if the brightness is outside the valid range [0.0, 1.0]
         * @see Builder#setDefaultBrightness(float)
         * @see #setBrightnessListener(Executor, BrightnessListener)
         */
        @FlaggedApi(android.companion.virtualdevice.flags.Flags.FLAG_DEVICE_AWARE_DISPLAY_POWER)
        @NonNull
        public Builder setDimBrightness(@FloatRange(from = 0.0f, to = 1.0f) float brightness) {
            if (!isValidBrightness(brightness)) {
                throw new IllegalArgumentException(
                        "Virtual display dim brightness must be in range [0.0, 1.0]");
            }
            mDimBrightness = brightness;
            return this;
        }

        /**
         * Sets the listener to get notified about changes in the display brightness.
         *
         * @param executor The executor where the callback is executed on.
         * @param listener The listener to get notified when the display brightness has changed.
         */
        @SuppressLint("MissingGetterMatchingBuilder") // The hidden getter returns the AIDL object
        @FlaggedApi(android.companion.virtualdevice.flags.Flags.FLAG_DEVICE_AWARE_DISPLAY_POWER)
        @NonNull
        public Builder setBrightnessListener(@NonNull @CallbackExecutor Executor executor,
                @NonNull BrightnessListener listener) {
            mBrightnessListener = new BrightnessListenerDelegate(
                    Objects.requireNonNull(executor), Objects.requireNonNull(listener));
            return this;
        }

        private boolean isValidBrightness(float brightness) {
            return !Float.isNaN(brightness) && PowerManager.BRIGHTNESS_MIN <= brightness
                    && brightness <= PowerManager.BRIGHTNESS_MAX;
        }

        /**
         * Builds the {@link VirtualDisplayConfig} instance.
         *
         * @throws IllegalArgumentException if the dim brightness is set to a value greater than
         *   the default brightness.
         */
        @NonNull
        public VirtualDisplayConfig build() {
            if (isValidBrightness(mDimBrightness) && mDimBrightness > mDefaultBrightness) {
                throw new IllegalArgumentException(
                        "The dim brightness must not be greater than the default brightness");
            }
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
                    mIsHomeSupported,
                    mDisplayCutout,
                    mIgnoreActivitySizeRestrictions,
                    mDefaultBrightness,
                    mDimBrightness,
                    mBrightnessListener);
        }
    }
}
