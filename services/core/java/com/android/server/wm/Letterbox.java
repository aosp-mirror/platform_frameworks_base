/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.view.SurfaceControl.HIDDEN;

import android.graphics.Point;
import android.graphics.Rect;
import android.view.SurfaceControl;

import java.util.function.Supplier;

/**
 * Manages a set of {@link SurfaceControl}s to draw a black letterbox between an
 * outer rect and an inner rect.
 */
public class Letterbox {

    private static final Rect EMPTY_RECT = new Rect();
    private static final Point ZERO_POINT = new Point(0, 0);

    private final Supplier<SurfaceControl.Builder> mFactory;
    private final Rect mOuter = new Rect();
    private final Rect mInner = new Rect();
    private final LetterboxSurface mTop = new LetterboxSurface("top");
    private final LetterboxSurface mLeft = new LetterboxSurface("left");
    private final LetterboxSurface mBottom = new LetterboxSurface("bottom");
    private final LetterboxSurface mRight = new LetterboxSurface("right");

    /**
     * Constructs a Letterbox.
     *
     * @param surfaceControlFactory a factory for creating the managed {@link SurfaceControl}s
     */
    public Letterbox(Supplier<SurfaceControl.Builder> surfaceControlFactory) {
        mFactory = surfaceControlFactory;
    }

    /**
     * Lays out the letterbox, such that the area between the outer and inner
     * frames will be covered by black color surfaces.
     *
     * The caller must use {@link #applySurfaceChanges} to apply the new layout to the surface.
     * @param outer the outer frame of the letterbox (this frame will be black, except the area
     *              that intersects with the {code inner} frame), in global coordinates
     * @param inner the inner frame of the letterbox (this frame will be clear), in global
     *              coordinates
     * @param surfaceOrigin the origin of the surface factory in global coordinates
     */
    public void layout(Rect outer, Rect inner, Point surfaceOrigin) {
        mOuter.set(outer);
        mInner.set(inner);

        mTop.layout(outer.left, outer.top, inner.right, inner.top, surfaceOrigin);
        mLeft.layout(outer.left, inner.top, inner.left, outer.bottom, surfaceOrigin);
        mBottom.layout(inner.left, inner.bottom, outer.right, outer.bottom, surfaceOrigin);
        mRight.layout(inner.right, outer.top, outer.right, inner.bottom, surfaceOrigin);
    }


    /**
     * Gets the insets between the outer and inner rects.
     */
    public Rect getInsets() {
        return new Rect(
                mLeft.getWidth(),
                mTop.getHeight(),
                mRight.getWidth(),
                mBottom.getHeight());
    }

    /**
     * Returns true if any part of the letterbox overlaps with the given {@code rect}.
     */
    public boolean isOverlappingWith(Rect rect) {
        return mTop.isOverlappingWith(rect) || mLeft.isOverlappingWith(rect)
                || mBottom.isOverlappingWith(rect) || mRight.isOverlappingWith(rect);
    }

    /**
     * Hides the letterbox.
     *
     * The caller must use {@link #applySurfaceChanges} to apply the new layout to the surface.
     */
    public void hide() {
        layout(EMPTY_RECT, EMPTY_RECT, ZERO_POINT);
    }

    /**
     * Destroys the managed {@link SurfaceControl}s.
     */
    public void destroy() {
        mOuter.setEmpty();
        mInner.setEmpty();

        mTop.destroy();
        mLeft.destroy();
        mBottom.destroy();
        mRight.destroy();
    }

    /** Returns whether a call to {@link #applySurfaceChanges} would change the surface. */
    public boolean needsApplySurfaceChanges() {
        return mTop.needsApplySurfaceChanges()
                || mLeft.needsApplySurfaceChanges()
                || mBottom.needsApplySurfaceChanges()
                || mRight.needsApplySurfaceChanges();
    }

    public void applySurfaceChanges(SurfaceControl.Transaction t) {
        mTop.applySurfaceChanges(t);
        mLeft.applySurfaceChanges(t);
        mBottom.applySurfaceChanges(t);
        mRight.applySurfaceChanges(t);
    }

    private class LetterboxSurface {

        private final String mType;
        private SurfaceControl mSurface;

        private final Rect mSurfaceFrameRelative = new Rect();
        private final Rect mLayoutFrameGlobal = new Rect();
        private final Rect mLayoutFrameRelative = new Rect();

        public LetterboxSurface(String type) {
            mType = type;
        }

        public void layout(int left, int top, int right, int bottom, Point surfaceOrigin) {
            mLayoutFrameGlobal.set(left, top, right, bottom);
            mLayoutFrameRelative.set(mLayoutFrameGlobal);
            mLayoutFrameRelative.offset(-surfaceOrigin.x, -surfaceOrigin.y);
        }

        private void createSurface() {
            mSurface = mFactory.get().setName("Letterbox - " + mType)
                    .setFlags(HIDDEN).setColorLayer(true).build();
            mSurface.setLayer(-1);
            mSurface.setColor(new float[]{0, 0, 0});
        }

        public void destroy() {
            if (mSurface != null) {
                mSurface.destroy();
                mSurface = null;
            }
        }

        public int getWidth() {
            return Math.max(0, mLayoutFrameGlobal.width());
        }

        public int getHeight() {
            return Math.max(0, mLayoutFrameGlobal.height());
        }

        /**
         * Returns if the given {@code rect} overlaps with this letterbox piece.
         * @param rect the area to check for overlap in global coordinates
         */
        public boolean isOverlappingWith(Rect rect) {
            if (mLayoutFrameGlobal.isEmpty()) {
                return false;
            }
            return Rect.intersects(rect, mLayoutFrameGlobal);
        }

        public void applySurfaceChanges(SurfaceControl.Transaction t) {
            if (mSurfaceFrameRelative.equals(mLayoutFrameRelative)) {
                // Nothing changed.
                return;
            }
            mSurfaceFrameRelative.set(mLayoutFrameRelative);
            if (!mSurfaceFrameRelative.isEmpty()) {
                if (mSurface == null) {
                    createSurface();
                }
                t.setPosition(mSurface, mSurfaceFrameRelative.left, mSurfaceFrameRelative.top);
                t.setWindowCrop(mSurface, mSurfaceFrameRelative.width(),
                        mSurfaceFrameRelative.height());
                t.show(mSurface);
            } else if (mSurface != null) {
                t.hide(mSurface);
            }
        }

        public boolean needsApplySurfaceChanges() {
            return !mSurfaceFrameRelative.equals(mLayoutFrameRelative);
        }
    }
}
