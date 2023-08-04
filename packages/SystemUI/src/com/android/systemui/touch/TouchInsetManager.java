/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.touch;

import android.graphics.Rect;
import android.graphics.Region;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;

import androidx.concurrent.futures.CallbackToFutureAdapter;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Executor;

/**
 * {@link TouchInsetManager} handles setting the touchable inset regions for a given View. This
 * is useful for passing through touch events for all but select areas.
 */
public class TouchInsetManager {
    /**
     * {@link TouchInsetSession} provides an individualized session with the
     * {@link TouchInsetManager}, linking any action to the client.
     */
    public static class TouchInsetSession {
        private final TouchInsetManager mManager;

        private final HashSet<View> mTrackedViews;
        private final Executor mExecutor;

        private final View.OnLayoutChangeListener mOnLayoutChangeListener =
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom)
                        -> updateTouchRegion();

        /**
         * Default constructor
         * @param manager The parent {@link TouchInsetManager} which will be affected by actions on
         *                this session.
         * @param rootView The parent of views that will be tracked.
         * @param executor An executor for marshalling operations.
         */
        TouchInsetSession(TouchInsetManager manager, Executor executor) {
            mManager = manager;
            mTrackedViews = new HashSet<>();
            mExecutor = executor;
        }

        /**
         * Adds a descendant of the root view to be tracked.
         * @param view {@link View} to be tracked.
         */
        public void addViewToTracking(View view) {
            mExecutor.execute(() -> {
                mTrackedViews.add(view);
                view.addOnLayoutChangeListener(mOnLayoutChangeListener);
                updateTouchRegion();
            });
        }

        /**
         * Removes a view from further tracking
         * @param view {@link View} to be removed.
         */
        public void removeViewFromTracking(View view) {
            mExecutor.execute(() -> {
                mTrackedViews.remove(view);
                view.removeOnLayoutChangeListener(mOnLayoutChangeListener);
                updateTouchRegion();
            });
        }

        private void updateTouchRegion() {
            final Region cumulativeRegion = Region.obtain();

            mTrackedViews.stream().forEach(view -> {
                if (!view.isAttachedToWindow()) {
                    return;
                }
                final Rect boundaries = new Rect();
                view.getDrawingRect(boundaries);
                ((ViewGroup) view.getRootView()).offsetDescendantRectToMyCoords(view, boundaries);

                cumulativeRegion.op(boundaries, Region.Op.UNION);
            });

            mManager.setTouchRegion(this, cumulativeRegion);

            cumulativeRegion.recycle();
        }

        /**
         * Removes all tracked views and updates insets accordingly.
         */
        public void clear() {
            mExecutor.execute(() -> {
                mManager.clearRegion(this);
                mTrackedViews.clear();
            });
        }
    }

    private final HashMap<TouchInsetSession, Region> mDefinedRegions = new HashMap<>();
    private final Executor mExecutor;
    private final View mRootView;

    private final View.OnAttachStateChangeListener mAttachListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    updateTouchInset();
                }

                @Override
                public void onViewDetachedFromWindow(View v) {
                }
            };

    /**
     * Default constructor.
     * @param executor An {@link Executor} to marshal all operations on.
     * @param rootView The root {@link View} for all views in sessions.
     */
    public TouchInsetManager(Executor executor, View rootView) {
        mExecutor = executor;
        mRootView = rootView;
        mRootView.addOnAttachStateChangeListener(mAttachListener);

    }

    /**
     * Creates a new associated session.
     */
    public TouchInsetSession createSession() {
        return new TouchInsetSession(this, mExecutor);
    }

    /**
     * Checks to see if the given point coordinates fall within an inset region.
     */
    public ListenableFuture<Boolean> checkWithinTouchRegion(int x, int y) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            mExecutor.execute(() -> completer.set(
                    mDefinedRegions.values().stream().anyMatch(region -> region.contains(x, y))));

            return "DreamOverlayTouchMonitor::checkWithinTouchRegion";
        });
    }

    private void updateTouchInset() {
        final ViewRootImpl viewRootImpl = mRootView.getViewRootImpl();

        if (viewRootImpl == null) {
            return;
        }

        final Region aggregateRegion = Region.obtain();

        for (Region region : mDefinedRegions.values()) {
            aggregateRegion.op(region, Region.Op.UNION);
        }

        viewRootImpl.setTouchableRegion(aggregateRegion);

        aggregateRegion.recycle();
    }

    protected void setTouchRegion(TouchInsetSession session, Region region) {
        final Region introducedRegion = Region.obtain(region);
        mExecutor.execute(() -> {
            mDefinedRegions.put(session, introducedRegion);
            updateTouchInset();
        });
    }

    private void clearRegion(TouchInsetSession session) {
        mExecutor.execute(() -> {
            final Region storedRegion = mDefinedRegions.remove(session);

            if (storedRegion != null) {
                storedRegion.recycle();
            }

            updateTouchInset();
        });
    }
}
