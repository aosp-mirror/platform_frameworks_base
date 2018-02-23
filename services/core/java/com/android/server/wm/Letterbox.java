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

import android.graphics.Rect;
import android.view.SurfaceControl;

import java.util.function.Supplier;

/**
 * Manages a set of {@link SurfaceControl}s to draw a black letterbox between an
 * outer rect and an inner rect.
 */
public class Letterbox {

    private static final Rect EMPTY_RECT = new Rect();

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
     * Sets the dimensions of the the letterbox, such that the area between the outer and inner
     * frames will be covered by black color surfaces.
     *
     * @param t     a transaction in which to set the dimensions
     * @param outer the outer frame of the letterbox (this frame will be black, except the area
     *              that intersects with the {code inner} frame).
     * @param inner the inner frame of the letterbox (this frame will be clear)
     */
    public void setDimensions(SurfaceControl.Transaction t, Rect outer, Rect inner) {
        mOuter.set(outer);
        mInner.set(inner);

        mTop.setRect(t, outer.left, outer.top, inner.right, inner.top);
        mLeft.setRect(t, outer.left, inner.top, inner.left, outer.bottom);
        mBottom.setRect(t, inner.left, inner.bottom, outer.right, outer.bottom);
        mRight.setRect(t, inner.right, outer.top, outer.right, inner.bottom);
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
     * Hides the letterbox.
     *
     * @param t a transaction in which to hide the letterbox
     */
    public void hide(SurfaceControl.Transaction t) {
        setDimensions(t, EMPTY_RECT, EMPTY_RECT);
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

    private class LetterboxSurface {

        private final String mType;
        private SurfaceControl mSurface;

        private int mLastLeft = 0;
        private int mLastTop = 0;
        private int mLastRight = 0;
        private int mLastBottom = 0;

        public LetterboxSurface(String type) {
            mType = type;
        }

        public void setRect(SurfaceControl.Transaction t,
                int left, int top, int right, int bottom) {
            if (mLastLeft == left && mLastTop == top
                    && mLastRight == right && mLastBottom == bottom) {
                // Nothing changed.
                return;
            }

            if (left < right && top < bottom) {
                if (mSurface == null) {
                    createSurface();
                }
                t.setPosition(mSurface, left, top);
                t.setSize(mSurface, right - left, bottom - top);
                t.show(mSurface);
            } else if (mSurface != null) {
                t.hide(mSurface);
            }

            mLastLeft = left;
            mLastTop = top;
            mLastRight = right;
            mLastBottom = bottom;
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
            return Math.max(0, mLastRight - mLastLeft);
        }

        public int getHeight() {
            return Math.max(0, mLastBottom - mLastTop);
        }
    }
}
