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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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

    @IntDef(flag = true,
            value = {
                    WINDOW_CONFIG_APP_BOUNDS,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WindowConfig {}

    /** Bit that indicates that the {@link #mAppBounds} changed. */
    public static final int WINDOW_CONFIG_APP_BOUNDS = 1 << 0;

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
    }

    private void readFromParcel(Parcel source) {
        mAppBounds = source.readParcelable(Rect.class.getClassLoader());
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

    public void setTo(WindowConfiguration other) {
        setAppBounds(other.mAppBounds);
    }

    /** Set this object to completely undefined. */
    public void unset() {
        setToDefaults();
    }

    public void setToDefaults() {
        setAppBounds(null);
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
        return result;
    }

    @Override
    public String toString() {
        return "{mAppBounds=" + mAppBounds + "}";
    }
}
