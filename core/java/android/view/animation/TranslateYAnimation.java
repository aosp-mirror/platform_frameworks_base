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

import android.compat.annotation.UnsupportedAppUsage;
import android.graphics.Matrix;

/**
 * Special case of TranslateAnimation that translates only vertically, picking up the
 * horizontal values from whatever is set on the Transformation already. When used in
 * conjunction with a TranslateXAnimation, allows independent animation of x and y
 * position.
 * @hide
 */
public class TranslateYAnimation extends TranslateAnimation {
    float[] mTmpValues = new float[9];

    /**
     * Constructor. Passes in 0 for the x parameters of TranslateAnimation
     */
    public TranslateYAnimation(float fromYDelta, float toYDelta) {
        super(0, 0, fromYDelta, toYDelta);
    }

    /**
     * Constructor. Passes in 0 for the x parameters of TranslateAnimation
     */
    @UnsupportedAppUsage
    public TranslateYAnimation(int fromYType, float fromYValue, int toYType, float toYValue) {
        super(ABSOLUTE, 0, ABSOLUTE, 0, fromYType, fromYValue, toYType, toYValue);
    }

    /**
     * Calculates and sets y translation values on given transformation.
     */
    @Override
    protected void applyTransformation(float interpolatedTime, Transformation t) {
        Matrix m = t.getMatrix();
        m.getValues(mTmpValues);
        float dy = mFromYDelta + ((mToYDelta - mFromYDelta) * interpolatedTime);
        t.getMatrix().setTranslate(mTmpValues[Matrix.MTRANS_X], dy);
    }
}
