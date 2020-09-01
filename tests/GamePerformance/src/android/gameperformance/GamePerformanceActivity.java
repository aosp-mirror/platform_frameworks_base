/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.gameperformance;

import java.util.concurrent.CountDownLatch;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.RelativeLayout;

/**
 * Minimal activity that holds different types of views.
 * call attachSurfaceView, attachOpenGLView or attachControlView to switch
 * the view.
 */
public class GamePerformanceActivity extends Activity {
    private CustomSurfaceView mSurfaceView = null;
    private CustomOpenGLView mOpenGLView = null;
    private CustomControlView mControlView = null;

    private RelativeLayout mRootLayout;

    private void detachAllViews() {
        if (mOpenGLView != null) {
            mRootLayout.removeView(mOpenGLView);
            mOpenGLView = null;
        }
        if (mSurfaceView != null) {
            mRootLayout.removeView(mSurfaceView);
            mSurfaceView = null;
        }
        if (mControlView != null) {
            mRootLayout.removeView(mControlView);
            mControlView = null;
        }
    }

    public void attachSurfaceView() throws InterruptedException {
        synchronized (mRootLayout) {
            if (mSurfaceView != null) {
                return;
            }
            final CountDownLatch latch = new CountDownLatch(1);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    detachAllViews();
                    mSurfaceView = new CustomSurfaceView(GamePerformanceActivity.this);
                    mRootLayout.addView(mSurfaceView);
                    latch.countDown();
                }
            });
            latch.await();
            mSurfaceView.waitForSurfaceReady();
        }
    }

    public void attachOpenGLView() throws InterruptedException {
        synchronized (mRootLayout) {
            if (mOpenGLView != null) {
                return;
            }
            final CountDownLatch latch = new CountDownLatch(1);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    detachAllViews();
                    mOpenGLView = new CustomOpenGLView(GamePerformanceActivity.this);
                    mRootLayout.addView(mOpenGLView);
                    latch.countDown();
                }
            });
            latch.await();
        }
    }

    public void attachControlView() throws InterruptedException {
        synchronized (mRootLayout) {
            if (mControlView != null) {
                return;
            }
            final CountDownLatch latch = new CountDownLatch(1);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    detachAllViews();
                    mControlView = new CustomControlView(GamePerformanceActivity.this);
                    mRootLayout.addView(mControlView);
                    latch.countDown();
                }
            });
            latch.await();
        }
    }


    public CustomOpenGLView getOpenGLView() {
        if (mOpenGLView == null) {
            throw new RuntimeException("OpenGL view is not attached");
        }
        return mOpenGLView;
    }

    public CustomControlView getControlView() {
        if (mControlView == null) {
            throw new RuntimeException("Control view is not attached");
        }
        return mControlView;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // To layouts in parent. First contains list of Surfaces and second
        // controls. Controls stay on top.
        mRootLayout = new RelativeLayout(this);
        mRootLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        Rect rect = new Rect();
        getWindow().getDecorView().getWindowVisibleDisplayFrame(rect);

        mOpenGLView =  new CustomOpenGLView(this);
        mRootLayout.addView(mOpenGLView);

        setContentView(mRootLayout);
    }

    public void resetFrameTimes() {
        if (mSurfaceView != null) {
            mSurfaceView.resetFrameTimes();
        } else if (mOpenGLView != null) {
            mOpenGLView.resetFrameTimes();
        } else if (mControlView != null) {
            mControlView.resetFrameTimes();
        } else {
            throw new IllegalStateException("Nothing attached");
        }
    }

    public double getFps() {
        if (mSurfaceView != null) {
            return mSurfaceView.getFps();
        } else if (mOpenGLView != null) {
            return mOpenGLView.getFps();
        } else if (mControlView != null) {
            return mControlView.getFps();
        } else {
            throw new IllegalStateException("Nothing attached");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }
}