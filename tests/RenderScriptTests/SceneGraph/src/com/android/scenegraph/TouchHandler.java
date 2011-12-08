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

package com.android.scenegraph;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.renderscript.*;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.Element.Builder;
import android.renderscript.Font.Style;
import android.renderscript.Program.TextureType;
import android.renderscript.ProgramStore.DepthFunc;
import android.util.Log;

import com.android.scenegraph.SceneManager.SceneLoadedCallback;

public class TouchHandler {

    private static String TAG = "TouchHandler";

    public TouchHandler() {
    }

    public void init(Scene scene) {
        mCameraRotate = (CompoundTransform)scene.getTransformByName("CameraAim");
        mCameraDist = (CompoundTransform)scene.getTransformByName("CameraDist");

        if (mCameraRotate != null && mCameraDist != null) {
            mRotateX = mCameraRotate.mTransformComponents.get(2);
            mRotateY = mCameraRotate.mTransformComponents.get(1);
            mDist = mCameraDist.mTransformComponents.get(0);
        }
    }


    private Resources mRes;
    private RenderScriptGL mRS;

    float mLastX;
    float mLastY;

    CompoundTransform mCameraRotate;
    CompoundTransform mCameraDist;

    CompoundTransform.Component mRotateX;
    CompoundTransform.Component mRotateY;
    CompoundTransform.Component mDist;

    public void onActionDown(float x, float y) {
        mLastX = x;
        mLastY = y;
    }

    public void onActionScale(float scale) {
        if (mCameraDist == null) {
            return;
        }
        mDist.mValue.z *= 1.0f / scale;
        mDist.mValue.z = Math.max(20.0f, Math.min(mDist.mValue.z, 100.0f));
        mCameraDist.updateRSData();
    }

    public void onActionMove(float x, float y) {
        if (mCameraRotate == null) {
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

        mRotateY.mValue.w += dx*0.25;
        if (mRotateY.mValue.w > 360) {
            mRotateY.mValue.w -= 360;
        }
        if (mRotateY.mValue.w < 0) {
            mRotateY.mValue.w += 360;
        }

        mRotateX.mValue.w += dy*0.25;
        mRotateX.mValue.w = Math.max(mRotateX.mValue.w, -80.0f);
        mRotateX.mValue.w = Math.min(mRotateX.mValue.w, 0.0f);

        mLastX = x;
        mLastY = y;

        mCameraRotate.updateRSData();
    }
}
