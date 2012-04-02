/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.example.android.rs.sto;

import android.app.Activity;
import android.os.Bundle;
import android.provider.Settings.System;
import android.util.Log;
import android.view.View;
import android.graphics.SurfaceTexture;

import java.lang.Runtime;

public class SurfaceTextureOpaque extends Activity {
    private SurfaceTextureOpaqueView mView;
    CameraCapture mCC;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mView = new SurfaceTextureOpaqueView(this);
        setContentView(mView);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mView.resume();
        startCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mView.pause();
        mCC.endCapture();
    }

    cfl mCFL;
    public void startCamera() {
        mCC = new CameraCapture();
        mCFL = new cfl();

        mCC.setCameraFrameListener(mCFL);

        mCC.beginCapture(1, 640, 480, mView.getST());
    }

    public class cfl implements CameraCapture.CameraFrameListener {
        public void onNewCameraFrame() {
            mView.mRender.newFrame();
        }
    }

}

