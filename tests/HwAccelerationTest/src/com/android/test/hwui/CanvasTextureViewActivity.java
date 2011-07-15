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
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.view.Gravity;
import android.view.TextureView;
import android.widget.FrameLayout;

@SuppressWarnings({"UnusedDeclaration"})
public class CanvasTextureViewActivity extends Activity
        implements TextureView.SurfaceTextureListener {
    private TextureView mTextureView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout content = new FrameLayout(this);

        mTextureView = new TextureView(this);
        mTextureView.setSurfaceTextureListener(this);

        content.addView(mTextureView, new FrameLayout.LayoutParams(500, 500, Gravity.CENTER));
        setContentView(content);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        Canvas c = mTextureView.lockCanvas();
        try {
            c.drawColor(0xff00ff00);
        } finally {
            mTextureView.unlockCanvasAndPost(c);
        }

        c = mTextureView.lockCanvas(new Rect(100, 100, 200, 200));
        try {
            c.drawColor(0xff0000ff);
        } finally {
            mTextureView.unlockCanvasAndPost(c);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // Ignored
    }

    @Override
    public void onSurfaceTextureDestroyed(SurfaceTexture surface) {
        // Ignored
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // Ignored
    }
}
