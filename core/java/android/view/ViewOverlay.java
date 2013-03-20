/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;

/**
 * ViewOverlay is a container that View uses to host all objects (views and drawables) that
 * are added to its "overlay", gotten through {@link View#getOverlay()}. Views and drawables are
 * added to the overlay via the add/remove methods in this class. These views and drawables are
 * drawn whenever the view itself is drawn; first the view draws its own content (and children,
 * if it is a ViewGroup), then it draws its overlay (if it has one).
 *
 * Besides managing and drawing the list of drawables, this class serves two purposes:
 * (1) it noops layout calls because children are absolutely positioned and
 * (2) it forwards all invalidation calls to its host view. The invalidation redirect is
 * necessary because the overlay is not a child of the host view and invalidation cannot
 * therefore follow the normal path up through the parent hierarchy.
 *
 * @hide
 */
class ViewOverlay extends ViewGroup implements Overlay {

    /**
     * The View for which this is an overlay. Invalidations of the overlay are redirected to
     * this host view.
     */
    View mHostView;

    /**
     * The set of drawables to draw when the overlay is rendered.
     */
    ArrayList<Drawable> mDrawables = null;

    ViewOverlay(Context context, View host) {
        super(context);
        mHostView = host;
        mParent = mHostView.getParent();
    }

    @Override
    public void add(Drawable drawable) {
        if (mDrawables == null) {
            mDrawables = new ArrayList<Drawable>();
        }
        if (!mDrawables.contains(drawable)) {
            // Make each drawable unique in the overlay; can't add it more than once
            mDrawables.add(drawable);
            invalidate(drawable.getBounds());
            drawable.setCallback(this);
        }
    }

    @Override
    public void remove(Drawable drawable) {
        if (mDrawables != null) {
            mDrawables.remove(drawable);
            invalidate(drawable.getBounds());
            drawable.setCallback(null);
        }
    }

    @Override
    public void invalidateDrawable(Drawable drawable) {
        invalidate(drawable.getBounds());
    }

    @Override
    public void add(View child) {
        super.addView(child);
    }

    @Override
    public void remove(View view) {
        super.removeView(view);
    }

    @Override
    public void clear() {
        removeAllViews();
        mDrawables.clear();
    }

    boolean isEmpty() {
        if (getChildCount() == 0 && (mDrawables == null || mDrawables.size() == 0)) {
            return true;
        }
        return false;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        final int numDrawables = (mDrawables == null) ? 0 : mDrawables.size();
        for (int i = 0; i < numDrawables; ++i) {
            mDrawables.get(i).draw(canvas);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        // Noop: children are positioned absolutely
    }

    /*
     The following invalidation overrides exist for the purpose of redirecting invalidation to
     the host view. The overlay is not parented to the host view (since a View cannot be a parent),
     so the invalidation cannot proceed through the normal parent hierarchy.
     There is a built-in assumption that the overlay exactly covers the host view, therefore
     the invalidation rectangles received do not need to be adjusted when forwarded to
     the host view.
     */

    @Override
    public void invalidate(Rect dirty) {
        super.invalidate(dirty);
        if (mHostView != null) {
            dirty.offset(getLeft(), getTop());
            mHostView.invalidate(dirty);
        }
    }

    @Override
    public void invalidate(int l, int t, int r, int b) {
        super.invalidate(l, t, r, b);
        if (mHostView != null) {
            mHostView.invalidate(l, t, r, b);
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (mHostView != null) {
            mHostView.invalidate();
        }
    }

    @Override
    void invalidate(boolean invalidateCache) {
        super.invalidate(invalidateCache);
        if (mHostView != null) {
            mHostView.invalidate(invalidateCache);
        }
    }

    @Override
    void invalidateViewProperty(boolean invalidateParent, boolean forceRedraw) {
        super.invalidateViewProperty(invalidateParent, forceRedraw);
        if (mHostView != null) {
            mHostView.invalidateViewProperty(invalidateParent, forceRedraw);
        }
    }

    @Override
    protected void invalidateParentCaches() {
        super.invalidateParentCaches();
        if (mHostView != null) {
            mHostView.invalidateParentCaches();
        }
    }

    @Override
    protected void invalidateParentIfNeeded() {
        super.invalidateParentIfNeeded();
        if (mHostView != null) {
            mHostView.invalidateParentIfNeeded();
        }
    }

    public void invalidateChildFast(View child, final Rect dirty) {
        if (mHostView != null) {
            // Note: This is not a "fast" invalidation. Would be nice to instead invalidate using DL
            // properties and a dirty rect instead of causing a real invalidation of the host view
            int left = child.mLeft;
            int top = child.mTop;
            if (!child.getMatrix().isIdentity()) {
                child.transformRect(dirty);
            }
            dirty.offset(left, top);
            mHostView.invalidate(dirty);
        }
    }

    @Override
    public ViewParent invalidateChildInParent(int[] location, Rect dirty) {
        if (mHostView != null) {
            mHostView.invalidate(dirty);
        }
        return null;
    }
}
