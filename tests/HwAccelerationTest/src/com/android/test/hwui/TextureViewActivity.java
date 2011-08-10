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

package com.android.test.hwui;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

import java.io.IOException;

@SuppressWarnings({"UnusedDeclaration"})
public class TextureViewActivity extends Activity implements TextureView.SurfaceTextureListener {
    private Camera mCamera;
    private TextureView mTextureView;
    private FrameLayout mContent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mContent = new FrameLayout(this);

        mTextureView = new TextureView(this);
        mTextureView.setSurfaceTextureListener(this);

        Button button = new Button(this);
        button.setText("Remove/Add");
        button.setOnClickListener(new View.OnClickListener() {
            private boolean mAdded = true;

            @Override
            public void onClick(View v) {
                if (mAdded) {
                    mContent.removeView(mTextureView);
                } else {
                    mContent.addView(mTextureView);
                }
                mAdded = !mAdded;
            }
        });

        mContent.addView(mTextureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        mContent.addView(button, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
        setContentView(mContent);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mCamera = Camera.open();
        Camera.Size previewSize = mCamera.getParameters().getPreviewSize();
        mTextureView.setLayoutParams(new FrameLayout.LayoutParams(
                previewSize.width, previewSize.height, Gravity.CENTER));

        try {
            mCamera.setPreviewTexture(surface);
        } catch (IOException t) {
            android.util.Log.e("TextureView", "Cannot set preview texture target!", t);
        }

        mCamera.startPreview();

        mTextureView.setCameraDistance(5000);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // Ignored, the Camera does all the work for us
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        mCamera.stopPreview();
        mCamera.release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Ignored
    }
}
