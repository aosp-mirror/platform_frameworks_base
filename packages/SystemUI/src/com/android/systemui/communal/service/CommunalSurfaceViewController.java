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

import android.annotation.IntDef;
import android.util.Log;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;

import com.android.systemui.util.ViewController;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.Executor;

/**
 * {@link CommunalSurfaceViewController} coordinates requesting communal surfaces to populate a
 * {@link SurfaceView} with.
 */
public class CommunalSurfaceViewController extends ViewController<SurfaceView> {
    private static final String TAG = "CommunalSurfaceViewCtlr";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private final Executor mMainExecutor;
    private final CommunalSourceImpl mSource;

    @IntDef({STATE_SURFACE_CREATED, STATE_SURFACE_VIEW_ATTACHED})
    private @interface State {}

    private static final int STATE_SURFACE_CREATED = 1 << 0;
    private static final int STATE_SURFACE_VIEW_ATTACHED = 1 << 1;

    private static final int STATE_CAN_SHOW_SURFACE =
            STATE_SURFACE_CREATED | STATE_SURFACE_VIEW_ATTACHED;

    private int mCurrentState;

    // The current in-flight request for a surface package.
    private ListenableFuture<SurfaceControlViewHost.SurfacePackage> mCurrentSurfaceFuture;

    private final SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            setState(STATE_SURFACE_CREATED, true);
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width,
                int height) {
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            setState(STATE_SURFACE_CREATED, false);
        }
    };

    protected CommunalSurfaceViewController(SurfaceView view, Executor executor,
            CommunalSourceImpl source) {
        super(view);
        mSource = source;
        mMainExecutor = executor;
    }

    @Override
    public void init() {
        super.init();
        mView.getHolder().addCallback(mSurfaceHolderCallback);
    }

    private void setState(@State int state, boolean enabled) {
        if (DEBUG) {
            Log.d(TAG, "setState. state:" + state + " enable:" + enabled);
        }

        final int newState = enabled ? mCurrentState | state : mCurrentState & ~state;

        // no new state is available
        if (newState == mCurrentState) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "setState. new state:" + mCurrentState);
        }

        mCurrentState = newState;

        showSurface(newState == STATE_CAN_SHOW_SURFACE);
    }

    private void showSurface(boolean show) {
        mView.setWillNotDraw(false);

        if (!show) {
            // If the surface is no longer showing, cancel any in-flight requests.
            if (mCurrentSurfaceFuture != null) {
                mCurrentSurfaceFuture.cancel(true);
                mCurrentSurfaceFuture = null;
            }

            mView.setWillNotDraw(true);
            return;
        }

        // Since this method is only called when the state has changed, mCurrentSurfaceFuture should
        // be null here.
        mCurrentSurfaceFuture = mSource.requestCommunalSurface(mView.getHostToken(),
                        mView.getDisplay().getDisplayId(), mView.getMeasuredWidth(),
                        mView.getMeasuredHeight());

        mCurrentSurfaceFuture.addListener(new Runnable() {
            @Override
            public void run() {
                try {
                    // If the request is received after detached, ignore.
                    if (!mView.isAttachedToWindow()) {
                        return;
                    }

                    SurfaceControlViewHost.SurfacePackage surfacePackage =
                            mCurrentSurfaceFuture.get();
                    mCurrentSurfaceFuture = null;

                    if (DEBUG) {
                        Log.d(TAG, "Received surface package:" + surfacePackage);
                    }

                    if (surfacePackage != null) {
                        mView.setChildSurfacePackage(surfacePackage);
                        mView.setZOrderOnTop(true);
                        mView.postInvalidate();
                    } else {
                        Log.e(TAG, "couldn't get the surface package");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "An error occurred retrieving the future result:" + e);
                }
            }
        }, mMainExecutor);
    }

    @Override
    protected void onViewAttached() {
        setState(STATE_SURFACE_VIEW_ATTACHED, true);
    }

    @Override
    protected void onViewDetached() {
        setState(STATE_SURFACE_VIEW_ATTACHED, false);
    }
}
