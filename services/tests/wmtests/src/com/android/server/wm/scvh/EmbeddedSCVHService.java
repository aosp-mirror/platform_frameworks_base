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

package com.android.server.wm.scvh;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import android.annotation.Nullable;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.view.Display;
import android.view.SurfaceControl.Transaction;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

public class EmbeddedSCVHService extends Service {
    private static final String TAG = "SCVHEmbeddedService";
    private SurfaceControlViewHost mVr;

    private Handler mHandler;

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

    public static class SlowView extends View {
        private long mDelayMs;
        public SlowView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            try {
                Thread.sleep(mDelayMs);
            } catch (InterruptedException e) {
            }
        }

        public void setDelay(long delayMs) {
            mDelayMs = delayMs;
        }
    }

    private class AttachEmbeddedWindow extends IAttachEmbeddedWindow.Stub {
        @Override
        public void attachEmbedded(IBinder hostToken, int width,
                int height, int displayId, long delayMs, IAttachEmbeddedWindowCallback callback) {
            mHandler.post(() -> {
                Context context = EmbeddedSCVHService.this;
                Display display = getApplicationContext().getSystemService(
                        DisplayManager.class).getDisplay(displayId);
                mVr = new SurfaceControlViewHost(context, display, hostToken);
                FrameLayout content = new FrameLayout(context);

                SlowView slowView = new SlowView(context);
                slowView.setDelay(delayMs);
                slowView.setBackgroundColor(Color.BLUE);
                content.addView(slowView);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
                        TYPE_APPLICATION, 0, PixelFormat.OPAQUE);
                lp.setTitle("EmbeddedWindow");
                mVr.setView(content, lp);

                content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(@NonNull View v) {
                        // First frame isn't included in the sync so don't notify the host about the
                        // surface package until the first draw has completed.
                        Transaction transaction = new Transaction().addTransactionCommittedListener(
                                getMainExecutor(), () -> {
                                    try {
                                        callback.onEmbeddedWindowAttached(mVr.getSurfacePackage());
                                    } catch (RemoteException e) {
                                    }
                                });
                        v.getRootSurfaceControl().applyTransactionOnDraw(transaction);
                    }

                    @Override
                    public void onViewDetachedFromWindow(@NonNull View v) {
                    }
                });
            });
        }

        @Override
        public void relayout(WindowManager.LayoutParams lp) {
            mHandler.post(() -> mVr.relayout(lp));
        }
    }
}
