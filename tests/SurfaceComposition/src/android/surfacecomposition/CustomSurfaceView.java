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
package android.surfacecomposition;

import java.util.Random;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * This provides functionality to measure Surface update frame rate. The idea is to
 * constantly invalidates Surface in a separate thread. Lowest possible way is to
 * use SurfaceView which works with Surface. This gives a very small overhead
 * and very close to Android internals. Note, that lockCanvas is blocking
 * methods and it returns once SurfaceFlinger consumes previous buffer. This
 * gives the change to measure real performance of Surface compositor.
 */
public class CustomSurfaceView extends SurfaceView implements SurfaceHolder.Callback {
    private final static long DURATION_TO_WARMUP_MS = 50;
    private final static long DURATION_TO_MEASURE_ROUGH_MS = 500;
    private final static long DURATION_TO_MEASURE_PRECISE_MS = 3000;
    private final static Random mRandom = new Random();

    private final Object mSurfaceLock = new Object();
    private Surface mSurface;
    private boolean mDrawNameOnReady = true;
    private boolean mSurfaceWasChanged = false;
    private String mName;
    private Canvas mCanvas;

    class ValidateThread extends Thread {
        private double mFPS = 0.0f;
        // Used to support early exit and prevent long computation.
        private double mBadFPS;
        private double mPerfectFPS;

        ValidateThread(double badFPS, double perfectFPS) {
            mBadFPS = badFPS;
            mPerfectFPS = perfectFPS;
        }

        public void run() {
            long startTime = System.currentTimeMillis();
            while (System.currentTimeMillis() - startTime < DURATION_TO_WARMUP_MS) {
                invalidateSurface(false);
            }

            startTime = System.currentTimeMillis();
            long endTime;
            int frameCnt = 0;
            while (true) {
                invalidateSurface(false);
                endTime = System.currentTimeMillis();
                ++frameCnt;
                mFPS = (double)frameCnt * 1000.0 / (endTime - startTime);
                if ((endTime - startTime) >= DURATION_TO_MEASURE_ROUGH_MS) {
                    // Test if result looks too bad or perfect and stop early.
                    if (mFPS <= mBadFPS || mFPS >= mPerfectFPS) {
                        break;
                    }
                }
                if ((endTime - startTime) >= DURATION_TO_MEASURE_PRECISE_MS) {
                    break;
                }
            }
        }

        public double getFPS() {
            return mFPS;
        }
    }

    public CustomSurfaceView(Context context, String name) {
        super(context);
        mName = name;
        getHolder().addCallback(this);
    }

    public void setMode(int pixelFormat, boolean drawNameOnReady) {
        mDrawNameOnReady = drawNameOnReady;
        getHolder().setFormat(pixelFormat);
    }

    public void acquireCanvas() {
        synchronized (mSurfaceLock) {
            if (mCanvas != null) {
                throw new RuntimeException("Surface canvas was already acquired.");
            }
            if (mSurface != null) {
                mCanvas = mSurface.lockCanvas(null);
            }
        }
    }

    public void releaseCanvas() {
        synchronized (mSurfaceLock) {
            if (mCanvas != null) {
                if (mSurface == null) {
                    throw new RuntimeException(
                            "Surface was destroyed but canvas was not released.");
                }
                mSurface.unlockCanvasAndPost(mCanvas);
                mCanvas = null;
            }
        }
    }

    /**
     * Invalidate surface.
     */
    private void invalidateSurface(boolean drawSurfaceId) {
        synchronized (mSurfaceLock) {
            if (mSurface != null) {
                Canvas canvas = mSurface.lockCanvas(null);
                // Draw surface name for debug purpose only. This does not affect the test
                // because it is drawn only during allocation.
                if (drawSurfaceId) {
                    int textSize = canvas.getHeight() / 24;
                    Paint paint = new Paint();
                    paint.setTextSize(textSize);
                    int textWidth = (int)(paint.measureText(mName) + 0.5f);
                    int x = mRandom.nextInt(canvas.getWidth() - textWidth);
                    int y = textSize + mRandom.nextInt(canvas.getHeight() - textSize);
                    // Create effect of fog to visually control correctness of composition.
                    paint.setColor(0xFFFF8040);
                    canvas.drawARGB(32, 255, 255, 255);
                    canvas.drawText(mName, x, y, paint);
                }
                mSurface.unlockCanvasAndPost(canvas);
            }
        }
    }

    /**
     * Wait until surface is created and ready to use or return immediately if surface
     * already exists.
     */
    public void waitForSurfaceReady() {
        synchronized (mSurfaceLock) {
            if (mSurface == null) {
                try {
                    mSurfaceLock.wait(5000);
                } catch(InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (mSurface == null)
                throw new RuntimeException("Surface is not ready.");
            mSurfaceWasChanged = false;
        }
    }

    /**
     * Wait until surface is destroyed or return immediately if surface does not exist.
     */
    public void waitForSurfaceDestroyed() {
        synchronized (mSurfaceLock) {
            if (mSurface != null) {
                try {
                    mSurfaceLock.wait(5000);
                } catch(InterruptedException e) {
                    e.printStackTrace();
                }
            }
            if (mSurface != null)
                throw new RuntimeException("Surface still exists.");
            mSurfaceWasChanged = false;
        }
    }

    /**
     * Validate that surface has not been changed since waitForSurfaceReady or
     * waitForSurfaceDestroyed.
     */
    public void validateSurfaceNotChanged() {
        synchronized (mSurfaceLock) {
            if (mSurfaceWasChanged) {
                throw new RuntimeException("Surface was changed during the test execution.");
            }
        }
    }

    public double measureFPS(double badFPS, double perfectFPS) {
        try {
            ValidateThread validateThread = new ValidateThread(badFPS, perfectFPS);
            validateThread.start();
            validateThread.join();
            return validateThread.getFPS();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        synchronized (mSurfaceLock) {
            mSurfaceWasChanged = true;
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // This method is always called at least once, after surfaceCreated.
        synchronized (mSurfaceLock) {
            mSurface = holder.getSurface();
            // We only need to invalidate the surface for the compositor performance test so that
            // it gets included in the composition process. For allocation performance we
            // don't need to invalidate surface and this allows us to remove non-necessary
            // surface invalidation from the test.
            if (mDrawNameOnReady) {
                invalidateSurface(true);
            }
            mSurfaceWasChanged = true;
            mSurfaceLock.notify();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (mSurfaceLock) {
            mSurface = null;
            mSurfaceWasChanged = true;
            mSurfaceLock.notify();
        }
    }
}
