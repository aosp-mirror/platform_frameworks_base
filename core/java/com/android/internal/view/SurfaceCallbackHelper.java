/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.view;

import android.os.RemoteException;
import android.view.Surface;
import android.view.SurfaceHolder;

public class SurfaceCallbackHelper {
    Runnable mRunnable;

    int mFinishDrawingCollected = 0;
    int mFinishDrawingExpected = 0;

    private Runnable mFinishDrawingRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (SurfaceCallbackHelper.this) {
                    mFinishDrawingCollected++;
                    if (mFinishDrawingCollected < mFinishDrawingExpected) {
                        return;
                    }
                    mRunnable.run();
                }
            }
    };

    public SurfaceCallbackHelper(Runnable callbacksCollected) {
        mRunnable = callbacksCollected;
    }

    public void dispatchSurfaceRedrawNeededAsync(SurfaceHolder holder, SurfaceHolder.Callback callbacks[]) {
        if (callbacks == null || callbacks.length == 0) {
            mRunnable.run();
            return;
        }

        synchronized (this) {
            mFinishDrawingExpected = callbacks.length;
            mFinishDrawingCollected = 0;
        }

        for (SurfaceHolder.Callback c : callbacks) {
            if (c instanceof SurfaceHolder.Callback2) {
                ((SurfaceHolder.Callback2) c).surfaceRedrawNeededAsync(
                        holder, mFinishDrawingRunnable);
            } else {
                mFinishDrawingRunnable.run();
            }
        }
    }
}