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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;

import com.android.internal.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Used by {@link ViewRootImpl} to track system gesture exclusion rects reported by views.
 */
class GestureExclusionTracker {
    private boolean mGestureExclusionViewsChanged = false;
    private boolean mRootGestureExclusionRectsChanged = false;
    private List<Rect> mRootGestureExclusionRects = Collections.emptyList();
    private List<GestureExclusionViewInfo> mGestureExclusionViewInfos = new ArrayList<>();
    private List<Rect> mGestureExclusionRects = Collections.emptyList();

    public void updateRectsForView(@NonNull View view) {
        boolean found = false;
        final Iterator<GestureExclusionViewInfo> i = mGestureExclusionViewInfos.iterator();
        while (i.hasNext()) {
            final GestureExclusionViewInfo info = i.next();
            final View v = info.getView();
            if (v == null || !v.isAttachedToWindow()) {
                mGestureExclusionViewsChanged = true;
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
            mGestureExclusionViewInfos.add(new GestureExclusionViewInfo(view));
            mGestureExclusionViewsChanged = true;
        }
    }

    @Nullable
    public List<Rect> computeChangedRects() {
        boolean changed = mRootGestureExclusionRectsChanged;
        final Iterator<GestureExclusionViewInfo> i = mGestureExclusionViewInfos.iterator();
        final List<Rect> rects = new ArrayList<>(mRootGestureExclusionRects);
        while (i.hasNext()) {
            final GestureExclusionViewInfo info = i.next();
            switch (info.update()) {
                case GestureExclusionViewInfo.CHANGED:
                    changed = true;
                    // Deliberate fall-through
                case GestureExclusionViewInfo.UNCHANGED:
                    rects.addAll(info.mExclusionRects);
                    break;
                case GestureExclusionViewInfo.GONE:
                    mGestureExclusionViewsChanged = true;
                    i.remove();
                    break;
            }
        }
        if (changed || mGestureExclusionViewsChanged) {
            mGestureExclusionViewsChanged = false;
            mRootGestureExclusionRectsChanged = false;
            if (!mGestureExclusionRects.equals(rects)) {
                mGestureExclusionRects = rects;
                return rects;
            }
        }
        return null;
    }

    public void setRootSystemGestureExclusionRects(@NonNull List<Rect> rects) {
        Preconditions.checkNotNull(rects, "rects must not be null");
        mRootGestureExclusionRects = rects;
        mRootGestureExclusionRectsChanged = true;
    }

    @NonNull
    public List<Rect> getRootSystemGestureExclusionRects() {
        return mRootGestureExclusionRects;
    }

    private static class GestureExclusionViewInfo {
        public static final int CHANGED = 0;
        public static final int UNCHANGED = 1;
        public static final int GONE = 2;

        private final WeakReference<View> mView;
        boolean mDirty = true;
        List<Rect> mExclusionRects = Collections.emptyList();

        GestureExclusionViewInfo(View view) {
            mView = new WeakReference<>(view);
        }

        public View getView() {
            return mView.get();
        }

        public int update() {
            final View excludedView = getView();
            if (excludedView == null || !excludedView.isAttachedToWindow()) return GONE;
            final List<Rect> localRects = excludedView.getSystemGestureExclusionRects();
            final List<Rect> newRects = new ArrayList<>(localRects.size());
            for (Rect src : localRects) {
                Rect mappedRect = new Rect(src);
                ViewParent p = excludedView.getParent();
                if (p != null && p.getChildVisibleRect(excludedView, mappedRect, null)) {
                    newRects.add(mappedRect);
                }
            }

            if (mExclusionRects.equals(localRects)) return UNCHANGED;
            mExclusionRects = newRects;
            return CHANGED;
        }
    }
}
