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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.DisplayInfo;

/**
 * Class that contains windowing configuration/state for other objects that contain windows directly
 * or indirectly. E.g. Activities, Task, Displays, ...
 * The test class is {@link com.android.server.wm.WindowConfigurationTests} which must be kept
 * up-to-date and ran anytime changes are made to this class.
 * @hide
 */
public class WindowConfiguration implements Parcelable, Comparable<WindowConfiguration> {

    /**
     * {@link android.graphics.Rect} defining app bounds. The dimensions override usages of
     * {@link DisplayInfo#appHeight} and {@link DisplayInfo#appWidth} and mirrors these values at
     * the display level. Lower levels can override these values to provide custom bounds to enforce
     * features such as a max aspect ratio.
     */
    private Rect mAppBounds;

    /** The current windowing mode of the configuration. */
    private @WindowingMode int mWindowingMode;

    /** Windowing mode is currently not defined. */
    public static final int WINDOWING_MODE_UNDEFINED = 0;
    /** Occupies the full area of the screen or the parent container. */
    public static final int WINDOWING_MODE_FULLSCREEN = 1;
    /** Always on-top (always visible). of other siblings in its parent container. */
    public static final int WINDOWING_MODE_PINNED = 2;
    /** Occupies a dedicated region of the screen or its parent container. */
    public static final int WINDOWING_MODE_DOCKED = 3;
    /** Can be freely resized within its parent container. */
    public static final int WINDOWING_MODE_FREEFORM = 4;

    @IntDef({
            WINDOWING_MODE_UNDEFINED,
            WINDOWING_MODE_FULLSCREEN,
            WINDOWING_MODE_PINNED,
            WINDOWING_MODE_DOCKED,
            WINDOWING_MODE_FREEFORM,
    })
    public @interface WindowingMode {}

    /** The current activity type of the configuration. */
    private @ActivityType int mActivityType;

    /** Activity type is currently not defined. */
    public static final int ACTIVITY_TYPE_UNDEFINED = 0;
    /** Standard activity type. Nothing special about the activity... */
    public static final int ACTIVITY_TYPE_STANDARD = 1;
    /** Home/Launcher activity type. */
    public static final int ACTIVITY_TYPE_HOME = 2;
    /** Recents/Overview activity type. */
    public static final int ACTIVITY_TYPE_RECENTS = 3;
    /** Assistant activity type. */
    public static final int ACTIVITY_TYPE_ASSISTANT = 4;

    @IntDef({
            ACTIVITY_TYPE_UNDEFINED,
            ACTIVITY_TYPE_STANDARD,
            ACTIVITY_TYPE_HOME,
            ACTIVITY_TYPE_RECENTS,
            ACTIVITY_TYPE_ASSISTANT,
    })
    public @interface ActivityType {}

    /** Bit that indicates that the {@link #mAppBounds} changed. */
    public static final int WINDOW_CONFIG_APP_BOUNDS = 1 << 0;
    /** Bit that indicates that the {@link #mWindowingMode} changed. */
    public static final int WINDOW_CONFIG_WINDOWING_MODE = 1 << 1;
    /** Bit that indicates that the {@link #mActivityType} changed. */
    public static final int WINDOW_CONFIG_ACTIVITY_TYPE = 1 << 2;

    @IntDef(flag = true,
            value = {
                    WINDOW_CONFIG_APP_BOUNDS,
                    WINDOW_CONFIG_WINDOWING_MODE,
                    WINDOW_CONFIG_ACTIVITY_TYPE,
            })
    public @interface WindowConfig {}

    public WindowConfiguration() {
        unset();
    }

    public WindowConfiguration(WindowConfiguration configuration) {
        setTo(configuration);
    }

