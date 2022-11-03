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

package android.view.accessibility;

import android.accessibilityservice.AccessibilityGestureEvent;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.IAccessibilityServiceClient;
import android.accessibilityservice.MagnificationConfig;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.Context;
import android.graphics.Region;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.EditorInfo;

import com.android.internal.inputmethod.IAccessibilityInputMethodSessionCallback;
import com.android.internal.inputmethod.RemoteAccessibilityInputConnection;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Allows a privileged app - an app with MANAGE_ACCESSIBILITY permission and SystemAPI access - to
 * interact with the windows in the display that this proxy represents. Proxying the default display
 * or a display that is not tracked will throw an exception. Only the real user has access to global
 * clients like SystemUI.
 *
 * <p>
 * To register and unregister a proxy, use
 * {@link AccessibilityManager#registerDisplayProxy(AccessibilityDisplayProxy)}
 * and {@link AccessibilityManager#unregisterDisplayProxy(AccessibilityDisplayProxy)}. If the app
 * that has registered the proxy dies, the system will remove the proxy.
 *
 * TODO(241429275): Complete proxy impl and add additional support (if necessary) like cache methods
 * @hide
 */
@SystemApi
public abstract class AccessibilityDisplayProxy {
    private static final String LOG_TAG = "AccessibilityDisplayProxy";
    private static final int INVALID_CONNECTION_ID = -1;

    private List<AccessibilityServiceInfo> mInstalledAndEnabledServices;
    private Executor mExecutor;
    private int mConnectionId = INVALID_CONNECTION_ID;
    private int mDisplayId;
    IAccessibilityServiceClient mServiceClient;

    /**
     * Constructs an AccessibilityDisplayProxy instance.
     * @param displayId the id of the display to proxy.
     * @param executor the executor used to execute proxy callbacks.
     * @param installedAndEnabledServices the list of infos representing the installed and
     *                                    enabled a11y services.
     */
    public AccessibilityDisplayProxy(int displayId, @NonNull Executor executor,
            @NonNull List<AccessibilityServiceInfo> installedAndEnabledServices) {
        mDisplayId = displayId;
        mExecutor = executor;
        // Typically, the context is the Service context of an accessibility service.
        // Context is used for ResolveInfo check, which a proxy won't have, IME input
        // (FLAG_INPUT_METHOD_EDITOR), which the proxy doesn't need, and tracing
        // A11yInteractionClient methods.
        // TODO(254097475): Enable tracing, potentially without exposing Context.
        mServiceClient = new IAccessibilityServiceClientImpl(null, mExecutor);
        mInstalledAndEnabledServices = installedAndEnabledServices;
    }

    /**
     * Returns the id of the display being proxy-ed.
     */
    public int getDisplayId() {
        return mDisplayId;
    }

    /**
     * An IAccessibilityServiceClient that handles interrupts and accessibility events.
     */
    private class IAccessibilityServiceClientImpl extends
            AccessibilityService.IAccessibilityServiceClientWrapper {

        IAccessibilityServiceClientImpl(Context context, Executor executor) {
            super(context, executor, new AccessibilityService.Callbacks() {
                @Override
                public void onAccessibilityEvent(AccessibilityEvent event) {
                    // TODO: call AccessiiblityProxy.onAccessibilityEvent
                }

                @Override
                public void onInterrupt() {
                    // TODO: call AccessiiblityProxy.onInterrupt
                }
                @Override
                public void onServiceConnected() {
                    // TODO: send service infos and call AccessiiblityProxy.onProxyConnected
                }
                @Override
                public void init(int connectionId, IBinder windowToken) {
                    mConnectionId = connectionId;
                }

                @Override
                public boolean onGesture(AccessibilityGestureEvent gestureInfo) {
                    return false;
                }

                @Override
                public boolean onKeyEvent(KeyEvent event) {
                    return false;
                }

                @Override
                public void onMagnificationChanged(int displayId, @NonNull Region region,
                        MagnificationConfig config) {
                }

                @Override
                public void onMotionEvent(MotionEvent event) {
                }

                @Override
                public void onTouchStateChanged(int displayId, int state) {
                }

                @Override
                public void onSoftKeyboardShowModeChanged(int showMode) {
                }

                @Override
                public void onPerformGestureResult(int sequence, boolean completedSuccessfully) {
                }

                @Override
                public void onFingerprintCapturingGesturesChanged(boolean active) {
                }

                @Override
                public void onFingerprintGesture(int gesture) {
                }

                @Override
                public void onAccessibilityButtonClicked(int displayId) {
                }

                @Override
                public void onAccessibilityButtonAvailabilityChanged(boolean available) {
                }

                @Override
                public void onSystemActionsChanged() {
                }

                @Override
                public void createImeSession(IAccessibilityInputMethodSessionCallback callback) {
                }

                @Override
                public void startInput(@Nullable RemoteAccessibilityInputConnection inputConnection,
                        @NonNull EditorInfo editorInfo, boolean restarting) {
                }
            });
        }
    }
}
