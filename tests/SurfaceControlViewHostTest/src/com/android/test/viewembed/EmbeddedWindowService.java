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

package com.android.test.viewembed;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import android.annotation.Nullable;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.Choreographer;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.window.InputTransferToken;

public class EmbeddedWindowService extends Service {
    private static final String TAG = "EmbeddedWindowService";
    private SurfaceControlViewHost mVr;

    private Handler mHandler;

    private IBinder mInputToken;
    private SurfaceControl mSurfaceControl;

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // Return the interface
        return new AttachEmbeddedWindow();
    }

    public static class SlowView extends TextView {

        public SlowView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
            }
        }
    }

    private class AttachEmbeddedWindow extends IAttachEmbeddedWindow.Stub {
        @Override
        public void attachEmbedded(IBinder hostToken, int width, int height,
                IAttachEmbeddedWindowCallback callback) {
            mHandler.post(() -> {
                Context context = EmbeddedWindowService.this;
                Display display = getApplicationContext().getSystemService(
                        DisplayManager.class).getDisplay(DEFAULT_DISPLAY);
                mVr = new SurfaceControlViewHost(context, display, hostToken);
                FrameLayout content = new FrameLayout(context);

                SlowView slowView = new SlowView(context);
                slowView.setText("INSIDE TEXT");
                slowView.setGravity(Gravity.CENTER);
                slowView.setTextColor(Color.BLACK);
                slowView.setBackgroundColor(Color.CYAN);
                content.addView(slowView);
                WindowManager.LayoutParams lp =
                        new WindowManager.LayoutParams(width, height, TYPE_APPLICATION,
                                0, PixelFormat.OPAQUE);
                lp.setTitle("EmbeddedWindow");

                mVr.setView(content, lp);
                try {
                    callback.onEmbeddedWindowAttached(mVr.getSurfacePackage());
                } catch (RemoteException e) {
                }
            });
        }

        @Override
        public void relayout(WindowManager.LayoutParams lp) {
            mHandler.post(() -> mVr.relayout(lp));
        }

        @Override
        public void attachEmbeddedSurfaceControl(SurfaceControl parentSc, int displayId,
                InputTransferToken inputTransferToken) {
            mHandler.post(() -> {
                Paint paint = new Paint();
                paint.setTextSize(40);
                paint.setColor(Color.WHITE);

                mSurfaceControl = new SurfaceControl.Builder().setName("Child SurfaceControl")
                        .setParent(parentSc).setBufferSize(500, 500).build();
                new SurfaceControl.Transaction().show(mSurfaceControl).apply();

                Surface surface = new Surface(mSurfaceControl);
                Canvas c = surface.lockCanvas(null);
                c.drawColor(Color.BLUE);
                c.drawText("Remote", 250, 250, paint);
                surface.unlockCanvasAndPost(c);
                WindowManager wm = getSystemService(WindowManager.class);
                wm.registerBatchedSurfaceControlInputReceiver(displayId, inputTransferToken,
                        mSurfaceControl,
                        Choreographer.getInstance(), event -> {
                            Log.d(TAG, "onInputEvent-remote " + event);
                            return false;
                        });

            });
        }

        @Override
        public void tearDownEmbeddedSurfaceControl() {
            if (mSurfaceControl != null) {
                WindowManager wm = getSystemService(WindowManager.class);
                wm.unregisterSurfaceControlInputReceiver(mSurfaceControl);
                new SurfaceControl.Transaction().remove(mSurfaceControl).apply();
            }
        }
    }
}
