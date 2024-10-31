/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm.flicker.testapp;

import static com.android.wm.shell.flicker.utils.MediaProjectionUtils.EXTRA_MESSENGER;
import static com.android.wm.shell.flicker.utils.MediaProjectionUtils.MSG_SERVICE_DESTROYED;
import static com.android.wm.shell.flicker.utils.MediaProjectionUtils.MSG_START_FOREGROUND_DONE;
import static com.android.wm.shell.flicker.utils.MediaProjectionUtils.REQUEST_CODE;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Messenger;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Button;

import com.android.wm.shell.flicker.utils.MediaProjectionService;

public class StartMediaProjectionActivity extends Activity {

    private static final String TAG = "StartMediaProjectionActivity";
    private MediaProjectionManager mService;
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private MediaProjection mMediaProjection;
    private MediaProjection.Callback mMediaProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            super.onStop();
        }

        @Override
        public void onCapturedContentResize(int width, int height) {
            super.onCapturedContentResize(width, height);
        }

        @Override
        public void onCapturedContentVisibilityChanged(boolean isVisible) {
            super.onCapturedContentVisibilityChanged(isVisible);
        }
    };

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mService = getSystemService(MediaProjectionManager.class);
        setContentView(R.layout.activity_start_media_projection);

        Button startMediaProjectionButton = findViewById(R.id.button_start_mp);
        startMediaProjectionButton.setOnClickListener(v ->
                startActivityForResult(mService.createScreenCaptureIntent(), REQUEST_CODE));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE) {
            throw new IllegalStateException("Unknown request code: " + requestCode);
        }
        if (resultCode != RESULT_OK) {
            throw new IllegalStateException("User denied screen sharing permission");
        }
        Log.d(TAG, "onActivityResult");
        startMediaProjectionService(resultCode, data);
    }

    private void startMediaProjectionService(int resultCode, Intent resultData) {
        final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(),
                msg -> {
                    switch (msg.what) {
                        case MSG_START_FOREGROUND_DONE:
                            setupMediaProjection(resultCode, resultData);
                            return true;
                        case MSG_SERVICE_DESTROYED:
                            return true;
                    }
                    Log.e(TAG, "Unknown message from the FlickerMPService: " + msg.what);
                    return false;
                }
        ));

        final Intent intent = new Intent()
                .setComponent(new ComponentName(this, MediaProjectionService.class))
                .putExtra(EXTRA_MESSENGER, messenger);
        startForegroundService(intent);
    }

    private void setupMediaProjection(int resultCode, Intent resultData) {
        mMediaProjection = mService.getMediaProjection(resultCode, resultData);
        if (mMediaProjection == null) {
            throw new IllegalStateException("cannot create new MediaProjection");
        }

        mMediaProjection.registerCallback(
                mMediaProjectionCallback, new Handler(Looper.getMainLooper()));

        Rect displayBounds = getWindowManager().getMaximumWindowMetrics().getBounds();
        mImageReader = ImageReader.newInstance(
                displayBounds.width(), displayBounds.height(), PixelFormat.RGBA_8888, 1);

        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                "DanielDisplay",
                displayBounds.width(),
                displayBounds.height(),
                DisplayMetrics.DENSITY_HIGH,
                /* flags= */ 0,
                mImageReader.getSurface(),
                new VirtualDisplay.Callback() {
                    @Override
                    public void onStopped() {
                        if (mMediaProjection != null) {
                            if (mMediaProjectionCallback != null) {
                                mMediaProjection.unregisterCallback(mMediaProjectionCallback);
                                mMediaProjectionCallback = null;
                            }
                            mMediaProjection.stop();
                            mMediaProjection = null;
                        }
                        if (mImageReader != null) {
                            mImageReader = null;
                        }
                        if (mVirtualDisplay != null) {
                            mVirtualDisplay.getSurface().release();
                            mVirtualDisplay.release();
                            mVirtualDisplay = null;
                        }
                    }
                },
                new Handler(Looper.getMainLooper())
        );
    }

}
