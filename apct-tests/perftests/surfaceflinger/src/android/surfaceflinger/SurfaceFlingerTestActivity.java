/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.surfaceflinger;

import static android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.util.concurrent.CountDownLatch;

/**
 * A simple activity used for testing, e.g. performance of activity switching, or as a base
 * container of testing view.
 */
public class SurfaceFlingerTestActivity extends Activity {
    public TestSurfaceView mTestSurfaceView;
    SurfaceControl mSurfaceControl;
    CountDownLatch mIsReady = new CountDownLatch(1);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decorView = getWindow().getDecorView();
        // Hide both the navigation bar and the status bar.
        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN;
        decorView.setSystemUiVisibility(uiOptions);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mTestSurfaceView = new TestSurfaceView(this);
        setContentView(mTestSurfaceView);
    }

    public SurfaceControl createChildSurfaceControl() {
        return mTestSurfaceView.getChildSurfaceControlHelper();
    }

    public int getBufferTransformHint() {
        return mTestSurfaceView.getRootSurfaceControl().getBufferTransformHint();
    }

    public class TestSurfaceView extends SurfaceView {
        public TestSurfaceView(Context context) {
            super(context);
            SurfaceHolder holder = getHolder();
            holder.addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    mIsReady.countDown();
                }
                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width,
                        int height) {}
                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                }
            });
        }

        public SurfaceControl getChildSurfaceControlHelper() {
            try {
                mIsReady.await();
            } catch (InterruptedException ignore) {
            }
            SurfaceHolder holder = getHolder();

            // check to see if surface is valid
            if (holder.getSurface().isValid()) {
                mSurfaceControl = getSurfaceControl();
                new SurfaceControl.Transaction()
                        .setFrameRate(mSurfaceControl, 1000, FRAME_RATE_COMPATIBILITY_DEFAULT)
                        .apply(true);
            }
            return new SurfaceControl.Builder()
                    .setName("ChildSurfaceControl")
                    .setParent(mSurfaceControl)
                    .build();
        }
    }
}
