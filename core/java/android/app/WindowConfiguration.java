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

import static android.app.ActivityThread.isSystem;
import static android.app.WindowConfigurationProto.ACTIVITY_TYPE;
import static android.app.WindowConfigurationProto.APP_BOUNDS;
import static android.app.WindowConfigurationProto.BOUNDS;
import static android.app.WindowConfigurationProto.MAX_BOUNDS;
import static android.app.WindowConfigurationProto.WINDOWING_MODE;
import static android.view.Surface.rotationToString;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;
import android.util.proto.WireTypeMismatchException;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.WindowManager;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Class that contains windowing configuration/state for other objects that contain windows directly
 * or indirectly. E.g. Activities, Task, Displays, ...
 * The test class is {@link com.android.server.wm.WindowConfigurationTests} which must be kept
 * up-to-date and ran anytime changes are made to this class.
 * @hide
 */
@TestApi
@RavenwoodKeepWholeClass
public class WindowConfiguration implements Parcelable, Comparable<WindowConfiguration> {
    /**
     * bounds that can differ from app bounds, which may include things such as insets.
     *
     * TODO: Investigate combining with {@link #mAppBounds}. Can the latter be a product of the
     * former?
     */
    private final Rect mBounds = new Rect();

    /**
     * {@link android.graphics.Rect} defining app bounds. The dimensions override usages of
     * {@link DisplayInfo#appHeight} and {@link DisplayInfo#appWidth} and mirrors these values at
     * the display level. Lower levels can override these values to provide custom bounds to enforce
     * features such as a max aspect ratio.
     */
    @Nullable
    private Rect mAppBounds;

    /**
     * The maximum {@link Rect} bounds that an app can expect. It is used to report value of
     * {@link WindowManager#getMaximumWindowMetrics()}.
     */
    private final Rect mMaxBounds = new Rect();

    /**
     * The rotation of this window's apparent display. This can differ from mRotation in some
     * situations (like letterbox).
     */
    @Surface.Rotation
    private int mDisplayRotation = ROTATION_UNDEFINED;

    /**
     * The current rotation of this window container relative to the default
     * orientation of the display it is on (regardless of how deep in the hierarchy
     * it is). It is used by the configuration hierarchy to apply rotation-dependent
     * policy during bounds calculation.
     */
    private int mRotation = ROTATION_UNDEFINED;

    /** Rotation is not defined, use the parent containers rotation. */
    public static final int ROTATION_UNDEFINED = -1;

    /** The current windowing mode of the configuration. */
    private @WindowingMode int mWindowingMode;

    /** Windowing mode is currently not defined. */
    public static final int WINDOWING_MODE_UNDEFINED = 0;
    /** Occupies the full area of the screen or the parent container. */
    public static final int WINDOWING_MODE_FULLSCREEN = 1;
    /** Always on-top (always visible). of other siblings in its parent container. */
    public static final int WINDOWING_MODE_PINNED = 2;
    /** Can be freely resized within its parent container. */
    // TODO: Remove once freeform is migrated to wm-shell.
    public static final int WINDOWING_MODE_FREEFORM = 5;
    /** Generic multi-window with no presentation attribution from the window manager. */
    public static final int WINDOWING_MODE_MULTI_WINDOW = 6;

