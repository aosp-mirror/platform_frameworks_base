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

    float mRotateXValue;
    float mRotateYValue;
    Float3 mDistValue;
    Float3 mPosValue;

    CompoundTransform mCameraRig;
    RotateComponent mRotateX;
    RotateComponent mRotateY;
    TranslateComponent mDist;
    TranslateComponent mPosition;
    Camera mCamera;

    public void init(Scene scene) {
        // Some initial values for camera position
        mRotateXValue = -20;
        mRotateYValue = 45;
        mDistValue = new Float3(0, 0, 45);
        mPosValue = new Float3(0, 4, 0);

        mRotateX = new RotateComponent("RotateX", new Float3(1, 0, 0), mRotateXValue);
        mRotateY = new RotateComponent("RotateY", new Float3(0, 1, 0), mRotateYValue);
        mDist = new TranslateComponent("Distance", mDistValue);
        mPosition = new TranslateComponent("Distance", mPosValue);

        // Make a camera transform we can manipulate
        mCameraRig = new CompoundTransform();
        mCameraRig.setName("CameraRig");
        mCameraRig.addComponent(mPosition);
        mCameraRig.addComponent(mRotateY);
        mCameraRig.addComponent(mRotateX);
        mCameraRig.addComponent(mDist);
        scene.appendTransform(mCameraRig);
        mCamera = new Camera();
        mCamera.setTransform(mCameraRig);
        scene.appendCamera(mCamera);
    }

    public Camera getCamera() {
        return mCamera;
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
        mDistValue.z = Math.max(10.0f, Math.min(mDistValue.z, 150.0f));
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
