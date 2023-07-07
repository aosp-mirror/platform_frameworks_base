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

package android.app;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;

import java.util.concurrent.Executor;

/** Handles screen capture callbacks.
 * @hide
 **/
public class ScreenCaptureCallbackHandler {

    private final IBinder mActivityToken;
    private final ScreenCaptureObserver mObserver;
    private final ArrayMap<Activity.ScreenCaptureCallback, ScreenCaptureRegistration>
            mScreenCaptureRegistrations = new ArrayMap<>();

    public ScreenCaptureCallbackHandler(@NonNull IBinder activityToken) {
        mActivityToken = activityToken;
        mObserver = new ScreenCaptureObserver(mScreenCaptureRegistrations);
    }

    private static class ScreenCaptureRegistration {
        Executor mExecutor;
        Activity.ScreenCaptureCallback mCallback;

        ScreenCaptureRegistration(Executor executor, Activity.ScreenCaptureCallback callback) {
            this.mExecutor = executor;
            this.mCallback = callback;
        }
    }

    private static class ScreenCaptureObserver extends IScreenCaptureObserver.Stub {
        ArrayMap<Activity.ScreenCaptureCallback, ScreenCaptureRegistration> mRegistrations;

        ScreenCaptureObserver(
                ArrayMap<Activity.ScreenCaptureCallback, ScreenCaptureRegistration>
                        registrations) {
            this.mRegistrations = registrations;
        }

        @Override
        public void onScreenCaptured() {
            for (ScreenCaptureRegistration registration : mRegistrations.values()) {
                registration.mExecutor.execute(
                        () -> {
                            registration.mCallback.onScreenCaptured();
                        });
            }
        }
    }

    /**
     * Start monitoring for screen captures of the activity, the callback will be triggered whenever
     * a screen capture is attempted. This callback will be executed on the thread of the passed
     * {@code executor}. If the window is FLAG_SECURE, the callback will not be triggered.
     */
    public void registerScreenCaptureCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Activity.ScreenCaptureCallback callback) {
        ScreenCaptureRegistration registration =
                new ScreenCaptureRegistration(executor, callback);
        synchronized (mScreenCaptureRegistrations) {
            if (mScreenCaptureRegistrations.containsKey(callback)) {
                throw new IllegalStateException(
                        "Capture observer already registered with the activity");
            }
            mScreenCaptureRegistrations.put(callback, registration);
            // register with system server only once.
            if (mScreenCaptureRegistrations.size() == 1) {
                try {
                    ActivityTaskManager.getService()
                            .registerScreenCaptureObserver(mActivityToken, mObserver);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
        }
    }
    /** Stop monitoring for screen captures of the activity */
    public void unregisterScreenCaptureCallback(@NonNull Activity.ScreenCaptureCallback callback) {
        synchronized (mScreenCaptureRegistrations) {
            if (!mScreenCaptureRegistrations.containsKey(callback)) {
                throw new IllegalStateException(
                        "Capture observer not registered with the activity");
            }
            mScreenCaptureRegistrations.remove(callback);
            // unregister only if no more registrations are left
            if (mScreenCaptureRegistrations.size() == 0) {
                try {
                    ActivityTaskManager.getService().unregisterScreenCaptureObserver(mActivityToken,
                            mObserver);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }
        }
    }
}
