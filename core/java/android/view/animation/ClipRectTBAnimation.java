/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * Special case of ClipRectAnimation that animates only the top/bottom
 * dimensions of the clip, picking up the other dimensions from whatever is
 * set on the transform already.
 *
 * @hide
 */
public class ClipRectTBAnimation extends ClipRectAnimation {

    /**
     * Constructor. Passes in 0 for Left/Right parameters of ClipRectAnimation
     */
    public ClipRectTBAnimation(int fromT, int fromB, int toT, int toB) {
        super(0, fromT, 0, fromB, 0, toT, 0, toB);
    }

    /**
     * Calculates and sets clip rect on given transformation. It uses existing values
     * on the Transformation for Left/Right clip parameters.
     */
    @Override
    protected void applyTransformation(float it, Transformation tr) {
        Rect oldClipRect = tr.getClipRect();
        tr.setClipRect(oldClipRect.left, mFromRect.top + (int) ((mToRect.top - mFromRect.top) * it),
                oldClipRect.right,
                mFromRect.bottom + (int) ((mToRect.bottom - mFromRect.bottom) * it));
    }

}
