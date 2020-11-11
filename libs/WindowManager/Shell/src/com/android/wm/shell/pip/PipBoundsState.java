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

package com.android.wm.shell.pip;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.util.Size;
import android.view.DisplayInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayLayout;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Singleton source of truth for the current state of PIP bounds.
 */
public final class PipBoundsState {
    public static final int STASH_TYPE_NONE = 0;
    public static final int STASH_TYPE_LEFT = 1;
    public static final int STASH_TYPE_RIGHT = 2;

    @IntDef(prefix = { "STASH_TYPE_" }, value =  {
            STASH_TYPE_NONE,
            STASH_TYPE_LEFT,
            STASH_TYPE_RIGHT
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface StashType {}

    private static final String TAG = PipBoundsState.class.getSimpleName();

    private final @NonNull Rect mBounds = new Rect();
    private final Context mContext;
    private float mAspectRatio;
    private int mStashedState = STASH_TYPE_NONE;
    private int mStashOffset;
    private PipReentryState mPipReentryState;
    private ComponentName mLastPipComponentName;
    private final DisplayInfo mDisplayInfo = new DisplayInfo();
    private final DisplayLayout mDisplayLayout = new DisplayLayout();
    /** The current minimum edge size of PIP. */
    private int mMinEdgeSize;
    /** The preferred minimum (and default) size specified by apps. */
    private Size mOverrideMinSize;
    private final @NonNull AnimatingBoundsState mAnimatingBoundsState = new AnimatingBoundsState();
    private boolean mIsImeShowing;
    private int mImeHeight;
    private boolean mIsShelfShowing;
    private int mShelfHeight;

    private Runnable mOnMinimalSizeChangeCallback;
    private BiConsumer<Boolean, Integer> mOnShelfVisibilityChangeCallback;

    public PipBoundsState(Context context) {
        mContext = context;
        reloadResources();
    }

    /**
     * Reloads the resources.
     */
    public void onConfigurationChanged() {
        reloadResources();
    }

    private void reloadResources() {
        mStashOffset = mContext.getResources()
                .getDimensionPixelSize(R.dimen.pip_stash_offset);
    }

    /**
     * Set the current PIP bounds.
     */
    public void setBounds(@NonNull Rect bounds) {
        mBounds.set(bounds);
    }

    @NonNull
    public Rect getBounds() {
        return new Rect(mBounds);
    }

    /**
     * Dictate where PiP currently should be stashed, if at all.
     */
    public void setStashed(@StashType int stashedState) {
        mStashedState = stashedState;
    }

    /**
     * Return where the PiP is stashed, if at all.
     * @return {@code STASH_NONE}, {@code STASH_LEFT} or {@code STASH_RIGHT}.
     */
    public @StashType int getStashedState() {
        return mStashedState;
    }

    /**
     * Whether PiP is stashed or not.
     */
    public boolean isStashed() {
        return mStashedState != STASH_TYPE_NONE;
    }

    /**
     * Returns the offset from the edge of the screen for PiP stash.
     */
    public int getStashOffset() {
        return mStashOffset;
    }

    public void setAspectRatio(float aspectRatio) {
        mAspectRatio = aspectRatio;
    }

    public float getAspectRatio() {
        return mAspectRatio;
    }

    /**
     * Save the reentry state to restore to when re-entering PIP mode.
     *
     * TODO(b/169373982): consider refactoring this so that this class alone can use mBounds and
     * calculate the snap fraction to save for re-entry.
     */
    public void saveReentryState(@NonNull Rect bounds, float fraction) {
        mPipReentryState = new PipReentryState(new Size(bounds.width(), bounds.height()), fraction);
    }

    /**
     * Returns the saved reentry state.
     */
    @Nullable
    public PipReentryState getReentryState() {
        return mPipReentryState;
    }

    /**
     * Set the last {@link ComponentName} to enter PIP mode.
     */
    public void setLastPipComponentName(ComponentName lastPipComponentName) {
        final boolean changed = !Objects.equals(mLastPipComponentName, lastPipComponentName);
        mLastPipComponentName = lastPipComponentName;
        if (changed) {
            clearReentryState();
        }
    }

    public ComponentName getLastPipComponentName() {
        return mLastPipComponentName;
    }

    @NonNull
    public DisplayInfo getDisplayInfo() {
        return mDisplayInfo;
    }

    /**
     * Update the display info.
     */
    public void setDisplayInfo(@NonNull DisplayInfo displayInfo) {
        mDisplayInfo.copyFrom(displayInfo);
    }

    public void setDisplayRotation(int rotation) {
        mDisplayInfo.rotation = rotation;
    }

    /**
     * Returns the display's bound.
     */
    @NonNull
    public Rect getDisplayBounds() {
        return new Rect(0, 0, mDisplayInfo.logicalWidth, mDisplayInfo.logicalHeight);
    }

    /**
     * Update the display layout.
     */
    public void setDisplayLayout(@NonNull DisplayLayout displayLayout) {
        mDisplayLayout.set(displayLayout);
    }

    @NonNull
    public DisplayLayout getDisplayLayout() {
        return mDisplayLayout;
    }

    @VisibleForTesting
    void clearReentryState() {
        mPipReentryState = null;
    }

    /** Set the PIP minimum edge size. */
    public void setMinEdgeSize(int minEdgeSize) {
        mMinEdgeSize = minEdgeSize;
    }

    /** Returns the PIP's current minimum edge size. */
    public int getMinEdgeSize() {
        return mMinEdgeSize;
    }

    /**
     * Sets the preferred size of PIP as specified by the activity in PIP mode.
     */
    public void setOverrideMinSize(Size overrideMinSize) {
        final boolean changed = !Objects.equals(overrideMinSize, mOverrideMinSize);
        mOverrideMinSize = overrideMinSize;
        if (changed && mOnMinimalSizeChangeCallback != null) {
            mOnMinimalSizeChangeCallback.run();
        }
    }

    /** Returns the preferred minimal size specified by the activity in PIP. */
    public Size getOverrideMinSize() {
        return mOverrideMinSize;
    }

    /** Returns the minimum edge size of the override minimum size, or 0 if not set. */
    public int getOverrideMinEdgeSize() {
        if (mOverrideMinSize == null) return 0;
        return Math.min(mOverrideMinSize.getWidth(), mOverrideMinSize.getHeight());
    }

    public AnimatingBoundsState getAnimatingBoundsState() {
        return mAnimatingBoundsState;
    }

    /** Set whether the IME is currently showing and its height. */
    public void setImeVisibility(boolean imeShowing, int imeHeight) {
        mIsImeShowing = imeShowing;
        mImeHeight = imeHeight;
    }

    /** Returns whether the IME is currently showing. */
    public boolean isImeShowing() {
        return mIsImeShowing;
    }

    /** Returns the IME height. */
    public int getImeHeight() {
        return mImeHeight;
    }

    /** Set whether the shelf is showing and its height. */
    public void setShelfVisibility(boolean showing, int height) {
        final boolean shelfShowing = showing && height > 0;
        if (shelfShowing == mIsShelfShowing && height == mShelfHeight) {
            return;
        }

        mIsShelfShowing = showing;
        mShelfHeight = height;
        if (mOnShelfVisibilityChangeCallback != null) {
            mOnShelfVisibilityChangeCallback.accept(mIsShelfShowing, mShelfHeight);
        }
    }

    /** Returns whether the shelf is currently showing. */
    public boolean isShelfShowing() {
        return mIsShelfShowing;
    }

    /** Returns the shelf height. */
    public int getShelfHeight() {
        return mShelfHeight;
    }

    /**
     * Registers a callback when the minimal size of PIP that is set by the app changes.
     */
    public void setOnMinimalSizeChangeCallback(Runnable onMinimalSizeChangeCallback) {
        mOnMinimalSizeChangeCallback = onMinimalSizeChangeCallback;
    }

    /** Set a callback to be notified when the shelf visibility changes. */
    public void setOnShelfVisibilityChangeCallback(
            BiConsumer<Boolean, Integer> onShelfVisibilityChangeCallback) {
        mOnShelfVisibilityChangeCallback = onShelfVisibilityChangeCallback;
    }

    /** Source of truth for the current animation bounds of PIP. */
    public static class AnimatingBoundsState {
        /** The bounds used when PIP is being dragged or animated. */
        private final Rect mTemporaryBounds = new Rect();
        /** The destination bounds to which PIP is animating. */
        private final Rect mAnimatingToBounds = new Rect();

        /** Whether PIP is being dragged or animated (e.g. resizing, in fling, etc). */
        public boolean isAnimating() {
            return !mTemporaryBounds.isEmpty();
        }

        /** Set the temporary bounds used to represent the drag or animation bounds of PIP. */
        public void setTemporaryBounds(Rect bounds) {
            mTemporaryBounds.set(bounds);
        }

        /** Set the bounds to which PIP is animating. */
        public void setAnimatingToBounds(Rect bounds) {
            mAnimatingToBounds.set(bounds);
        }

        /** Called when all ongoing dragging and animation operations have ended. */
        public void onAllAnimationsEnded() {
            mTemporaryBounds.setEmpty();
        }

        /** Called when an ongoing physics animation has ended. */
        public void onPhysicsAnimationEnded() {
            mAnimatingToBounds.setEmpty();
        }

        /** Returns the temporary animation bounds. */
        public Rect getTemporaryBounds() {
            return mTemporaryBounds;
        }

        /** Returns the destination bounds to which PIP is currently animating. */
        public Rect getAnimatingToBounds() {
            return mAnimatingToBounds;
        }

        void dump(PrintWriter pw, String prefix) {
            final String innerPrefix = prefix + "  ";
            pw.println(prefix + AnimatingBoundsState.class.getSimpleName());
            pw.println(innerPrefix + "mTemporaryBounds=" + mTemporaryBounds);
            pw.println(innerPrefix + "mAnimatingToBounds=" + mAnimatingToBounds);
        }
    }

    static final class PipReentryState {
        private static final String TAG = PipReentryState.class.getSimpleName();

        private final @NonNull Size mSize;
        private final float mSnapFraction;

        PipReentryState(@NonNull Size size, float snapFraction) {
            mSize = size;
            mSnapFraction = snapFraction;
        }

        @NonNull
        Size getSize() {
            return mSize;
        }

        float getSnapFraction() {
            return mSnapFraction;
        }

        void dump(PrintWriter pw, String prefix) {
            final String innerPrefix = prefix + "  ";
            pw.println(prefix + TAG);
            pw.println(innerPrefix + "mSize=" + mSize);
            pw.println(innerPrefix + "mSnapFraction=" + mSnapFraction);
        }
    }

    /**
     * Dumps internal state.
     */
    public void dump(PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        pw.println(innerPrefix + "mBounds=" + mBounds);
        pw.println(innerPrefix + "mLastPipComponentName=" + mLastPipComponentName);
        pw.println(innerPrefix + "mAspectRatio=" + mAspectRatio);
        pw.println(innerPrefix + "mDisplayInfo=" + mDisplayInfo);
        pw.println(innerPrefix + "mDisplayLayout=" + mDisplayLayout);
        pw.println(innerPrefix + "mStashedState=" + mStashedState);
        pw.println(innerPrefix + "mStashOffset=" + mStashOffset);
        pw.println(innerPrefix + "mMinEdgeSize=" + mMinEdgeSize);
        pw.println(innerPrefix + "mOverrideMinSize=" + mOverrideMinSize);
        pw.println(innerPrefix + "mIsImeShowing=" + mIsImeShowing);
        pw.println(innerPrefix + "mImeHeight=" + mImeHeight);
        pw.println(innerPrefix + "mIsShelfShowing=" + mIsShelfShowing);
        pw.println(innerPrefix + "mShelfHeight=" + mShelfHeight);
        if (mPipReentryState == null) {
            pw.println(innerPrefix + "mPipReentryState=null");
        } else {
            mPipReentryState.dump(pw, innerPrefix);
        }
        mAnimatingBoundsState.dump(pw, innerPrefix);
    }
}
