/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.view.animation;

import android.graphics.Rect;

/**
 * An animation that controls the clip of an object. See the
 * {@link android.view.animation full package} description for details and
 * sample code.
 *
 * @hide
 */
public class ClipRectAnimation extends Animation {
    protected Rect mFromRect = new Rect();
    protected Rect mToRect = new Rect();

    /**
     * Constructor to use when building a ClipRectAnimation from code
     *
     * @param fromClip the clip rect to animate from
     * @param toClip the clip rect to animate to
     */
    public ClipRectAnimation(Rect fromClip, Rect toClip) {
        if (fromClip == null || toClip == null) {
            throw new RuntimeException("Expected non-null animation clip rects");
        }
        mFromRect.set(fromClip);
        mToRect.set(toClip);
    }

    /**
     * Constructor to use when building a ClipRectAnimation from code
     */
    public ClipRectAnimation(int fromL, int fromT, int fromR, int fromB,
            int toL, int toT, int toR, int toB) {
        mFromRect.set(fromL, fromT, fromR, fromB);
        mToRect.set(toL, toT, toR, toB);
    }

    @Override
    protected void applyTransformation(float it, Transformation tr) {
        int l = mFromRect.left + (int) ((mToRect.left - mFromRect.left) * it);
        int t = mFromRect.top + (int) ((mToRect.top - mFromRect.top) * it);
        int r = mFromRect.right + (int) ((mToRect.right - mFromRect.right) * it);
        int b = mFromRect.bottom + (int) ((mToRect.bottom - mFromRect.bottom) * it);
        tr.setClipRect(l, t, r, b);
    }

    @Override
    public boolean willChangeTransformationMatrix() {
        return false;
    }
}
