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

package com.android.systemui.communal.service;

import android.content.Context;
import android.util.Log;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Executor;

/**
 * {@link CommunalSurfaceView} can be used to display remote surfaces returned by the
 * {@link CommunalService}. The necessary information for the request are derived from the view's
 * events from being attached to the parent container.
 */
public class CommunalSurfaceView extends SurfaceView {
    private static final String TAG = "CommunalSurfaceView";
    private static final boolean DEBUG = false;
    private final Executor mMainExecutor;
    private final CommunalSourceImpl mSource;

    public CommunalSurfaceView(Context context, Executor executor, CommunalSourceImpl source) {
        super(context);
        mSource = source;
        mMainExecutor = executor;

        getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                onSurfaceCreated();
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width,
                    int height) {

            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {

            }
        });
    }

    private void onSurfaceCreated() {
        setWillNotDraw(false);

        final ListenableFuture<SurfaceControlViewHost.SurfacePackage> surfaceFuture =
                mSource.requestCommunalSurface(this.getHostToken(),
                        getDisplay().getDisplayId(), getMeasuredWidth(), getMeasuredHeight());

        surfaceFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    SurfaceControlViewHost.SurfacePackage surfacePackage = surfaceFuture.get();

                    if (DEBUG) {
                        Log.d(TAG, "Received surface package:" + surfacePackage);
                    }

                    if (surfacePackage != null) {
                        setChildSurfacePackage(surfacePackage);
                        setZOrderOnTop(true);
                        postInvalidate();
                    } else {
                        Log.e(TAG, "couldn't get the surface package");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "An error occurred retrieving the future result:" + e);
                }
            }
        }, mMainExecutor);
    }
}
