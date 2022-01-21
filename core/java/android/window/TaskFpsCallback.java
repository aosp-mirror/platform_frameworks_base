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

package android.window;

import android.annotation.BinderThread;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.RemoteException;

import java.util.concurrent.Executor;

/**
 * Callback for sampling the frames per second for a task and its children.
 * This should only be used by a system component that needs to listen to a task's
 * tree's FPS when it is not actively submitting transactions for that corresponding SurfaceControl.
 * Otherwise, ASurfaceTransaction_OnComplete callbacks should be used.
 *
 * Each callback can only register for receiving FPS report for one task id until
 * {@link WindowManager#unregister()} is called.
 *
 * @hide
 */
@SystemApi
public final class TaskFpsCallback {

    /**
     * Listener interface to receive frame per second of a task.
     */
    public interface OnFpsCallbackListener {
        /**
         * Reports the fps from the registered task
         * @param fps The frame per second of the task that has the registered task id
         *            and its children.
         */
        void onFpsReported(float fps);
    }

    private final IOnFpsCallbackListener mListener;

    public TaskFpsCallback(@NonNull Executor executor, @NonNull OnFpsCallbackListener listener) {
        mListener = new IOnFpsCallbackListener.Stub() {
            @Override
            public void onFpsReported(float fps) {
                executor.execute(() -> {
                    listener.onFpsReported(fps);
                });
            }
        };
    }

    /**
     * @hide
     */
    public IOnFpsCallbackListener getListener() {
        return mListener;
    }

    /**
     * Dispatch the collected sample.
     *
     * Called from native code on a binder thread.
     */
    @BinderThread
    private static void dispatchOnFpsReported(
            @NonNull IOnFpsCallbackListener listener, float fps) {
        try {
            listener.onFpsReported(fps);
        } catch (RemoteException e) {
            /* ignore */
        }
    }
}
