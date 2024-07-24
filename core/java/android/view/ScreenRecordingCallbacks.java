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

package android.view;

import static android.Manifest.permission.DETECT_SCREEN_RECORDING;
import static android.view.WindowManager.SCREEN_RECORDING_STATE_NOT_VISIBLE;
import static android.view.WindowManager.SCREEN_RECORDING_STATE_VISIBLE;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.os.Binder;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.view.WindowManager.ScreenRecordingState;
import android.window.IScreenRecordingCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class is responsible for calling app-registered screen recording callbacks. This class
 * registers a single screen recording callback with WindowManagerService and calls the
 * app-registered callbacks whenever that WindowManagerService callback is called.
 *
 * @hide
 */
public final class ScreenRecordingCallbacks {

    private static ScreenRecordingCallbacks sInstance;
    private static final Object sLock = new Object();

    private final ArrayMap<Consumer<@ScreenRecordingState Integer>, Executor> mCallbacks =
            new ArrayMap<>();

    private IScreenRecordingCallback mCallbackNotifier;
    private @ScreenRecordingState int mState = SCREEN_RECORDING_STATE_NOT_VISIBLE;

    private ScreenRecordingCallbacks() {}

    private static @NonNull IWindowManager getWindowManagerService() {
        return Objects.requireNonNull(WindowManagerGlobal.getWindowManagerService());
    }

    static ScreenRecordingCallbacks getInstance() {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new ScreenRecordingCallbacks();
            }
            return sInstance;
        }
    }

    @RequiresPermission(DETECT_SCREEN_RECORDING)
    @ScreenRecordingState
    int addCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<@ScreenRecordingState Integer> callback) {
        synchronized (sLock) {
            if (mCallbackNotifier == null) {
                mCallbackNotifier =
                        new IScreenRecordingCallback.Stub() {
                            @Override
                            public void onScreenRecordingStateChanged(
                                    boolean visibleInScreenRecording) {
                                int state =
                                        visibleInScreenRecording
                                                ? SCREEN_RECORDING_STATE_VISIBLE
                                                : SCREEN_RECORDING_STATE_NOT_VISIBLE;
                                notifyCallbacks(state);
                            }
                        };
                try {
                    boolean visibleInScreenRecording =
                            getWindowManagerService()
                                    .registerScreenRecordingCallback(mCallbackNotifier);
                    mState =
                            visibleInScreenRecording
                                    ? SCREEN_RECORDING_STATE_VISIBLE
                                    : SCREEN_RECORDING_STATE_NOT_VISIBLE;
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
            mCallbacks.put(callback, executor);
            return mState;
        }
    }

    @RequiresPermission(DETECT_SCREEN_RECORDING)
    void removeCallback(@NonNull Consumer<@ScreenRecordingState Integer> callback) {
        synchronized (sLock) {
            mCallbacks.remove(callback);
            if (mCallbacks.isEmpty()) {
                try {
                    getWindowManagerService().unregisterScreenRecordingCallback(mCallbackNotifier);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
                mCallbackNotifier = null;
            }
        }
    }

    private void notifyCallbacks(@ScreenRecordingState int state) {
        List<Runnable> callbacks;
        synchronized (sLock) {
            mState = state;
            if (mCallbacks.isEmpty()) {
                return;
            }

            callbacks = new ArrayList<>();
            for (int i = 0; i < mCallbacks.size(); i++) {
                Consumer<Integer> callback = mCallbacks.keyAt(i);
                Executor executor = mCallbacks.valueAt(i);
                callbacks.add(() -> executor.execute(() -> callback.accept(state)));
            }
        }
        final long token = Binder.clearCallingIdentity();
        try {
            for (int i = 0; i < callbacks.size(); i++) {
                callbacks.get(i).run();
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }
}
