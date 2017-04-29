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
import android.os.Looper;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.util.Preconditions;

/**
 * Controller for the accessibility button within the system's navigation area
 * <p>
 * This class may be used to query the accessibility button's state and register
 * callbacks for interactions with and state changes to the accessibility button when
 * {@link AccessibilityServiceInfo#FLAG_REQUEST_ACCESSIBILITY_BUTTON} is set.
 * </p>
 * <p>
 * <strong>Note:</strong> This class and
 * {@link AccessibilityServiceInfo#FLAG_REQUEST_ACCESSIBILITY_BUTTON} should not be used as
 * the sole means for offering functionality to users via an {@link AccessibilityService}.
 * Some device implementations may choose not to provide a software-rendered system
 * navigation area, making this affordance permanently unavailable.
 * </p>
 * <p>
 * <strong>Note:</strong> On device implementations where the accessibility button is
 * supported, it may not be available at all times, such as when a foreground application uses
 * {@link android.view.View#SYSTEM_UI_FLAG_HIDE_NAVIGATION}. A user may also choose to assign
 * this button to another accessibility service or feature. In each of these cases, a
 * registered {@link AccessibilityButtonCallback}'s
 * {@link AccessibilityButtonCallback#onAvailabilityChanged(AccessibilityButtonController, boolean)}
 * method will be invoked to provide notifications of changes in the accessibility button's
 * availability to the registering service.
 * </p>
 */
public final class AccessibilityButtonController {
    private static final String LOG_TAG = "A11yButtonController";

    private final IAccessibilityServiceConnection mServiceConnection;
    private final Object mLock;
    private ArrayMap<AccessibilityButtonCallback, Handler> mCallbacks;

    AccessibilityButtonController(@NonNull IAccessibilityServiceConnection serviceConnection) {
        mServiceConnection = serviceConnection;
        mLock = new Object();
    }

    /**
     * Retrieves whether the accessibility button in the system's navigation area is
     * available to the calling service.
     * <p>
     * <strong>Note:</strong> If the service is not yet connected (e.g.
     * {@link AccessibilityService#onServiceConnected()} has not yet been called) or the
     * service has been disconnected, this method will have no effect and return {@code false}.
     * </p>
     *
     * @return {@code true} if the accessibility button in the system's navigation area is
     * available to the calling service, {@code false} otherwise
     */
    public boolean isAccessibilityButtonAvailable() {
        try {
            return mServiceConnection.isAccessibilityButtonAvailable();
        } catch (RemoteException re) {
            Slog.w(LOG_TAG, "Failed to get accessibility button availability.", re);
            re.rethrowFromSystemServer();
            return false;
        }
    }

    /**
     * Registers the provided {@link AccessibilityButtonCallback} for interaction and state
     * changes callbacks related to the accessibility button.
     *
     * @param callback the callback to add, must be non-null
     */
    public void registerAccessibilityButtonCallback(@NonNull AccessibilityButtonCallback callback) {
        registerAccessibilityButtonCallback(callback, new Handler(Looper.getMainLooper()));
    }

    /**
     * Registers the provided {@link AccessibilityButtonCallback} for interaction and state
     * change callbacks related to the accessibility button. The callback will occur on the
     * specified {@link Handler}'s thread, or on the services's main thread if the handler is
     * {@code null}.
     *
     * @param callback the callback to add, must be non-null
     * @param handler the handler on which the callback should execute, must be non-null
     */
    public void registerAccessibilityButtonCallback(@NonNull AccessibilityButtonCallback callback,
            @NonNull Handler handler) {
        Preconditions.checkNotNull(callback);
        Preconditions.checkNotNull(handler);
        synchronized (mLock) {
            if (mCallbacks == null) {
                mCallbacks = new ArrayMap<>();
            }

            mCallbacks.put(callback, handler);
        }
    }

    /**
     * Unregisters the provided {@link AccessibilityButtonCallback} for interaction and state
     * change callbacks related to the accessibility button.
     *
     * @param callback the callback to remove, must be non-null
     */
    public void unregisterAccessibilityButtonCallback(
            @NonNull AccessibilityButtonCallback callback) {
        Preconditions.checkNotNull(callback);
        synchronized (mLock) {
            if (mCallbacks == null) {
                return;
            }

            final int keyIndex = mCallbacks.indexOfKey(callback);
            final boolean hasKey = keyIndex >= 0;
            if (hasKey) {
                mCallbacks.removeAt(keyIndex);
            }
        }
    }

    /**
     * Dispatches the accessibility button click to any registered callbacks. This should
     * be called on the service's main thread.
     */
    void dispatchAccessibilityButtonClicked() {
        final ArrayMap<AccessibilityButtonCallback, Handler> entries;
        synchronized (mLock) {
            if (mCallbacks == null || mCallbacks.isEmpty()) {
                Slog.w(LOG_TAG, "Received accessibility button click with no callbacks!");
                return;
            }

            // Callbacks may remove themselves. Perform a shallow copy to avoid concurrent
            // modification.
            entries = new ArrayMap<>(mCallbacks);
        }

        for (int i = 0, count = entries.size(); i < count; i++) {
            final AccessibilityButtonCallback callback = entries.keyAt(i);
            final Handler handler = entries.valueAt(i);
            handler.post(() -> callback.onClicked(this));
        }
    }

    /**
     * Dispatches the accessibility button availability changes to any registered callbacks.
     * This should be called on the service's main thread.
     */
    void dispatchAccessibilityButtonAvailabilityChanged(boolean available) {
        final ArrayMap<AccessibilityButtonCallback, Handler> entries;
        synchronized (mLock) {
            if (mCallbacks == null || mCallbacks.isEmpty()) {
                Slog.w(LOG_TAG,
                        "Received accessibility button availability change with no callbacks!");
                return;
            }

            // Callbacks may remove themselves. Perform a shallow copy to avoid concurrent
            // modification.
            entries = new ArrayMap<>(mCallbacks);
        }

        for (int i = 0, count = entries.size(); i < count; i++) {
            final AccessibilityButtonCallback callback = entries.keyAt(i);
            final Handler handler = entries.valueAt(i);
            handler.post(() -> callback.onAvailabilityChanged(this, available));
        }
    }

    /**
     * Callback for interaction with and changes to state of the accessibility button
     * within the system's navigation area.
     */
    public static abstract class AccessibilityButtonCallback {

        /**
         * Called when the accessibility button in the system's navigation area is clicked.
         *
         * @param controller the controller used to register for this callback
         */
        public void onClicked(AccessibilityButtonController controller) {}

        /**
         * Called when the availability of the accessibility button in the system's
         * navigation area has changed. The accessibility button may become unavailable
         * because the device shopped showing the button, the button was assigned to another
         * service, or for other reasons.
         *
         * @param controller the controller used to register for this callback
         * @param available {@code true} if the accessibility button is available to this
         *                  service, {@code false} otherwise
         */
        public void onAvailabilityChanged(AccessibilityButtonController controller,
                boolean available) {
        }
    }
}
