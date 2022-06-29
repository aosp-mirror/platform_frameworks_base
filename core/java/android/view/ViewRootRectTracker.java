/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;

import com.android.internal.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

/**
 * Abstract class to track a collection of rects reported by the views under the same
 * {@link ViewRootImpl}.
 */
class ViewRootRectTracker {
    private final Function<View, List<Rect>> mRectCollector;
    private boolean mViewsChanged = false;
    private boolean mRootRectsChanged = false;
    private List<Rect> mRootRects = Collections.emptyList();
    private List<ViewInfo> mViewInfos = new ArrayList<>();
    private List<Rect> mRects = Collections.emptyList();

    /**
     * @param rectCollector given a view returns a list of the rects of interest for this
     *                      ViewRootRectTracker
     */
    ViewRootRectTracker(Function<View, List<Rect>> rectCollector) {
        mRectCollector = rectCollector;
    }

    public void updateRectsForView(@NonNull View view) {
        boolean found = false;
        final Iterator<ViewInfo> i = mViewInfos.iterator();
        while (i.hasNext()) {
            final ViewInfo info = i.next();
            final View v = info.getView();
            if (v == null || !v.isAttachedToWindow() || !v.isAggregatedVisible()) {
                mViewsChanged = true;
                i.remove();
                continue;
            }
            if (v == view) {
                found = true;
                info.mDirty = true;
                break;
            }
        }
        if (!found && view.isAttachedToWindow()) {
            mViewInfos.add(new ViewInfo(view));
            mViewsChanged = true;
        }
    }

    /**
     * @return all Rects from all visible Views in the global (root) coordinate system,
     * or {@code null} if Rects are unchanged since the last call to this method.
     */
    @Nullable
    public List<Rect> computeChangedRects() {
        if (computeChanges()) {
            return mRects;
        }
        return null;
    }

    /**
     * Computes changes to all Rects from all Views.
     * After calling this method, the updated list of Rects can be retrieved
     * with {@link #getLastComputedRects()}.
     *
     * @return {@code true} if there were changes, {@code false} otherwise.
     */
    public boolean computeChanges() {
        boolean changed = mRootRectsChanged;
        final Iterator<ViewInfo> i = mViewInfos.iterator();
        final List<Rect> rects = new ArrayList<>(mRootRects);
        while (i.hasNext()) {
            final ViewInfo info = i.next();
            switch (info.update()) {
                case ViewInfo.CHANGED:
                    changed = true;
                    // Deliberate fall-through
                case ViewInfo.UNCHANGED:
                    rects.addAll(info.mRects);
                    break;
                case ViewInfo.GONE:
                    mViewsChanged = true;
                    i.remove();
                    break;
            }
        }
        if (changed || mViewsChanged) {
            mViewsChanged = false;
            mRootRectsChanged = false;
            if (!mRects.equals(rects)) {
                mRects = rects;
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a List of all Rects from all visible Views in the global (root) coordinate system.
     * This list is only updated when calling {@link #computeChanges()} or
     * {@link #computeChangedRects()}.
     *
     * @return all Rects from all visible Views in the global (root) coordinate system
     */
    @NonNull
    public List<Rect> getLastComputedRects() {
        return mRects;
    }

    /**
     * Sets rects defined in the global (root) coordinate system, i.e. not for a specific view.
     */
    public void setRootRects(@NonNull List<Rect> rects) {
        Preconditions.checkNotNull(rects, "rects must not be null");
        mRootRects = rects;
        mRootRectsChanged = true;
    }

    @NonNull
    public List<Rect> getRootRects() {
        return mRootRects;
    }

    @NonNull
    private List<Rect> getTrackedRectsForView(@NonNull View v) {
        final List<Rect> rects = mRectCollector.apply(v);
        return rects == null ? Collections.emptyList() : rects;
    }

    private class ViewInfo {
        public static final int CHANGED = 0;
        public static final int UNCHANGED = 1;
        public static final int GONE = 2;

        private final WeakReference<View> mView;
        boolean mDirty = true;
        List<Rect> mRects = Collections.emptyList();

        ViewInfo(View view) {
            mView = new WeakReference<>(view);
        }

        public View getView() {
            return mView.get();
        }

        public int update() {
            final View view = getView();
            if (view == null || !view.isAttachedToWindow()
                    || !view.isAggregatedVisible()) return GONE;
            final List<Rect> localRects = getTrackedRectsForView(view);
            final List<Rect> newRects = new ArrayList<>(localRects.size());
            for (Rect src : localRects) {
                Rect mappedRect = new Rect(src);
                ViewParent p = view.getParent();
                if (p != null && p.getChildVisibleRect(view, mappedRect, null)) {
                    newRects.add(mappedRect);
                }
            }

            if (mRects.equals(localRects)) return UNCHANGED;
            mRects = newRects;
            return CHANGED;
        }
    }
}
