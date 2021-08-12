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

package androidx.window.extensions.organizer;

import static android.view.RemoteAnimationTarget.MODE_OPENING;

import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.WindowManager;

import androidx.annotation.Nullable;

/** To run the TaskFragment animations. */
class TaskFragmentAnimationRunner extends IRemoteAnimationRunner.Stub {

    private static final String TAG = "TaskFragAnimationRunner";
    private final Handler mHandler = new Handler(Looper.myLooper());

    @Nullable
    private IRemoteAnimationFinishedCallback mFinishedCallback;

    @Override
    public void onAnimationStart(@WindowManager.TransitionOldType int transit,
            RemoteAnimationTarget[] apps,
            RemoteAnimationTarget[] wallpapers,
            RemoteAnimationTarget[] nonApps,
            IRemoteAnimationFinishedCallback finishedCallback) {
        if (wallpapers.length != 0 || nonApps.length != 0) {
            throw new IllegalArgumentException("TaskFragment shouldn't handle animation with"
                    + "wallpaper or non-app windows.");
        }
        if (TaskFragmentAnimationController.DEBUG) {
            Log.v(TAG, "onAnimationStart transit=" + transit);
        }
        mHandler.post(() -> startAnimation(apps, finishedCallback));
    }

    @Override
    public void onAnimationCancelled() {
        if (TaskFragmentAnimationController.DEBUG) {
            Log.v(TAG, "onAnimationCancelled");
        }
        mHandler.post(this::onAnimationFinished);
    }

    private void startAnimation(RemoteAnimationTarget[] targets,
            IRemoteAnimationFinishedCallback finishedCallback) {
        // TODO(b/196173550) replace with actual animations
        mFinishedCallback = finishedCallback;
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        for (RemoteAnimationTarget target : targets) {
            if (target.mode == MODE_OPENING) {
                t.show(target.leash);
                t.setAlpha(target.leash, 1);
            }
            t.setPosition(target.leash, target.localBounds.left, target.localBounds.top);
        }
        t.apply();
        onAnimationFinished();
    }

    private void onAnimationFinished() {
        if (mFinishedCallback == null) {
            return;
        }
        try {
            mFinishedCallback.onAnimationFinished();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        mFinishedCallback = null;
    }
}
