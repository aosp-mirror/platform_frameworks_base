/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.accessibilityservice;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import com.android.internal.annotations.VisibleForTesting;

/**
 * An {@link AccessibilityService} can capture gestures performed on a device's fingerprint
 * sensor, as long as the device has a sensor capable of detecting gestures.
 * <p>
 * This capability must be declared by the service as
 * {@link AccessibilityServiceInfo#CAPABILITY_CAN_REQUEST_FINGERPRINT_GESTURES}. It also requires
 * the permission {@link android.Manifest.permission#USE_FINGERPRINT}.
 * <p>
 * Because capturing fingerprint gestures may have side effects, services with the capability only
 * capture gestures when {@link AccessibilityServiceInfo#FLAG_REQUEST_FINGERPRINT_GESTURES} is set.
 * <p>
 * <strong>Note: </strong>The fingerprint sensor is used for authentication in critical use cases,
 * so services must carefully design their user's experience when performing gestures on the sensor.
 * When the sensor is in use by an app, for example, when authenticating or enrolling a user,
 * the sensor will not detect gestures. Services need to ensure that users understand when the
 * sensor is in-use for authentication to prevent users from authenticating unintentionally when
 * trying to interact with the service. They can use
 * {@link FingerprintGestureCallback#onGestureDetectionAvailabilityChanged(boolean)} to learn when
 * gesture detection becomes unavailable.
 * <p>
 * Multiple accessibility services may listen for fingerprint gestures simultaneously, so services
 * should provide a way for the user to disable the use of this feature so multiple services don't
 * conflict with each other.
 * <p>
 * {@see android.hardware.fingerprint.FingerprintManager#isHardwareDetected}
 */
public final class FingerprintGestureController {
    /** Identifier for a swipe right on the fingerprint sensor */
    public static final int FINGERPRINT_GESTURE_SWIPE_RIGHT = 0x00000001;

    /** Identifier for a swipe left on the fingerprint sensor */
    public static final int FINGERPRINT_GESTURE_SWIPE_LEFT = 0x00000002;

    /** Identifier for a swipe up on the fingerprint sensor */
    public static final int FINGERPRINT_GESTURE_SWIPE_UP = 0x00000004;

    /** Identifier for a swipe down on the fingerprint sensor */
    public static final int FINGERPRINT_GESTURE_SWIPE_DOWN = 0x00000008;

    private static final String LOG_TAG = "FingerprintGestureController";
    private final Object mLock = new Object();
    private final IAccessibilityServiceConnection mAccessibilityServiceConnection;

    private final ArrayMap<FingerprintGestureCallback, Handler> mCallbackHandlerMap =
            new ArrayMap<>(1);

    /**
     * @param connection The connection to use for system interactions
     * @hide
     */
    @VisibleForTesting
    public FingerprintGestureController(IAccessibilityServiceConnection connection) {
        mAccessibilityServiceConnection = connection;
    }

    /**
     * Gets if the fingerprint sensor's gesture detection is available.
     *
     * @return {@code true} if the sensor's gesture detection is available. {@code false} if it is
     * not currently detecting gestures (for example, if it is enrolling a finger).
     */
    public boolean isGestureDetectionAvailable() {
        try {
            return mAccessibilityServiceConnection.isFingerprintGestureDetectionAvailable();
        } catch (RemoteException re) {
            Log.w(LOG_TAG, "Failed to check if fingerprint gestures are active", re);
            re.rethrowFromSystemServer();
            return false;
        }
    }

    /**
     * Register a callback to be informed of fingerprint sensor gesture events.
     *
     * @param callback The listener to be added.
     * @param handler The handler to use for the callback. If {@code null}, callbacks will happen
     * on the service's main thread.
     */
    public void registerFingerprintGestureCallback(
            @NonNull FingerprintGestureCallback callback, @Nullable Handler handler) {
        synchronized (mLock) {
            mCallbackHandlerMap.put(callback, handler);
        }
    }

    /**
     * Unregister a listener added with {@link #registerFingerprintGestureCallback}.
     *
     * @param callback The callback to remove. Removing a callback that was never added has no
     * effect.
     */
    public void unregisterFingerprintGestureCallback(FingerprintGestureCallback callback) {
        synchronized (mLock) {
            mCallbackHandlerMap.remove(callback);
        }
    }

    /**
     * Called when gesture detection becomes active or inactive
     * @hide
     */
    public void onGestureDetectionActiveChanged(boolean active) {
        final ArrayMap<FingerprintGestureCallback, Handler> handlerMap;
        synchronized (mLock) {
            handlerMap = new ArrayMap<>(mCallbackHandlerMap);
        }
        int numListeners = handlerMap.size();
        for (int i = 0; i < numListeners; i++) {
            FingerprintGestureCallback callback = handlerMap.keyAt(i);
            Handler handler = handlerMap.valueAt(i);
            if (handler != null) {
                handler.post(() -> callback.onGestureDetectionAvailabilityChanged(active));
            } else {
                callback.onGestureDetectionAvailabilityChanged(active);
            }
        }
    }

    /**
     * Called when gesture is detected.
     * @hide
     */
    public void onGesture(int gesture) {
        final ArrayMap<FingerprintGestureCallback, Handler> handlerMap;
        synchronized (mLock) {
            handlerMap = new ArrayMap<>(mCallbackHandlerMap);
        }
        int numListeners = handlerMap.size();
        for (int i = 0; i < numListeners; i++) {
            FingerprintGestureCallback callback = handlerMap.keyAt(i);
            Handler handler = handlerMap.valueAt(i);
            if (handler != null) {
                handler.post(() -> callback.onGestureDetected(gesture));
            } else {
                callback.onGestureDetected(gesture);
            }
        }
    }

    /**
     * Class that is called back when fingerprint gestures are being used for accessibility.
     */
    public abstract static class FingerprintGestureCallback {
        /**
         * Called when the fingerprint sensor's gesture detection becomes available or unavailable.
         *
         * @param available Whether or not the sensor's gesture detection is now available.
         */
        public void onGestureDetectionAvailabilityChanged(boolean available) {}

        /**
         * Called when the fingerprint sensor detects gestures.
         *
         * @param gesture The id of the gesture that was detected. For example,
         * {@link #FINGERPRINT_GESTURE_SWIPE_RIGHT}.
         */
        public void onGestureDetected(int gesture) {}
    }
}
