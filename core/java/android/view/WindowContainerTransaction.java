/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.view;

import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;

import java.util.Map;

/**
 * Represents a collection of operations on some WindowContainers that should be applied all at
 * once.
 *
 * @hide
 */
public class WindowContainerTransaction implements Parcelable {
    private final ArrayMap<IBinder, Change> mChanges = new ArrayMap<>();

    public WindowContainerTransaction() {}

    protected WindowContainerTransaction(Parcel in) {
        in.readMap(mChanges, null /* loader */);
    }

    private Change getOrCreateChange(IBinder token) {
        Change out = mChanges.get(token);
        if (out == null) {
            out = new Change();
            mChanges.put(token, out);
        }
        return out;
    }

    /**
     * Resize a container.
     */
    public WindowContainerTransaction setBounds(IWindowContainer container, Rect bounds) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mConfiguration.windowConfiguration.setBounds(bounds);
        chg.mConfigSetMask |= ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
        chg.mWindowSetMask |= WindowConfiguration.WINDOW_CONFIG_BOUNDS;
        return this;
    }

    /**
     * Notify activies within the hiearchy of a container that they have entered picture-in-picture
     * mode with the given bounds.
     */
    public WindowContainerTransaction scheduleFinishEnterPip(IWindowContainer container,
            Rect bounds) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mSchedulePipCallback = true;
        chg.mPinnedBounds = new Rect(bounds);
        return this;
    }

    public Map<IBinder, Change> getChanges() {
        return mChanges;
    }

    @Override
    public String toString() {
        return "WindowContainerTransaction { changes = " + mChanges + " }";
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeMap(mChanges);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<WindowContainerTransaction> CREATOR =
            new Creator<WindowContainerTransaction>() {
                @Override
                public WindowContainerTransaction createFromParcel(Parcel in) {
                    return new WindowContainerTransaction(in);
                }

                @Override
                public WindowContainerTransaction[] newArray(int size) {
                    return new WindowContainerTransaction[size];
                }
            };

    /**
     * Holds changes on a single WindowContainer including Configuration changes.
     *
     * @hide
     */
    public static class Change implements Parcelable {
        private final Configuration mConfiguration = new Configuration();
        private @ActivityInfo.Config int mConfigSetMask = 0;
        private @WindowConfiguration.WindowConfig int mWindowSetMask = 0;

        private boolean mSchedulePipCallback = false;
        private Rect mPinnedBounds = null;

        public Change() {}

        protected Change(Parcel in) {
            mConfiguration.readFromParcel(in);
            mConfigSetMask = in.readInt();
            mWindowSetMask = in.readInt();
            mSchedulePipCallback = (in.readInt() != 0);
            if (mSchedulePipCallback ) {
                mPinnedBounds = new Rect();
                mPinnedBounds.readFromParcel(in);
            }
        }

        public Configuration getConfiguration() {
            return mConfiguration;
        }

        @ActivityInfo.Config
        public int getConfigSetMask() {
            return mConfigSetMask;
        }

        @WindowConfiguration.WindowConfig
        public int getWindowSetMask() {
            return mWindowSetMask;
        }

        /**
         * Returns the bounds to be used for scheduling the enter pip callback
         * or null if no callback is to be scheduled.
         */
        public Rect getEnterPipBounds() {
            return mPinnedBounds;
        }

        @Override
        public String toString() {
            final boolean changesBounds =
                    (mConfigSetMask & ActivityInfo.CONFIG_WINDOW_CONFIGURATION) != 0
                            && ((mWindowSetMask & WindowConfiguration.WINDOW_CONFIG_BOUNDS)
                                    != 0);
            final boolean changesSss =
                    (mConfigSetMask & ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE) != 0;
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            if (changesBounds) {
                sb.append("bounds:" + mConfiguration.windowConfiguration.getBounds() + ",");
            }
            if (changesSss) {
                sb.append("ssw:" + mConfiguration.smallestScreenWidthDp + ",");
            }
            sb.append("}");
            return sb.toString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            mConfiguration.writeToParcel(dest, flags);
            dest.writeInt(mConfigSetMask);
            dest.writeInt(mWindowSetMask);

            dest.writeInt(mSchedulePipCallback ? 1 : 0);
            if (mSchedulePipCallback ) {
                mPinnedBounds.writeToParcel(dest, flags);
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<Change> CREATOR = new Creator<Change>() {
            @Override
            public Change createFromParcel(Parcel in) {
                return new Change(in);
            }

            @Override
            public Change[] newArray(int size) {
                return new Change[size];
            }
        };
    }
}
