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
 * Special case of ClipRectAnimation that animates only the left/right
 * dimensions of the clip, picking up the other dimensions from whatever is
 * set on the transform already.
 *
 * @hide
 */
public class ClipRectLRAnimation extends ClipRectAnimation {

    /**
     * Constructor. Passes in 0 for Top/Bottom parameters of ClipRectAnimation
     */
    public ClipRectLRAnimation(int fromL, int fromR, int toL, int toR) {
        super(fromL, 0, fromR, 0, toL, 0, toR, 0);
    }

    /**
     * Calculates and sets clip rect on given transformation. It uses existing values
     * on the Transformation for Top/Bottom clip parameters.
     */
    @Override
    protected void applyTransformation(float it, Transformation tr) {
        Rect oldClipRect = tr.getClipRect();
        tr.setClipRect(mFromRect.left + (int) ((mToRect.left - mFromRect.left) * it),
                oldClipRect.top,
                mFromRect.right + (int) ((mToRect.right - mFromRect.right) * it),
                oldClipRect.bottom);
    }
}