    private WindowConfiguration(Parcel in) {
        readFromParcel(in);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mAppBounds, flags);
        dest.writeInt(mWindowingMode);
        dest.writeInt(mActivityType);
    }

    private void readFromParcel(Parcel source) {
        mAppBounds = source.readParcelable(Rect.class.getClassLoader());
        mWindowingMode = source.readInt();
        mActivityType = source.readInt();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<WindowConfiguration> CREATOR = new Creator<WindowConfiguration>() {
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
     * Set {@link #mAppBounds} to the input Rect.
     * @param rect The rect value to set {@link #mAppBounds} to.
     * @see #getAppBounds()
     */
    public void setAppBounds(Rect rect) {
        if (rect == null) {
            mAppBounds = null;
            return;
        }

        setAppBounds(rect.left, rect.top, rect.right, rect.bottom);
    }

    /**
     * @see #setAppBounds(Rect)
     * @see #getAppBounds()
     */
    public void setAppBounds(int left, int top, int right, int bottom) {
        if (mAppBounds == null) {
            mAppBounds = new Rect();
        }

        mAppBounds.set(left, top, right, bottom);
    }

    /**
     * @see #setAppBounds(Rect)
     */
    public Rect getAppBounds() {
        return mAppBounds;
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
        if (mActivityType != ACTIVITY_TYPE_UNDEFINED
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
        setAppBounds(other.mAppBounds);
        setWindowingMode(other.mWindowingMode);
        setActivityType(other.mActivityType);
    }

    /** Set this object to completely undefined. */
    public void unset() {
        setToDefaults();
    }

    public void setToDefaults() {
        setAppBounds(null);
        setWindowingMode(WINDOWING_MODE_UNDEFINED);
        setActivityType(ACTIVITY_TYPE_UNDEFINED);
    }

    /**
     * Copies the fields from delta into this Configuration object, keeping
     * track of which ones have changed. Any undefined fields in {@code delta}
     * are ignored and not copied in to the current Configuration.
     *
     * @return a bit mask of the changed fields, as per {@link #diff}
     */
    public @WindowConfig int updateFrom(@NonNull WindowConfiguration delta) {
        int changed = 0;
        if (delta.mAppBounds != null && !delta.mAppBounds.equals(mAppBounds)) {
            changed |= WINDOW_CONFIG_APP_BOUNDS;
            setAppBounds(delta.mAppBounds);
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
        return changed;
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
     */
    public @WindowConfig long diff(WindowConfiguration other, boolean compareUndefined) {
        long changes = 0;

        // Make sure that one of the values is not null and that they are not equal.
        if ((compareUndefined || other.mAppBounds != null)
                && mAppBounds != other.mAppBounds
                && (mAppBounds == null || !mAppBounds.equals(other.mAppBounds))) {
            changes |= WINDOW_CONFIG_APP_BOUNDS;
        }

        if ((compareUndefined || other.mWindowingMode != WINDOWING_MODE_UNDEFINED)
                && mWindowingMode != other.mWindowingMode) {
            changes |= WINDOW_CONFIG_WINDOWING_MODE;
        }

        if ((compareUndefined || other.mActivityType != ACTIVITY_TYPE_UNDEFINED)
                && mActivityType != other.mActivityType) {
            changes |= WINDOW_CONFIG_ACTIVITY_TYPE;
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
        n = mWindowingMode - that.mWindowingMode;
        if (n != 0) return n;
        n = mActivityType - that.mActivityType;
        if (n != 0) return n;

        // if (n != 0) return n;
        return n;
    }

    @Override
    public boolean equals(Object that) {
        if (that == null) return false;
        if (that == this) return true;
        if (!(that instanceof WindowConfiguration)) {
            return false;
        }
        return this.compareTo((WindowConfiguration) that) == 0;
    }

    @Override
    public int hashCode() {
        int result = 0;
        if (mAppBounds != null) {
            result = 31 * result + mAppBounds.hashCode();
        }
        result = 31 * result + mWindowingMode;
        result = 31 * result + mActivityType;
        return result;
    }

    @Override
    public String toString() {
        return "{mAppBounds=" + mAppBounds
                + " mWindowingMode=" + windowingModeToString(mWindowingMode)
                + " mActivityType=" + activityTypeToString(mActivityType) + "}";
    }

    /**
     * Returns true if the activities associated with this window configuration display a shadow
     * around their border.
     */
    public boolean hasWindowShadow() {
        return tasksAreFloating();
    }

    /**
     * Returns true if the activities associated with this window configuration display a decor
     * view.
     */
    public boolean hasWindowDecorCaption() {
        return mWindowingMode == WINDOWING_MODE_FREEFORM;
    }

    /**
     * Returns true if the tasks associated with this window configuration can be resized
     * independently of their parent container.
     */
    public boolean canResizeTask() {
        return mWindowingMode == WINDOWING_MODE_FREEFORM;
    }

    /** Returns true if the task bounds should persist across power cycles. */
    public boolean persistTaskBounds() {
        return mWindowingMode == WINDOWING_MODE_FREEFORM;
    }

    /**
     * Returns true if the tasks associated with this window configuration are floating.
     * Floating tasks are laid out differently as they are allowed to extend past the display bounds
     * without overscan insets.
     */
    public boolean tasksAreFloating() {
        return mWindowingMode == WINDOWING_MODE_FREEFORM || mWindowingMode == WINDOWING_MODE_PINNED;
    }

    /**
     * Returns true if the windows associated with this window configuration can receive input keys.
     */
    public boolean canReceiveKeys() {
        return mWindowingMode != WINDOWING_MODE_PINNED;
    }

    /**
     * Returns true if the container associated with this window configuration is always-on-top of
     * its siblings.
     */
    public boolean isAlwaysOnTop() {
        return mWindowingMode == WINDOWING_MODE_PINNED;
    }

    /**
     * Returns true if any visible windows belonging to apps with this window configuration should
     * be kept on screen when the app is killed due to something like the low memory killer.
     */
    public boolean keepVisibleDeadAppWindowOnScreen() {
        return mWindowingMode != WINDOWING_MODE_PINNED;
    }

    /**
     * Returns true if the backdrop on the client side should match the frame of the window.
     * Returns false, if the backdrop should be fullscreen.
     */
    public boolean useWindowFrameForBackdrop() {
        return mWindowingMode == WINDOWING_MODE_FREEFORM || mWindowingMode == WINDOWING_MODE_PINNED;
    }

    /**
     * Returns true if this container may be scaled without resizing, and windows within may need
     * to be configured as such.
     */
    public boolean windowsAreScaleable() {
        return mWindowingMode == WINDOWING_MODE_PINNED;
    }

    /**
     * Returns true if windows in this container should be given move animations by default.
     */
    public boolean hasMovementAnimations() {
        return mWindowingMode == WINDOWING_MODE_PINNED;
    }

    private static String windowingModeToString(@WindowingMode int windowingMode) {
        switch (windowingMode) {
            case WINDOWING_MODE_UNDEFINED: return "undefined";
            case WINDOWING_MODE_FULLSCREEN: return "fullscreen";
            case WINDOWING_MODE_PINNED: return "pinned";
            case WINDOWING_MODE_DOCKED: return "docked";
            case WINDOWING_MODE_FREEFORM: return "freeform";
        }
        return String.valueOf(windowingMode);
    }

    public static String activityTypeToString(@ActivityType int applicationType) {
        switch (applicationType) {
            case ACTIVITY_TYPE_UNDEFINED: return "undefined";
            case ACTIVITY_TYPE_STANDARD: return "standard";
            case ACTIVITY_TYPE_HOME: return "home";
            case ACTIVITY_TYPE_RECENTS: return "recents";
            case ACTIVITY_TYPE_ASSISTANT: return "assistant";
        }
        return String.valueOf(applicationType);
    }
}
