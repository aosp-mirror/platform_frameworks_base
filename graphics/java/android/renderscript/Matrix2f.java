/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.renderscript;

import java.lang.Math;
import android.util.Log;


/**
 * @hide
 *
 **/
public class Matrix2f {

    public Matrix2f() {
        mMat = new float[4];
        loadIdentity();
    }

    public float get(int i, int j) {
        return mMat[i*2 + j];
    }

    public void set(int i, int j, float v) {
        mMat[i*2 + j] = v;
    }

    public void loadIdentity() {
        mMat[0] = 1;
        mMat[1] = 0;

        mMat[2] = 0;
        mMat[3] = 1;
    }

    public void load(Matrix2f src) {
        System.arraycopy(mMat, 0, src, 0, 4);
    }

    final float[] mMat;
}



