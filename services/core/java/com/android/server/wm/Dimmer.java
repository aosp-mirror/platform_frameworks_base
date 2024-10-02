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

package com.android.server.wm;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_DIMMER;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;
import com.android.window.flags.Flags;


/**
 * Utility class for use by a WindowContainer implementation to add "DimLayer" support, that is
 * black layers of varying opacity at various Z-levels which create the effect of a Dim.
 */
class Dimmer {

    /**
     * The {@link WindowContainer} that our Dims are bounded to. We may be dimming on behalf of the
     * host, some controller of it, or one of the hosts children.
     */
    private final WindowContainer<?> mHost;

    private static final String TAG = TAG_WITH_CLASS_NAME ? "Dimmer" : TAG_WM;
    DimState mDimState;
    final DimmerAnimationHelper.AnimationAdapterFactory mAnimationAdapterFactory;

    /**
     * Controls the dim behaviour
     */
    protected class DimState {
        /** Related objects */
        SurfaceControl mDimSurface;
        final WindowContainer<?> mHostContainer;
        // The last container to request to dim
        private WindowState mLastDimmingWindow;
        /** Animation */
        private final DimmerAnimationHelper mAnimationHelper;
        boolean mSkipAnimation = false;
        // Determines whether the dim layer should animate before destroying.
        boolean mAnimateExit = true;
        /** Surface visibility and bounds */
        private boolean mIsVisible = false;
        // TODO(b/64816140): Remove after confirming dimmer layer always matches its container.
        final Rect mDimBounds = new Rect();

        DimState() {
            mHostContainer = mHost;
            mAnimationHelper = new DimmerAnimationHelper(mAnimationAdapterFactory);
            try {
                mDimSurface = makeDimLayer();
            } catch (Surface.OutOfResourcesException e) {
                Log.w(TAG, "OutOfResourcesException creating dim surface");
            }
        }

        void ensureVisible(@NonNull SurfaceControl.Transaction t) {
            if (!mIsVisible) {
                t.show(mDimSurface);
                t.setAlpha(mDimSurface, 0f);
                mIsVisible = true;
            }
        }

        void adjustSurfaceLayout(@NonNull SurfaceControl.Transaction t) {
            // TODO: Once we use geometry from hierarchy this falls away.
            t.setPosition(mDimSurface, mDimBounds.left, mDimBounds.top);
            t.setWindowCrop(mDimSurface, mDimBounds.width(), mDimBounds.height());
        }

        /**
         * Set the parameters to prepare the dim to change its appearance
         */
        void prepareLookChange(float alpha, int blurRadius) {
            mAnimationHelper.setRequestedAppearance(alpha, blurRadius);
        }

        /**
         * Prepare the dim for the exit animation
         */
        void exit(@NonNull SurfaceControl.Transaction t) {
            if (!mAnimateExit) {
                remove(t);
            } else {
                mAnimationHelper.setExitParameters();
                setReady(t);
            }
        }

        void remove(@NonNull SurfaceControl.Transaction t) {
            mAnimationHelper.stopCurrentAnimation(mDimSurface);
            if (mDimSurface.isValid()) {
                t.remove(mDimSurface);
                ProtoLog.d(WM_DEBUG_DIMMER,
                        "Removing dim surface %s on transaction %s", this, t);
            } else {
                Log.w(TAG, "Tried to remove " + mDimSurface + " multiple times\n");
            }
        }

        @Override
        public String toString() {
            return "Dimmer#DimState with host=" + mHostContainer + ", surface=" + mDimSurface;
        }

        /**
         * Set the parameters to prepare the dim to be relative parented to the dimming container
         */
        void prepareReparent(@Nullable WindowContainer<?> geometryParent,
                @NonNull WindowState relativeParent) {
            mAnimationHelper.setRequestedRelativeParent(relativeParent);
            mAnimationHelper.setRequestedGeometryParent(geometryParent);
        }

        /**
         * Call when all the changes have been requested to have them applied
         * @param t The transaction in which to apply the changes
         */
        void setReady(@NonNull SurfaceControl.Transaction t) {
            mAnimationHelper.applyChanges(t, this);
        }

        /**
         * Whether anyone is currently requesting the dim
         */
        boolean isDimming() {
            return mLastDimmingWindow != null
                    && (mHostContainer.isVisibleRequested() || !Flags.useTasksDimOnly());
        }

        private SurfaceControl makeDimLayer() {
            return mHost.makeChildSurface(null)
                    .setParent(mHost.getSurfaceControl())
                    .setColorLayer()
                    .setName("Dim Layer for - " + mHost.getName())
                    .setCallsite("DimLayer.makeDimLayer")
                    .build();
        }
    }

