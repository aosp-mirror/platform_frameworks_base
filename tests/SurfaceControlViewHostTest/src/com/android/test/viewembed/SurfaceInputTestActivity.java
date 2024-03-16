/*
 * Copyright 2024 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.AttachedSurfaceControl;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.LinearLayout;

/**
 * Used to manually test that {@link android.view.SurfaceControlInputReceiver} API works.
 */
public class SurfaceInputTestActivity extends Activity {

    private static final String TAG = "SurfaceInputTestActivity";
    private SurfaceView mLocalSurfaceView;
    private SurfaceView mRemoteSurfaceView;
    private IAttachEmbeddedWindow mIAttachEmbeddedWindow;
    private SurfaceControl mParentSurfaceControl;

    private SurfaceControl mLocalSurfaceControl;

    private final ServiceConnection mConnection = new ServiceConnection() {
        // Called when the connection with the service is established
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(TAG, "Service Connected");
            mIAttachEmbeddedWindow = IAttachEmbeddedWindow.Stub.asInterface(service);
            loadEmbedded();
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "Service Disconnected");
            mIAttachEmbeddedWindow = null;
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewTreeObserver viewTreeObserver = getWindow().getDecorView().getViewTreeObserver();
        viewTreeObserver.addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        addLocalChildSurfaceControl(getWindow().getRootSurfaceControl());
                        viewTreeObserver.removeOnPreDrawListener(this);
                        return true;
                    }
                });
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout content = new LinearLayout(this);
        mLocalSurfaceView = new SurfaceView(this);
        content.addView(mLocalSurfaceView, new LinearLayout.LayoutParams(
                500, 500, Gravity.CENTER_HORIZONTAL | Gravity.TOP));

        mRemoteSurfaceView = new SurfaceView(this);
        content.addView(mRemoteSurfaceView, new LinearLayout.LayoutParams(
                500, 500, Gravity.CENTER_HORIZONTAL | Gravity.TOP));

        setContentView(content);

        mLocalSurfaceView.setZOrderOnTop(true);
        mLocalSurfaceView.getHolder().addCallback(mLocalSurfaceViewCallback);

        mRemoteSurfaceView.setZOrderOnTop(true);
        mRemoteSurfaceView.getHolder().addCallback(mRemoteSurfaceViewHolder);

        Intent intent = new Intent(this, EmbeddedWindowService.class);
        intent.setAction(IAttachEmbeddedWindow.class.getName());
        Log.d(TAG, "bindService");
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mLocalSurfaceControl != null) {
            getWindowManager().unregisterSurfaceControlInputReceiver(mLocalSurfaceControl);
            new SurfaceControl.Transaction().remove(mLocalSurfaceControl).apply();
        }
    }

    private void addLocalChildSurfaceControl(AttachedSurfaceControl attachedSurfaceControl) {
        mLocalSurfaceControl = new SurfaceControl.Builder().setName("LocalSC")
                .setBufferSize(100, 100).build();
        attachedSurfaceControl.buildReparentTransaction(mLocalSurfaceControl)
                .setVisibility(mLocalSurfaceControl, true)
                .setCrop(mLocalSurfaceControl, new Rect(0, 0, 100, 100))
                .setPosition(mLocalSurfaceControl, 250, 1000)
                .setLayer(mLocalSurfaceControl, 1).apply();

        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setTextSize(20);

        Surface surface = new Surface(mLocalSurfaceControl);
        Canvas c = surface.lockCanvas(null);
        c.drawColor(Color.GREEN);
        c.drawText("Local SC", 0, 0, paint);
        surface.unlockCanvasAndPost(c);
        WindowManager wm = getSystemService(WindowManager.class);
        wm.registerBatchedSurfaceControlInputReceiver(
                attachedSurfaceControl.getInputTransferToken(), mLocalSurfaceControl,
                Choreographer.getInstance(), event -> {
                    Log.d(TAG, "onInputEvent-sc " + event);
                    return false;
                });
    }

    private final SurfaceHolder.Callback mLocalSurfaceViewCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            Paint paint = new Paint();
            paint.setColor(Color.WHITE);
            paint.setTextSize(40);

            Canvas c = holder.lockCanvas();
            c.drawColor(Color.RED);
            c.drawText("Local", 250, 250, paint);
            holder.unlockCanvasAndPost(c);

            WindowManager wm = getSystemService(WindowManager.class);
            wm.registerBatchedSurfaceControlInputReceiver(
                    mLocalSurfaceView.getRootSurfaceControl().getInputTransferToken(),
                    mLocalSurfaceView.getSurfaceControl(),
                    Choreographer.getInstance(), event -> {
                        Log.d(TAG, "onInputEvent-local " + event);
                        return false;
                    });
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width,
                int height) {

        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            getWindowManager().unregisterSurfaceControlInputReceiver(
                    mLocalSurfaceView.getSurfaceControl());
        }
    };

    private final SurfaceHolder.Callback mRemoteSurfaceViewHolder = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            mParentSurfaceControl = mRemoteSurfaceView.getSurfaceControl();
            loadEmbedded();
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width,
                int height) {
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            if (mIAttachEmbeddedWindow != null) {
                try {
                    mIAttachEmbeddedWindow.tearDownEmbeddedSurfaceControl();
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to tear down embedded SurfaceControl", e);
                }
            }
        }
    };

    private void loadEmbedded() {
        if (mParentSurfaceControl == null || mIAttachEmbeddedWindow == null) {
            return;
        }
        try {
            mIAttachEmbeddedWindow.attachEmbeddedSurfaceControl(mParentSurfaceControl,
                    getDisplayId(),
                    mRemoteSurfaceView.getRootSurfaceControl().getInputTransferToken());
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to load embedded SurfaceControl", e);
        }
    }
}
