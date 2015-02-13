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

import android.graphics.Matrix;

/**
 * Special case of TranslateAnimation that translates only horizontally, picking up the
 * vertical values from whatever is set on the Transformation already. When used in
 * conjunction with a TranslateYAnimation, allows independent animation of x and y
 * position.
 * @hide
 */
public class TranslateXAnimation extends TranslateAnimation {
    float[] mTmpValues = new float[9];

    /**
     * Constructor. Passes in 0 for the y parameters of TranslateAnimation
     */
    public TranslateXAnimation(float fromXDelta, float toXDelta) {
        super(fromXDelta, toXDelta, 0, 0);
    }

    /**
     * Constructor. Passes in 0 for the y parameters of TranslateAnimation
     */
    public TranslateXAnimation(int fromXType, float fromXValue, int toXType, float toXValue) {
        super(fromXType, fromXValue, toXType, toXValue, ABSOLUTE, 0, ABSOLUTE, 0);
    }

    /**
     * Calculates and sets x translation values on given transformation.
     */
    @Override
    protected void applyTransformation(float interpolatedTime, Transformation t) {
        Matrix m = t.getMatrix();
        m.getValues(mTmpValues);
        float dx = mFromXDelta + ((mToXDelta - mFromXDelta) * interpolatedTime);
        t.getMatrix().setTranslate(dx, mTmpValues[Matrix.MTRANS_Y]);
    }
}
