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
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.WindowConfiguration;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.view.SurfaceControl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a collection of operations on some WindowContainers that should be applied all at
 * once.
 *
 * @hide
 */
@TestApi
public final class WindowContainerTransaction implements Parcelable {
    private final ArrayMap<IBinder, Change> mChanges = new ArrayMap<>();

    // Flat list because re-order operations are order-dependent
    private final ArrayList<HierarchyOp> mHierarchyOps = new ArrayList<>();

    public WindowContainerTransaction() {}

    private WindowContainerTransaction(Parcel in) {
        in.readMap(mChanges, null /* loader */);
        in.readList(mHierarchyOps, null /* loader */);
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
    @NonNull
    public WindowContainerTransaction setBounds(
            @NonNull WindowContainerToken container,@NonNull Rect bounds) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mConfiguration.windowConfiguration.setBounds(bounds);
        chg.mConfigSetMask |= ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
        chg.mWindowSetMask |= WindowConfiguration.WINDOW_CONFIG_BOUNDS;
        return this;
    }

    /**
     * Resize a container's app bounds. This is the bounds used to report appWidth/Height to an
     * app's DisplayInfo. It is derived by subtracting the overlapping portion of the navbar from
     * the full bounds.
     */
    @NonNull
    public WindowContainerTransaction setAppBounds(
            @NonNull WindowContainerToken container,@NonNull Rect appBounds) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mConfiguration.windowConfiguration.setAppBounds(appBounds);
        chg.mConfigSetMask |= ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
        chg.mWindowSetMask |= WindowConfiguration.WINDOW_CONFIG_APP_BOUNDS;
        return this;
    }

    /**
     * Resize a container's configuration size. The configuration size is what gets reported to the
     * app via screenWidth/HeightDp and influences which resources get loaded. This size is
     * derived by subtracting the overlapping portions of both the statusbar and the navbar from
     * the full bounds.
     */
    @NonNull
    public WindowContainerTransaction setScreenSizeDp(
            @NonNull WindowContainerToken container, int w, int h) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mConfiguration.screenWidthDp = w;
        chg.mConfiguration.screenHeightDp = h;
        chg.mConfigSetMask |= ActivityInfo.CONFIG_SCREEN_SIZE;
        return this;
    }

    /**
     * Notify activities within the hierarchy of a container that they have entered picture-in-picture
     * mode with the given bounds.
     */
    @NonNull
    public WindowContainerTransaction scheduleFinishEnterPip(
            @NonNull WindowContainerToken container,@NonNull Rect bounds) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mPinnedBounds = new Rect(bounds);
        chg.mChangeMask |= Change.CHANGE_PIP_CALLBACK;

        return this;
    }

    /**
     * Send a SurfaceControl transaction to the server, which the server will apply in sync with
     * the next bounds change. As this uses deferred transaction and not BLAST it is only
     * able to sync with a single window, and the first visible window in this hierarchy of type
     * BASE_APPLICATION to resize will be used. If there are bound changes included in this
     * WindowContainer transaction (from setBounds or scheduleFinishEnterPip), the SurfaceControl
     * transaction will be synced with those bounds. If there are no changes, then
     * the SurfaceControl transaction will be synced with the next bounds change. This means
     * that you can call this, apply the WindowContainer transaction, and then later call
     * dismissPip() to achieve synchronization.
     */
    @NonNull
    public WindowContainerTransaction setBoundsChangeTransaction(
            @NonNull WindowContainerToken container,@NonNull SurfaceControl.Transaction t) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mBoundsChangeTransaction = t;
        chg.mChangeMask |= Change.CHANGE_BOUNDS_TRANSACTION;
        return this;
    }

    /**
     * Like {@link #setBoundsChangeTransaction} but instead queues up a setPosition/WindowCrop
     * on a container's surface control. This is useful when a boundsChangeTransaction needs to be
     * queued up on a Task that won't be organized until the end of this window-container
     * transaction.
     *
     * This requires that, at the end of this transaction, `task` will be organized; otherwise
     * the server will throw an IllegalArgumentException.
     *
     * WARNING: Use this carefully. Whatever is set here should match the expected bounds after
     *          the transaction completes since it will likely be replaced by it. This call is
     *          intended to pre-emptively set bounds on a surface in sync with a buffer when
     *          otherwise the new bounds and the new buffer would update on different frames.
     *
     * TODO(b/134365562): remove once TaskOrg drives full-screen or BLAST is enabled.
     *
     * @hide
     */
    @NonNull
    public WindowContainerTransaction setBoundsChangeTransaction(
            @NonNull WindowContainerToken task, @NonNull Rect surfaceBounds) {
        Change chg = getOrCreateChange(task.asBinder());
        if (chg.mBoundsChangeSurfaceBounds == null) {
            chg.mBoundsChangeSurfaceBounds = new Rect();
        }
        chg.mBoundsChangeSurfaceBounds.set(surfaceBounds);
        chg.mChangeMask |= Change.CHANGE_BOUNDS_TRANSACTION_RECT;
        return this;
    }

    /**
     * Set the windowing mode of children of a given root task, without changing
     * the windowing mode of the Task itself. This can be used during transitions
     * for example to make the activity render it's fullscreen configuration
     * while the Task is still in PIP, so you can complete the animation.
     *
     * TODO(b/134365562): Can be removed once TaskOrg drives full-screen
     */
    @NonNull
    public WindowContainerTransaction setActivityWindowingMode(
            @NonNull WindowContainerToken container, int windowingMode) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mActivityWindowingMode = windowingMode;
        return this;
    }

    /**
     * Sets the windowing mode of the given container.
     */
    @NonNull
    public WindowContainerTransaction setWindowingMode(
            @NonNull WindowContainerToken container, int windowingMode) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mWindowingMode = windowingMode;
        return this;
    }

    /**
     * Sets whether a container or any of its children can be focusable. When {@code false}, no
     * child can be focused; however, when {@code true}, it is still possible for children to be
     * non-focusable due to WM policy.
     */
    @NonNull
    public WindowContainerTransaction setFocusable(
            @NonNull WindowContainerToken container, boolean focusable) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mFocusable = focusable;
        chg.mChangeMask |= Change.CHANGE_FOCUSABLE;
        return this;
    }

    /**
     * Sets whether a container or its children should be hidden. When {@code false}, the existing
     * visibility of the container applies, but when {@code true} the container will be forced
     * to be hidden.
     */
    @NonNull
    public WindowContainerTransaction setHidden(
            @NonNull WindowContainerToken container, boolean hidden) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mHidden = hidden;
        chg.mChangeMask |= Change.CHANGE_HIDDEN;
        return this;
    }

    /**
     * Set the smallestScreenWidth of a container.
     */
    @NonNull
    public WindowContainerTransaction setSmallestScreenWidthDp(
            @NonNull WindowContainerToken container, int widthDp) {
        Change cfg = getOrCreateChange(container.asBinder());
        cfg.mConfiguration.smallestScreenWidthDp = widthDp;
        cfg.mConfigSetMask |= ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
        return this;
    }

    /**
     * Reparents a container into another one. The effect of a {@code null} parent can vary. For
     * example, reparenting a stack to {@code null} will reparent it to its display.
     *
     * @param onTop When {@code true}, the child goes to the top of parent; otherwise it goes to
     *              the bottom.
     */
    @NonNull
    public WindowContainerTransaction reparent(@NonNull WindowContainerToken child,
            @Nullable WindowContainerToken parent, boolean onTop) {
        mHierarchyOps.add(new HierarchyOp(child.asBinder(),
                parent == null ? null : parent.asBinder(), onTop));
        return this;
    }

    /**
     * Reorders a container within its parent.
     *
     * @param onTop When {@code true}, the child goes to the top of parent; otherwise it goes to
     *              the bottom.
     */
    @NonNull
    public WindowContainerTransaction reorder(@NonNull WindowContainerToken child, boolean onTop) {
        mHierarchyOps.add(new HierarchyOp(child.asBinder(), onTop));
        return this;
    }

    /**
     * Merges another WCT into this one.
     * @param transfer When true, this will transfer everything from other potentially leaving
     *                 other in an unusable state. When false, other is left alone, but
     *                 SurfaceFlinger Transactions will not be merged.
     * @hide
     */
    public void merge(WindowContainerTransaction other, boolean transfer) {
        for (int i = 0, n = other.mChanges.size(); i < n; ++i) {
            final IBinder key = other.mChanges.keyAt(i);
            Change existing = mChanges.get(key);
            if (existing == null) {
                existing = new Change();
                mChanges.put(key, existing);
            }
            existing.merge(other.mChanges.valueAt(i), transfer);
        }
        for (int i = 0, n = other.mHierarchyOps.size(); i < n; ++i) {
            mHierarchyOps.add(transfer ? other.mHierarchyOps.get(i)
                    : new HierarchyOp(other.mHierarchyOps.get(i)));
        }
    }

    /** @hide */
    public Map<IBinder, Change> getChanges() {
        return mChanges;
    }

    /** @hide */
    public List<HierarchyOp> getHierarchyOps() {
        return mHierarchyOps;
    }

    @Override
    @NonNull
    public String toString() {
        return "WindowContainerTransaction { changes = " + mChanges + " hops = " + mHierarchyOps
                + " }";
    }

    @Override
    /** @hide */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeMap(mChanges);
        dest.writeList(mHierarchyOps);
    }

    @Override
    /** @hide */
    public int describeContents() {
        return 0;
    }

    @NonNull
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
     * @hide
     */
    public static class Change implements Parcelable {
        public static final int CHANGE_FOCUSABLE = 1;
        public static final int CHANGE_BOUNDS_TRANSACTION = 1 << 1;
        public static final int CHANGE_PIP_CALLBACK = 1 << 2;
        public static final int CHANGE_HIDDEN = 1 << 3;
        public static final int CHANGE_BOUNDS_TRANSACTION_RECT = 1 << 4;

        private final Configuration mConfiguration = new Configuration();
        private boolean mFocusable = true;
        private boolean mHidden = false;
        private int mChangeMask = 0;
        private @ActivityInfo.Config int mConfigSetMask = 0;
        private @WindowConfiguration.WindowConfig int mWindowSetMask = 0;

        private Rect mPinnedBounds = null;
        private SurfaceControl.Transaction mBoundsChangeTransaction = null;
        private Rect mBoundsChangeSurfaceBounds = null;

        private int mActivityWindowingMode = -1;
        private int mWindowingMode = -1;

        public Change() {}

        protected Change(Parcel in) {
            mConfiguration.readFromParcel(in);
            mFocusable = in.readBoolean();
            mHidden = in.readBoolean();
            mChangeMask = in.readInt();
            mConfigSetMask = in.readInt();
            mWindowSetMask = in.readInt();
            if ((mChangeMask & Change.CHANGE_PIP_CALLBACK) != 0) {
                mPinnedBounds = new Rect();
                mPinnedBounds.readFromParcel(in);
            }
            if ((mChangeMask & Change.CHANGE_BOUNDS_TRANSACTION) != 0) {
                mBoundsChangeTransaction =
                    SurfaceControl.Transaction.CREATOR.createFromParcel(in);
            }
            if ((mChangeMask & Change.CHANGE_BOUNDS_TRANSACTION_RECT) != 0) {
                mBoundsChangeSurfaceBounds = new Rect();
                mBoundsChangeSurfaceBounds.readFromParcel(in);
            }

            mWindowingMode = in.readInt();
            mActivityWindowingMode = in.readInt();
        }

        /**
         * @param transfer When true, this will transfer other into this leaving other in an
         *                 undefined state. Use this if you don't intend to use other. When false,
         *                 SurfaceFlinger Transactions will not merge.
         */
        public void merge(Change other, boolean transfer) {
            mConfiguration.setTo(other.mConfiguration, other.mConfigSetMask, other.mWindowSetMask);
            mConfigSetMask |= other.mConfigSetMask;
            mWindowSetMask |= other.mWindowSetMask;
            if ((other.mChangeMask & CHANGE_FOCUSABLE) != 0) {
                mFocusable = other.mFocusable;
            }
            if (transfer && (other.mChangeMask & CHANGE_BOUNDS_TRANSACTION) != 0) {
                mBoundsChangeTransaction = other.mBoundsChangeTransaction;
                other.mBoundsChangeTransaction = null;
            }
            if ((other.mChangeMask & CHANGE_PIP_CALLBACK) != 0) {
                mPinnedBounds = transfer ? other.mPinnedBounds : new Rect(other.mPinnedBounds);
            }
            if ((other.mChangeMask & CHANGE_HIDDEN) != 0) {
                mHidden = other.mHidden;
            }
            mChangeMask |= other.mChangeMask;
            if (other.mActivityWindowingMode >= 0) {
                mActivityWindowingMode = other.mActivityWindowingMode;
            }
            if (other.mWindowingMode >= 0) {
                mWindowingMode = other.mWindowingMode;
            }
            if (other.mBoundsChangeSurfaceBounds != null) {
                mBoundsChangeSurfaceBounds = transfer ? other.mBoundsChangeSurfaceBounds
                        : new Rect(other.mBoundsChangeSurfaceBounds);
            }
        }

        public int getWindowingMode() {
            return mWindowingMode;
        }

        public int getActivityWindowingMode() {
            return mActivityWindowingMode;
        }

        public Configuration getConfiguration() {
            return mConfiguration;
        }

        /** Gets the requested focusable state */
        public boolean getFocusable() {
            if ((mChangeMask & CHANGE_FOCUSABLE) == 0) {
                throw new RuntimeException("Focusable not set. check CHANGE_FOCUSABLE first");
            }
            return mFocusable;
        }

        /** Gets the requested hidden state */
        public boolean getHidden() {
            if ((mChangeMask & CHANGE_HIDDEN) == 0) {
                throw new RuntimeException("Hidden not set. check CHANGE_HIDDEN first");
            }
            return mHidden;
        }

        public int getChangeMask() {
            return mChangeMask;
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

        public SurfaceControl.Transaction getBoundsChangeTransaction() {
            return mBoundsChangeTransaction;
        }

        public Rect getBoundsChangeSurfaceBounds() {
            return mBoundsChangeSurfaceBounds;
        }

        @Override
        public String toString() {
            final boolean changesBounds =
                    (mConfigSetMask & ActivityInfo.CONFIG_WINDOW_CONFIGURATION) != 0
                            && ((mWindowSetMask & WindowConfiguration.WINDOW_CONFIG_BOUNDS)
                                    != 0);
            final boolean changesAppBounds =
                    (mConfigSetMask & ActivityInfo.CONFIG_WINDOW_CONFIGURATION) != 0
                            && ((mWindowSetMask & WindowConfiguration.WINDOW_CONFIG_APP_BOUNDS)
                                    != 0);
            final boolean changesSs = (mConfigSetMask & ActivityInfo.CONFIG_SCREEN_SIZE) != 0;
            final boolean changesSss =
                    (mConfigSetMask & ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE) != 0;
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            if (changesBounds) {
                sb.append("bounds:" + mConfiguration.windowConfiguration.getBounds() + ",");
            }
            if (changesAppBounds) {
                sb.append("appbounds:" + mConfiguration.windowConfiguration.getAppBounds() + ",");
            }
            if (changesSss) {
                sb.append("ssw:" + mConfiguration.smallestScreenWidthDp + ",");
            }
            if (changesSs) {
                sb.append("sw/h:" + mConfiguration.screenWidthDp + "x"
                        + mConfiguration.screenHeightDp + ",");
            }
            if ((mChangeMask & CHANGE_FOCUSABLE) != 0) {
                sb.append("focusable:" + mFocusable + ",");
            }
            if (mBoundsChangeTransaction != null) {
                sb.append("hasBoundsTransaction,");
            }
            sb.append("}");
            return sb.toString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            mConfiguration.writeToParcel(dest, flags);
            dest.writeBoolean(mFocusable);
            dest.writeBoolean(mHidden);
            dest.writeInt(mChangeMask);
            dest.writeInt(mConfigSetMask);
            dest.writeInt(mWindowSetMask);

            if (mPinnedBounds != null) {
                mPinnedBounds.writeToParcel(dest, flags);
            }
            if (mBoundsChangeTransaction != null) {
                mBoundsChangeTransaction.writeToParcel(dest, flags);
            }
            if (mBoundsChangeSurfaceBounds != null) {
                mBoundsChangeSurfaceBounds.writeToParcel(dest, flags);
            }

            dest.writeInt(mWindowingMode);
            dest.writeInt(mActivityWindowingMode);
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

    /**
     * Holds information about a reparent/reorder operation in the hierarchy. This is separate from
     * Changes because they must be executed in the same order that they are added.
     * @hide
     */
    public static class HierarchyOp implements Parcelable {
        private final IBinder mContainer;

        // If this is same as mContainer, then only change position, don't reparent.
        private final IBinder mReparent;

        // Moves/reparents to top of parent when {@code true}, otherwise moves/reparents to bottom.
        private final boolean mToTop;

        public HierarchyOp(@NonNull IBinder container, @Nullable IBinder reparent, boolean toTop) {
            mContainer = container;
            mReparent = reparent;
            mToTop = toTop;
        }

        public HierarchyOp(@NonNull IBinder container, boolean toTop) {
            mContainer = container;
            mReparent = container;
            mToTop = toTop;
        }

        public HierarchyOp(@NonNull HierarchyOp copy) {
            mContainer = copy.mContainer;
            mReparent = copy.mReparent;
            mToTop = copy.mToTop;
        }

        protected HierarchyOp(Parcel in) {
            mContainer = in.readStrongBinder();
            mReparent = in.readStrongBinder();
            mToTop = in.readBoolean();
        }

        public boolean isReparent() {
            return mContainer != mReparent;
        }

        @Nullable
        public IBinder getNewParent() {
            return mReparent;
        }

        @NonNull
        public IBinder getContainer() {
            return mContainer;
        }

        public boolean getToTop() {
            return mToTop;
        }

        @Override
        public String toString() {
            if (isReparent()) {
                return "{reparent: " + mContainer + " to " + (mToTop ? "top of " : "bottom of ")
                        + mReparent + "}";
            } else {
                return "{reorder: " + mContainer + " to " + (mToTop ? "top" : "bottom") + "}";
            }
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeStrongBinder(mContainer);
            dest.writeStrongBinder(mReparent);
            dest.writeBoolean(mToTop);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<HierarchyOp> CREATOR = new Creator<HierarchyOp>() {
            @Override
            public HierarchyOp createFromParcel(Parcel in) {
                return new HierarchyOp(in);
            }

            @Override
            public HierarchyOp[] newArray(int size) {
                return new HierarchyOp[size];
            }
        };
    }
}