    protected Dimmer(@NonNull WindowContainer<?> host) {
        this(host, new DimmerAnimationHelper.AnimationAdapterFactory());
    }

    @VisibleForTesting
    Dimmer(@NonNull WindowContainer host,
                 @NonNull DimmerAnimationHelper.AnimationAdapterFactory animationFactory) {
        mHost = host;
        mAnimationAdapterFactory = animationFactory;
    }

    public boolean hostIsTask() {
        return mHost.asTask() != null;
    }

    /**
     * Mark all dims as pending completion on the next call to {@link #updateDims}
     *
     * Called before iterating on mHost's children, first step of dimming.
     * This is intended for us by the host container, to be called at the beginning of
     * {@link WindowContainer#prepareSurfaces}. After calling this, the container should
     * chain {@link WindowContainer#prepareSurfaces} down to its children to give them
     * a chance to request dims to continue.
     */
    void resetDimStates() {
        if (mDimState != null) {
            mDimState.mLastDimmingWindow = null;
        }
    }

    /**
     * Set the aspect of the dim layer, and request to keep dimming.
     * For each call to {@link WindowContainer#prepareSurfaces} the Dim state will be reset, and the
     * child should call setAppearance again to request the Dim to continue.
     * If multiple containers call this method, only the changes relative to the topmost will be
     * applied.
     * @param dimmingContainer  Container requesting the dim
     * @param alpha      Dim amount
     * @param blurRadius Blur amount
     */
    protected void adjustAppearance(@NonNull WindowState dimmingContainer,
                                    float alpha, int blurRadius) {
        final DimState d = obtainDimState(dimmingContainer);
        d.prepareLookChange(alpha, blurRadius);
    }

    /**
     * Position the dim relatively to the dimming container.
     * Normally called together with #setAppearance, it can be called alone to keep the dim parented
     * to a visible container until the next dimming container is ready.
     * If multiple containers call this method, only the changes relative to the topmost will be
     * applied.
     *
     * For each call to {@link WindowContainer#prepareSurfaces()} the DimState will be reset, and
     * the child of the host should call adjustRelativeLayer and {@link Dimmer#adjustAppearance} to
     * continue dimming. Indeed, this method won't be able to keep dimming or get a new DimState
     * without also adjusting the appearance.
     * @param geometryParent    The container that defines the geometry of the dim
     * @param dimmingContainer      The container that is dimming. The dim layer will be rel-z
     *                              parented below it
     */
    public void adjustPosition(@Nullable WindowContainer<?> geometryParent,
                                    @NonNull WindowState dimmingContainer) {
        if (mDimState != null) {
            mDimState.prepareReparent(geometryParent, dimmingContainer);
        }
    }

    /**
     * Call after invoking {@link WindowContainer#prepareSurfaces} on children as
     * described in {@link #resetDimStates}.
     *
     * @param t      A transaction in which to update the dims.
     * @return true if any Dims were updated.
     */
    boolean updateDims(@NonNull SurfaceControl.Transaction t) {
        if (mDimState == null) {
            return false;
        }
        if (!mDimState.isDimming()) {
            // No one is dimming, fade out and remove the dim
            mDimState.exit(t);
            mDimState = null;
            return false;
        } else {
            // Someone is dimming, show the requested changes
            if (!Flags.useTasksDimOnly()) {
                mDimState.adjustSurfaceLayout(t);
            }
            if (!mDimState.mIsVisible && mDimState.mLastDimmingWindow != null
                    && mDimState.mLastDimmingWindow.mActivityRecord != null
                    && mDimState.mLastDimmingWindow.mActivityRecord.mStartingData != null) {
                // Skip enter animation while starting window is on top of its activity
                mDimState.mSkipAnimation = true;
            }
            mDimState.setReady(t);
            return true;
        }
    }

    @NonNull
    private DimState obtainDimState(@NonNull WindowState window) {
        if (mDimState == null) {
            mDimState = new DimState();
        }
        mDimState.mLastDimmingWindow = window;
        return mDimState;
    }

    /** Returns non-null bounds if the dimmer is showing. */
    @VisibleForTesting
    SurfaceControl getDimLayer() {
        return mDimState != null ? mDimState.mDimSurface : null;
    }

    @Deprecated
    Rect getDimBounds() {
        return mDimState != null ? mDimState.mDimBounds : null;
    }

    void dontAnimateExit() {
        if (mDimState != null) {
            mDimState.mAnimateExit = false;
        }
    }
}
