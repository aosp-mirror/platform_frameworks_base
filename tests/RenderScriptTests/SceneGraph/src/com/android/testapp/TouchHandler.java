/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.testapp;

import android.util.Log;
import android.renderscript.Float3;
import com.android.scenegraph.*;
import com.android.scenegraph.CompoundTransform.RotateComponent;
import com.android.scenegraph.CompoundTransform.TranslateComponent;

public class TouchHandler {
    private static String TAG = "TouchHandler";

    float mLastX;
    float mLastY;

    RotateComponent mRotateX;
    float mRotateXValue;
    RotateComponent mRotateY;
    float mRotateYValue;
    TranslateComponent mDist;
    Float3 mDistValue;

    public void init(Scene scene) {
        CompoundTransform cameraRotate = (CompoundTransform)scene.getTransformByName("CameraAim");
        CompoundTransform cameraDist = (CompoundTransform)scene.getTransformByName("CameraDist");

        if (cameraRotate != null && cameraDist != null) {
            mRotateX = (RotateComponent)cameraRotate.mTransformComponents.get(2);
            mRotateXValue = mRotateX.getAngle();
            mRotateY = (RotateComponent)cameraRotate.mTransformComponents.get(1);
            mRotateYValue = mRotateY.getAngle();
            mDist = (TranslateComponent)cameraDist.mTransformComponents.get(0);
            mDistValue = mDist.getValue();
        }
    }

    public void onActionDown(float x, float y) {
        mLastX = x;
        mLastY = y;
    }

    public void onActionScale(float scale) {
        if (mDist == null) {
            return;
        }
        mDistValue.z *= 1.0f / scale;
        mDistValue.z = Math.max(20.0f, Math.min(mDistValue.z, 100.0f));
        mDist.setValue(mDistValue);
    }

    public void onActionMove(float x, float y) {
        if (mRotateX == null) {
            return;
        }

        float dx = mLastX - x;
        float dy = mLastY - y;

        if (Math.abs(dy) <= 2.0f) {
            dy = 0.0f;
        }
        if (Math.abs(dx) <= 2.0f) {
            dx = 0.0f;
        }

        mRotateYValue += dx * 0.25f;
        mRotateYValue %= 360.0f;

        mRotateXValue  += dy * 0.25f;
        mRotateXValue  = Math.max(mRotateXValue , -80.0f);
        mRotateXValue  = Math.min(mRotateXValue , 0.0f);

        mRotateX.setAngle(mRotateXValue);
        mRotateY.setAngle(mRotateYValue);

        mLastX = x;
        mLastY = y;
    }
}
