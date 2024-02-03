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
import android.util.Log;
import android.view.AttachedSurfaceControl;
import android.view.View;
import android.view.ViewGroup;

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
    private static final String TAG = "TouchInsetManager";
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
                        -> updateTouchRegions();

        private final View.OnAttachStateChangeListener mAttachListener =
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        updateTouchRegions();
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        updateTouchRegions();
                    }
                };

        /**
         * Default constructor
         * @param manager The parent {@link TouchInsetManager} which will be affected by actions on
         *                this session.
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
                view.addOnAttachStateChangeListener(mAttachListener);
                view.addOnLayoutChangeListener(mOnLayoutChangeListener);
                updateTouchRegions();
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
                view.removeOnAttachStateChangeListener(mAttachListener);
                updateTouchRegions();
            });
        }

        private void updateTouchRegions() {
            mExecutor.execute(() -> {
                final HashMap<AttachedSurfaceControl, Region> affectedSurfaces = new HashMap<>();
                if (mTrackedViews.isEmpty()) {
                    return;
                }

                mTrackedViews.stream().forEach(view -> {
                    final AttachedSurfaceControl surface = view.getRootSurfaceControl();

                    // Detached views will not have a surface control.
                    if (surface == null) {
                        return;
                    }

                    if (!affectedSurfaces.containsKey(surface)) {
                        affectedSurfaces.put(surface, Region.obtain());
                    }
                    final Rect boundaries = new Rect();
                    view.getDrawingRect(boundaries);
                    ((ViewGroup) view.getRootView())
                            .offsetDescendantRectToMyCoords(view, boundaries);
                    affectedSurfaces.get(surface).op(boundaries, Region.Op.UNION);
                });
                mManager.setTouchRegions(this, affectedSurfaces);
            });
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

    private final HashMap<TouchInsetSession, HashMap<AttachedSurfaceControl, Region>>
            mSessionRegions = new HashMap<>();
    private final HashMap<AttachedSurfaceControl, Region> mLastAffectedSurfaces = new HashMap();
    private final Executor mExecutor;

    /**
     * Default constructor.
     * @param executor An {@link Executor} to marshal all operations on.
     */
    public TouchInsetManager(Executor executor) {
        mExecutor = executor;
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
                    mLastAffectedSurfaces.values().stream().anyMatch(
                            region -> region.contains(x, y))));

            return "DreamOverlayTouchMonitor::checkWithinTouchRegion";
        });
    }

    private void updateTouchInsets() {
        // Get affected
        final HashMap<AttachedSurfaceControl, Region> affectedSurfaces = new HashMap<>();
        mSessionRegions.values().stream().forEach(regionMapping -> {
            regionMapping.entrySet().stream().forEach(entry -> {
                final AttachedSurfaceControl surface = entry.getKey();

                if (!affectedSurfaces.containsKey(surface)) {
                    affectedSurfaces.put(surface, Region.obtain());
                }

                affectedSurfaces.get(surface).op(entry.getValue(), Region.Op.UNION);
            });
        });

        affectedSurfaces.entrySet().stream().forEach(entry -> {
            entry.getKey().setTouchableRegion(entry.getValue());
        });

        mLastAffectedSurfaces.entrySet().forEach(entry -> {
            final AttachedSurfaceControl surface = entry.getKey();
            if (!affectedSurfaces.containsKey(surface)) {
                surface.setTouchableRegion(null);
            }
            entry.getValue().recycle();
        });

        mLastAffectedSurfaces.clear();
        mLastAffectedSurfaces.putAll(affectedSurfaces);
    }

    protected void setTouchRegions(TouchInsetSession session,
            HashMap<AttachedSurfaceControl, Region> regions) {
        mExecutor.execute(() -> {
            recycleRegions(session);
            mSessionRegions.put(session, regions);
            updateTouchInsets();
        });
    }

    private void recycleRegions(TouchInsetSession session) {
        if (!mSessionRegions.containsKey(session)) {
            Log.w(TAG,  "Removing a session with no regions:" + session);
            return;
        }

        for (Region region : mSessionRegions.get(session).values()) {
            region.recycle();
        }
    }

    private void clearRegion(TouchInsetSession session) {
        mExecutor.execute(() -> {
            recycleRegions(session);
            mSessionRegions.remove(session);
            updateTouchInsets();
        });
    }
}