    /** @hide */
    @IntDef(prefix = { "WINDOWING_MODE_" }, value = {
            WINDOWING_MODE_UNDEFINED,
            WINDOWING_MODE_FULLSCREEN,
            WINDOWING_MODE_MULTI_WINDOW,
            WINDOWING_MODE_PINNED,
            WINDOWING_MODE_FREEFORM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WindowingMode {}

    /** The current activity type of the configuration. */
    private @ActivityType int mActivityType;

    /** Activity type is currently not defined. */
    public static final int ACTIVITY_TYPE_UNDEFINED = 0;
    /** Standard activity type. Nothing special about the activity... */
    public static final int ACTIVITY_TYPE_STANDARD = 1;
    /** Home/Launcher activity type. */
    public static final int ACTIVITY_TYPE_HOME = 2;
    /** Recents/Overview activity type. There is only one activity with this type in the system. */
    public static final int ACTIVITY_TYPE_RECENTS = 3;
    /** Assistant activity type. */
    public static final int ACTIVITY_TYPE_ASSISTANT = 4;
    /** Dream activity type. */
    public static final int ACTIVITY_TYPE_DREAM = 5;

    /** @hide */
    @IntDef(prefix = { "ACTIVITY_TYPE_" }, value = {
            ACTIVITY_TYPE_UNDEFINED,
            ACTIVITY_TYPE_STANDARD,
            ACTIVITY_TYPE_HOME,
            ACTIVITY_TYPE_RECENTS,
            ACTIVITY_TYPE_ASSISTANT,
            ACTIVITY_TYPE_DREAM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ActivityType {}

    /** The current always on top status of the configuration. */
    private @AlwaysOnTop int mAlwaysOnTop;

    /** Always on top is currently not defined. */
    private static final int ALWAYS_ON_TOP_UNDEFINED = 0;
    /** Always on top is currently on for this configuration. */
    private static final int ALWAYS_ON_TOP_ON = 1;
    /** Always on top is currently off for this configuration. */
    private static final int ALWAYS_ON_TOP_OFF = 2;

    /** @hide */
    @IntDef(prefix = { "ALWAYS_ON_TOP_" }, value = {
            ALWAYS_ON_TOP_UNDEFINED,
            ALWAYS_ON_TOP_ON,
            ALWAYS_ON_TOP_OFF,
    })
    private @interface AlwaysOnTop {}

    /** Bit that indicates that the {@link #mBounds} changed.
     * @hide */
    public static final int WINDOW_CONFIG_BOUNDS = 1 << 0;
    /** Bit that indicates that the {@link #mAppBounds} changed.
     * @hide */
    public static final int WINDOW_CONFIG_APP_BOUNDS = 1 << 1;
    /** Bit that indicates that the {@link #mMaxBounds} changed.
     * @hide */
    public static final int WINDOW_CONFIG_MAX_BOUNDS = 1 << 2;
    /** Bit that indicates that the {@link #mWindowingMode} changed.
     * @hide */
    public static final int WINDOW_CONFIG_WINDOWING_MODE = 1 << 3;
    /** Bit that indicates that the {@link #mActivityType} changed.
     * @hide */
    public static final int WINDOW_CONFIG_ACTIVITY_TYPE = 1 << 4;
    /** Bit that indicates that the {@link #mAlwaysOnTop} changed.
     * @hide */
    public static final int WINDOW_CONFIG_ALWAYS_ON_TOP = 1 << 5;
    /** Bit that indicates that the {@link #mRotation} changed.
     * @hide */
    public static final int WINDOW_CONFIG_ROTATION = 1 << 6;
    /** Bit that indicates that the apparent-display changed.
     * @hide */
    public static final int WINDOW_CONFIG_DISPLAY_ROTATION = 1 << 7;

    /** @hide */
    @IntDef(flag = true, prefix = { "WINDOW_CONFIG_" }, value = {
            WINDOW_CONFIG_BOUNDS,
            WINDOW_CONFIG_APP_BOUNDS,
            WINDOW_CONFIG_MAX_BOUNDS,
            WINDOW_CONFIG_WINDOWING_MODE,
            WINDOW_CONFIG_ACTIVITY_TYPE,
            WINDOW_CONFIG_ALWAYS_ON_TOP,
            WINDOW_CONFIG_ROTATION,
            WINDOW_CONFIG_DISPLAY_ROTATION,
    })
    public @interface WindowConfig {}

    @UnsupportedAppUsage
    public WindowConfiguration() {
        unset();
    }

    /** @hide */
    public WindowConfiguration(WindowConfiguration configuration) {
        setTo(configuration);
    }

    private WindowConfiguration(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        mBounds.writeToParcel(dest, flags);
        dest.writeTypedObject(mAppBounds, flags);
        mMaxBounds.writeToParcel(dest, flags);
        dest.writeInt(mWindowingMode);
        dest.writeInt(mActivityType);
        dest.writeInt(mAlwaysOnTop);
        dest.writeInt(mRotation);
        dest.writeInt(mDisplayRotation);
    }

    /** @hide */
    public void readFromParcel(@NonNull Parcel source) {
        mBounds.readFromParcel(source);
        mAppBounds = source.readTypedObject(Rect.CREATOR);
        mMaxBounds.readFromParcel(source);
        mWindowingMode = source.readInt();
        mActivityType = source.readInt();
        mAlwaysOnTop = source.readInt();
        mRotation = source.readInt();
        mDisplayRotation = source.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    public static final @android.annotation.NonNull Creator<WindowConfiguration> CREATOR = new Creator<WindowConfiguration>() {
        @Override
        public WindowConfiguration createFromParcel(Parcel in) {
            return new WindowConfiguration(in);
        }

        @Override
        public WindowConfiguration[] newArray(int size) {
            return new WindowConfiguration[size];
        }
    };

    /**
     * Sets the bounds to the provided {@link Rect}.
     * Passing {@code null} sets the bounds {@link Rect} to empty.
     *
     * @param rect the new bounds value.
     */
    public void setBounds(@Nullable Rect rect) {
        if (rect == null) {
            mBounds.setEmpty();
            return;
        }

        mBounds.set(rect);
    }

    /**
     * Sets the app bounds to the provided {@link Rect}.
     * Passing {@code null} sets the bounds to {@code null}.
     *
     * @param rect the new app bounds value.
     * @see #getAppBounds()
     */
    public void setAppBounds(@Nullable Rect rect) {
        if (rect == null) {
            mAppBounds = null;
            return;
        }

        setAppBounds(rect.left, rect.top, rect.right, rect.bottom);
    }

    /**
     * Sets the maximum bounds to the provided {@link Rect}.
     * Passing {@code null} sets the bounds {@link Rect} to empty.
     *
     * @param rect the new max bounds value.
     * @see #getMaxBounds()
     */
    public void setMaxBounds(@Nullable Rect rect) {
        if (rect == null) {
            mMaxBounds.setEmpty();
            return;
        }
        mMaxBounds.set(rect);
    }

    /**
     * @see #setMaxBounds(Rect)
     * @hide
     */
    public void setMaxBounds(int left, int top, int right, int bottom) {
        mMaxBounds.set(left, top, right, bottom);
    }

    /**
     * Sets the display rotation.
     * @hide
     */
    public void setDisplayRotation(@Surface.Rotation int rotation) {
        mDisplayRotation = rotation;
    }

    /**
     * Sets whether this window should be always on top.
     * @param alwaysOnTop {@code true} to set window always on top, otherwise {@code false}
     * @hide
     */
    public void setAlwaysOnTop(boolean alwaysOnTop) {
        mAlwaysOnTop = alwaysOnTop ? ALWAYS_ON_TOP_ON : ALWAYS_ON_TOP_OFF;
    }

    /**
     * Unsets always-on-top to undefined.
     * @hide
     */
    public void unsetAlwaysOnTop() {
        mAlwaysOnTop = ALWAYS_ON_TOP_UNDEFINED;
    }

    private void setAlwaysOnTop(@AlwaysOnTop int alwaysOnTop) {
        mAlwaysOnTop = alwaysOnTop;
    }

    /**
     * @see #setAppBounds(Rect)
     * @see #getAppBounds()
     * @hide
     */
    public void setAppBounds(int left, int top, int right, int bottom) {
        if (mAppBounds == null) {
            mAppBounds = new Rect();
        }

        mAppBounds.set(left, top, right, bottom);
    }

    /** @see #setAppBounds(Rect) */
    @Nullable
    public Rect getAppBounds() {
        return mAppBounds;
    }

    /** @see #setBounds(Rect) */
    @NonNull
    public Rect getBounds() {
        return mBounds;
    }

    /** @see #setMaxBounds(Rect) */
    @NonNull
    public Rect getMaxBounds() {
        return mMaxBounds;
    }

    /**
     * Gets the display rotation.
     */
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    public @Surface.Rotation int getDisplayRotation() {
        return mDisplayRotation;
    }

    public int getRotation() {
        return mRotation;
    }

    public void setRotation(int rotation) {
        mRotation = rotation;
    }

    public void setWindowingMode(@WindowingMode int windowingMode) {
        mWindowingMode = windowingMode;
    }

    @WindowingMode
    public int getWindowingMode() {
        return mWindowingMode;
    }

    public void setActivityType(@ActivityType int activityType) {
        if (mActivityType == activityType) {
            return;
        }

        // Error check within system server that we are not changing activity type which can be
        // dangerous. It is okay for things to change in the application process as it doesn't
        // affect how other things is the system is managed.
        if (isSystem()
                && mActivityType != ACTIVITY_TYPE_UNDEFINED
                && activityType != ACTIVITY_TYPE_UNDEFINED) {
            throw new IllegalStateException("Can't change activity type once set: " + this
                    + " activityType=" + activityTypeToString(activityType));
        }
        mActivityType = activityType;
    }

    @ActivityType
    public int getActivityType() {
        return mActivityType;
    }

    public void setTo(WindowConfiguration other) {
        setBounds(other.mBounds);
        setAppBounds(other.mAppBounds);
        setMaxBounds(other.mMaxBounds);
        setDisplayRotation(other.mDisplayRotation);
        setWindowingMode(other.mWindowingMode);
        setActivityType(other.mActivityType);
        setAlwaysOnTop(other.mAlwaysOnTop);
        setRotation(other.mRotation);
    }

    /** Set this object to completely undefined.
     * @hide */
    public void unset() {
        setToDefaults();
    }

    /** @hide */
    public void setToDefaults() {
        setAppBounds(null);
        setBounds(null);
        setMaxBounds(null);
        setDisplayRotation(ROTATION_UNDEFINED);
        setWindowingMode(WINDOWING_MODE_UNDEFINED);
        setActivityType(ACTIVITY_TYPE_UNDEFINED);
        setAlwaysOnTop(ALWAYS_ON_TOP_UNDEFINED);
        setRotation(ROTATION_UNDEFINED);
    }

    /** @hide */
    public void scale(float scale) {
        scaleBounds(scale, mBounds);
        scaleBounds(scale, mMaxBounds);
        if (mAppBounds != null) {
            scaleBounds(scale, mAppBounds);
        }
    }

    /**
     * Size based scaling. This avoid inconsistent length when rounding 4 sides.
     * E.g. left=12, right=18, scale=0.8. The scaled width can be:
     *   int((right - left) * scale + 0.5) = int(4.8 + 0.5) = 5
     * But with rounding both left and right, the width will be inconsistent:
     *   int(right * scale + 0.5) - int(left * scale + 0.5) = int(14.9) - int(10.1) = 4
     * @hide
     */
    private static void scaleBounds(float scale, Rect bounds) {
        final int w = bounds.width();
        final int h = bounds.height();
        bounds.left = (int) (bounds.left * scale + .5f);
        bounds.top = (int) (bounds.top * scale + .5f);
        bounds.right = bounds.left + (int) (w * scale + .5f);
        bounds.bottom = bounds.top + (int) (h * scale + .5f);
    }

    /**
     * Copies the fields from delta into this Configuration object, keeping
     * track of which ones have changed. Any undefined fields in {@code delta}
     * are ignored and not copied in to the current Configuration.
     *
     * @return a bit mask of the changed fields, as per {@link #diff}
     * @hide
     */
    public @WindowConfig int updateFrom(@NonNull WindowConfiguration delta) {
        int changed = 0;
        // Only allow override if bounds is not empty
        if (!delta.mBounds.isEmpty() && !delta.mBounds.equals(mBounds)) {
            changed |= WINDOW_CONFIG_BOUNDS;
            setBounds(delta.mBounds);
        }
        if (delta.mAppBounds != null && !delta.mAppBounds.equals(mAppBounds)) {
            changed |= WINDOW_CONFIG_APP_BOUNDS;
            setAppBounds(delta.mAppBounds);
        }
        if (!delta.mMaxBounds.isEmpty() && !delta.mMaxBounds.equals(mMaxBounds)) {
            changed |= WINDOW_CONFIG_MAX_BOUNDS;
            setMaxBounds(delta.mMaxBounds);
        }
        if (delta.mWindowingMode != WINDOWING_MODE_UNDEFINED
                && mWindowingMode != delta.mWindowingMode) {
            changed |= WINDOW_CONFIG_WINDOWING_MODE;
            setWindowingMode(delta.mWindowingMode);
        }
        if (delta.mActivityType != ACTIVITY_TYPE_UNDEFINED
                && mActivityType != delta.mActivityType) {
            changed |= WINDOW_CONFIG_ACTIVITY_TYPE;
            setActivityType(delta.mActivityType);
        }
        if (delta.mAlwaysOnTop != ALWAYS_ON_TOP_UNDEFINED
                && mAlwaysOnTop != delta.mAlwaysOnTop) {
            changed |= WINDOW_CONFIG_ALWAYS_ON_TOP;
            setAlwaysOnTop(delta.mAlwaysOnTop);
        }
        if (delta.mRotation != ROTATION_UNDEFINED && delta.mRotation != mRotation) {
            changed |= WINDOW_CONFIG_ROTATION;
            setRotation(delta.mRotation);
        }
        if (delta.mDisplayRotation != ROTATION_UNDEFINED
                && delta.mDisplayRotation != mDisplayRotation) {
            changed |= WINDOW_CONFIG_DISPLAY_ROTATION;
            setDisplayRotation(delta.mDisplayRotation);
        }
        return changed;
    }

    /**
     * Copies the fields specified by mask from delta into this Configuration object.
     * @hide
     */
    public void setTo(@NonNull WindowConfiguration delta, @WindowConfig int mask) {
        if ((mask & WINDOW_CONFIG_BOUNDS) != 0) {
            setBounds(delta.mBounds);
        }
        if ((mask & WINDOW_CONFIG_APP_BOUNDS) != 0) {
            setAppBounds(delta.mAppBounds);
        }
        if ((mask & WINDOW_CONFIG_MAX_BOUNDS) != 0) {
            setMaxBounds(delta.mMaxBounds);
        }
        if ((mask & WINDOW_CONFIG_WINDOWING_MODE) != 0) {
            setWindowingMode(delta.mWindowingMode);
        }
        if ((mask & WINDOW_CONFIG_ACTIVITY_TYPE) != 0) {
            setActivityType(delta.mActivityType);
        }
        if ((mask & WINDOW_CONFIG_ALWAYS_ON_TOP) != 0) {
            setAlwaysOnTop(delta.mAlwaysOnTop);
        }
        if ((mask & WINDOW_CONFIG_ROTATION) != 0) {
            setRotation(delta.mRotation);
        }
        if ((mask & WINDOW_CONFIG_DISPLAY_ROTATION) != 0) {
            setDisplayRotation(delta.mDisplayRotation);
        }
    }

    /**
     * Return a bit mask of the differences between this Configuration object and the given one.
     * Does not change the values of either. Any undefined fields in <var>other</var> are ignored.
     * @param other The configuration to diff against.
     * @param compareUndefined If undefined values should be compared.
     * @return Returns a bit mask indicating which configuration
     * values has changed, containing any combination of {@link WindowConfig} flags.
     *
     * @see Configuration#diff(Configuration)
     * @hide
     */
    public @WindowConfig long diff(WindowConfiguration other, boolean compareUndefined) {
        long changes = 0;

        if (!mBounds.equals(other.mBounds)) {
            changes |= WINDOW_CONFIG_BOUNDS;
        }

        // Make sure that one of the values is not null and that they are not equal.
        if ((compareUndefined || other.mAppBounds != null)
                && mAppBounds != other.mAppBounds
                && (mAppBounds == null || !mAppBounds.equals(other.mAppBounds))) {
            changes |= WINDOW_CONFIG_APP_BOUNDS;
        }

        if (!mMaxBounds.equals(other.mMaxBounds)) {
            changes |= WINDOW_CONFIG_MAX_BOUNDS;
        }

        if ((compareUndefined || other.mWindowingMode != WINDOWING_MODE_UNDEFINED)
                && mWindowingMode != other.mWindowingMode) {
            changes |= WINDOW_CONFIG_WINDOWING_MODE;
        }

        if ((compareUndefined || other.mActivityType != ACTIVITY_TYPE_UNDEFINED)
                && mActivityType != other.mActivityType) {
            changes |= WINDOW_CONFIG_ACTIVITY_TYPE;
        }

        if ((compareUndefined || other.mAlwaysOnTop != ALWAYS_ON_TOP_UNDEFINED)
                && mAlwaysOnTop != other.mAlwaysOnTop) {
            changes |= WINDOW_CONFIG_ALWAYS_ON_TOP;
        }

        if ((compareUndefined || other.mRotation != ROTATION_UNDEFINED)
                && mRotation != other.mRotation) {
            changes |= WINDOW_CONFIG_ROTATION;
        }

        if ((compareUndefined || other.mDisplayRotation != ROTATION_UNDEFINED)
                && mDisplayRotation != other.mDisplayRotation) {
            changes |= WINDOW_CONFIG_DISPLAY_ROTATION;
        }

        return changes;
    }

    @Override
    public int compareTo(WindowConfiguration that) {
        int n = 0;
        if (mAppBounds == null && that.mAppBounds != null) {
            return 1;
        } else if (mAppBounds != null && that.mAppBounds == null) {
            return -1;
        } else if (mAppBounds != null && that.mAppBounds != null) {
            n = mAppBounds.left - that.mAppBounds.left;
            if (n != 0) return n;
            n = mAppBounds.top - that.mAppBounds.top;
            if (n != 0) return n;
            n = mAppBounds.right - that.mAppBounds.right;
            if (n != 0) return n;
            n = mAppBounds.bottom - that.mAppBounds.bottom;
            if (n != 0) return n;
        }

        n = mMaxBounds.left - that.mMaxBounds.left;
        if (n != 0) return n;
        n = mMaxBounds.top - that.mMaxBounds.top;
        if (n != 0) return n;
        n = mMaxBounds.right - that.mMaxBounds.right;
        if (n != 0) return n;
        n = mMaxBounds.bottom - that.mMaxBounds.bottom;
        if (n != 0) return n;

        n = mBounds.left - that.mBounds.left;
        if (n != 0) return n;
        n = mBounds.top - that.mBounds.top;
        if (n != 0) return n;
        n = mBounds.right - that.mBounds.right;
        if (n != 0) return n;
        n = mBounds.bottom - that.mBounds.bottom;
        if (n != 0) return n;

        n = mWindowingMode - that.mWindowingMode;
        if (n != 0) return n;
        n = mActivityType - that.mActivityType;
        if (n != 0) return n;
        n = mAlwaysOnTop - that.mAlwaysOnTop;
        if (n != 0) return n;
        n = mRotation - that.mRotation;
        if (n != 0) return n;

        n = mDisplayRotation - that.mDisplayRotation;
        if (n != 0) return n;

        // if (n != 0) return n;
        return n;
    }

    /** @hide */
    @Override
    public boolean equals(@Nullable Object that) {
        if (that == null) return false;
        if (that == this) return true;
        if (!(that instanceof WindowConfiguration)) {
            return false;
        }
        return this.compareTo((WindowConfiguration) that) == 0;
    }

    /** @hide */
    @Override
    public int hashCode() {
        int result = 0;
        result = 31 * result + Objects.hashCode(mAppBounds);
        result = 31 * result + Objects.hashCode(mBounds);
        result = 31 * result + Objects.hashCode(mMaxBounds);
        result = 31 * result + mWindowingMode;
        result = 31 * result + mActivityType;
        result = 31 * result + mAlwaysOnTop;
        result = 31 * result + mRotation;
        result = 31 * result + mDisplayRotation;
        return result;
    }

    /** @hide */
    @Override
    public String toString() {
        return "{ mBounds=" + mBounds
                + " mAppBounds=" + mAppBounds
                + " mMaxBounds=" + mMaxBounds
                + " mDisplayRotation=" + (mRotation == ROTATION_UNDEFINED
                        ? "undefined" : rotationToString(mDisplayRotation))
                + " mWindowingMode=" + windowingModeToString(mWindowingMode)
                + " mActivityType=" + activityTypeToString(mActivityType)
                + " mAlwaysOnTop=" + alwaysOnTopToString(mAlwaysOnTop)
                + " mRotation=" + (mRotation == ROTATION_UNDEFINED
                        ? "undefined" : rotationToString(mRotation))
                + "}";
    }

    /**
     * Write to a protocol buffer output stream.
     * Protocol buffer message definition at {@link android.app.WindowConfigurationProto}
     *
     * @param protoOutputStream Stream to write the WindowConfiguration object to.
     * @param fieldId           Field Id of the WindowConfiguration as defined in the parent message
     * @hide
     */
    public void dumpDebug(ProtoOutputStream protoOutputStream, long fieldId) {
        final long token = protoOutputStream.start(fieldId);
        if (mAppBounds != null) {
            mAppBounds.dumpDebug(protoOutputStream, APP_BOUNDS);
        }
        protoOutputStream.write(WINDOWING_MODE, mWindowingMode);
        protoOutputStream.write(ACTIVITY_TYPE, mActivityType);
        mBounds.dumpDebug(protoOutputStream, BOUNDS);
        mMaxBounds.dumpDebug(protoOutputStream, MAX_BOUNDS);
        protoOutputStream.end(token);
    }

    /**
     * Read from a protocol buffer input stream.
     * Protocol buffer message definition at {@link android.app.WindowConfigurationProto}
     *
     * @param proto   Stream to read the WindowConfiguration object from.
     * @param fieldId Field Id of the WindowConfiguration as defined in the parent message
     * @hide
     */
    public void readFromProto(ProtoInputStream proto, long fieldId)
            throws IOException, WireTypeMismatchException {
        final long token = proto.start(fieldId);
        try {
            while (proto.nextField() != ProtoInputStream.NO_MORE_FIELDS) {
                switch (proto.getFieldNumber()) {
                    case (int) APP_BOUNDS:
                        mAppBounds = new Rect();
                        mAppBounds.readFromProto(proto, APP_BOUNDS);
                        break;
                    case (int) BOUNDS:
                        mBounds.readFromProto(proto, BOUNDS);
                        break;
                    case (int) MAX_BOUNDS:
                        mMaxBounds.readFromProto(proto, MAX_BOUNDS);
                        break;
                    case (int) WINDOWING_MODE:
                        mWindowingMode = proto.readInt(WINDOWING_MODE);
                        break;
                    case (int) ACTIVITY_TYPE:
                        mActivityType = proto.readInt(ACTIVITY_TYPE);
                        break;
                }
            }
        } finally {
            // Let caller handle any exceptions
            proto.end(token);
        }
    }

    /**
     * Returns true if the activities associated with this window configuration display a shadow
     * around their border.
     * @hide
     */
    public boolean hasWindowShadow() {
        return mWindowingMode != WINDOWING_MODE_MULTI_WINDOW && tasksAreFloating();
    }

    /**
     * Returns true if the tasks associated with this window configuration can be resized
     * independently of their parent container.
     * @hide
     */
    public boolean canResizeTask() {
        return mWindowingMode == WINDOWING_MODE_FREEFORM
                || mWindowingMode == WINDOWING_MODE_MULTI_WINDOW;
    }

    /** Returns true if the task bounds should persist across power cycles.
     * @hide */
    public boolean persistTaskBounds() {
        return mWindowingMode == WINDOWING_MODE_FREEFORM;
    }

    /**
     * Returns true if the tasks associated with this window configuration are floating.
     * Floating tasks are laid out differently as they are allowed to extend past the display bounds
     * without overscan insets.
     * @hide
     */
    public boolean tasksAreFloating() {
        return isFloating(mWindowingMode);
    }

    /** Returns true if the windowingMode represents a floating window. */
    public static boolean isFloating(@WindowingMode int windowingMode) {
        return windowingMode == WINDOWING_MODE_FREEFORM || windowingMode == WINDOWING_MODE_PINNED;
    }

    /**
     * Returns {@code true} if the windowingMode represents a window in multi-window mode.
     * I.e. sharing the screen with another activity.
     * @hide
     */
    public static boolean inMultiWindowMode(int windowingMode) {
        return windowingMode != WINDOWING_MODE_FULLSCREEN
                && windowingMode != WINDOWING_MODE_UNDEFINED;
    }

    /**
     * Returns true if the windows associated with this window configuration can receive input keys.
     * @hide
     */
    public boolean canReceiveKeys() {
        return mWindowingMode != WINDOWING_MODE_PINNED;
    }

    /**
     * Returns true if the container associated with this window configuration is always-on-top of
     * its siblings.
     * @hide
     */
    public boolean isAlwaysOnTop() {
        if (mWindowingMode == WINDOWING_MODE_PINNED) return true;
        if (mActivityType == ACTIVITY_TYPE_DREAM) return true;
        if (mAlwaysOnTop != ALWAYS_ON_TOP_ON) return false;
        return mWindowingMode == WINDOWING_MODE_FREEFORM
                    || mWindowingMode == WINDOWING_MODE_MULTI_WINDOW;
    }

    /**
     * Returns true if the backdrop on the client side should match the frame of the window.
     * Returns false, if the backdrop should be fullscreen.
     * @hide
     */
    public boolean useWindowFrameForBackdrop() {
        return mWindowingMode == WINDOWING_MODE_FREEFORM || mWindowingMode == WINDOWING_MODE_PINNED;
    }

    /**
     * Returns true if windows in this container should be given move animations by default.
     * @hide
     */
    public boolean hasMovementAnimations() {
        return mWindowingMode != WINDOWING_MODE_PINNED;
    }

    /**
     * Returns true if this container can be put in {@link #WINDOWING_MODE_MULTI_WINDOW}
     * windowing mode based on its current state.
     * @hide
     */
    public boolean supportSplitScreenWindowingMode() {
        return supportSplitScreenWindowingMode(mActivityType);
    }

    /** @hide */
    public static boolean supportSplitScreenWindowingMode(int activityType) {
        return activityType != ACTIVITY_TYPE_ASSISTANT && activityType != ACTIVITY_TYPE_DREAM;
    }

    /**
     * Checks if the two {@link Configuration}s are equal to each other for the fields that are read
     * by {@link Display}.
     * @hide
     */
    public static boolean areConfigurationsEqualForDisplay(@NonNull Configuration newConfig,
            @NonNull Configuration oldConfig) {
        // Only report different if max bounds and display rotation is changed, so that it will not
        // report on Task resizing.
        if (!newConfig.windowConfiguration.getMaxBounds().equals(
                oldConfig.windowConfiguration.getMaxBounds())) {
            return false;
        }
        return newConfig.windowConfiguration.getDisplayRotation()
                == oldConfig.windowConfiguration.getDisplayRotation();
    }

    /** @hide */
    public static String windowingModeToString(@WindowingMode int windowingMode) {
        switch (windowingMode) {
            case WINDOWING_MODE_UNDEFINED: return "undefined";
            case WINDOWING_MODE_FULLSCREEN: return "fullscreen";
            case WINDOWING_MODE_MULTI_WINDOW: return "multi-window";
            case WINDOWING_MODE_PINNED: return "pinned";
            case WINDOWING_MODE_FREEFORM: return "freeform";
        }
        return String.valueOf(windowingMode);
    }

    /** @hide */
    public static String activityTypeToString(@ActivityType int applicationType) {
        switch (applicationType) {
            case ACTIVITY_TYPE_UNDEFINED: return "undefined";
            case ACTIVITY_TYPE_STANDARD: return "standard";
            case ACTIVITY_TYPE_HOME: return "home";
            case ACTIVITY_TYPE_RECENTS: return "recents";
            case ACTIVITY_TYPE_ASSISTANT: return "assistant";
            case ACTIVITY_TYPE_DREAM: return "dream";
        }
        return String.valueOf(applicationType);
    }

    /** @hide */
    public static String alwaysOnTopToString(@AlwaysOnTop int alwaysOnTop) {
        switch (alwaysOnTop) {
            case ALWAYS_ON_TOP_UNDEFINED: return "undefined";
            case ALWAYS_ON_TOP_ON: return "on";
            case ALWAYS_ON_TOP_OFF: return "off";
        }
        return String.valueOf(alwaysOnTop);
    }
}
