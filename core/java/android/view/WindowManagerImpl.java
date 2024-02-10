/*
 * Copyright (C) 2006 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.window.WindowProviderService.isWindowProviderService;

import static com.android.window.flags.Flags.screenRecordingCallbacks;

import android.annotation.CallbackExecutor;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiContext;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Region;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.StrictMode;
import android.util.Log;
import android.window.ITaskFpsCallback;
import android.window.InputTransferToken;
import android.window.TaskFpsCallback;
import android.window.TrustedPresentationThresholds;
import android.window.WindowContext;
import android.window.WindowMetricsController;
import android.window.WindowProvider;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IResultReceiver;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Provides low-level communication with the system window manager for
 * operations that are bound to a particular context, display or parent window.
 * Instances of this object are sensitive to the compatibility info associated
 * with the running application.
 *
 * This object implements the {@link ViewManager} interface,
 * allowing you to add any View subclass as a top-level window on the screen.
 * Additional window manager specific layout parameters are defined for
 * control over how windows are displayed.  It also implements the {@link WindowManager}
 * interface, allowing you to control the displays attached to the device.
 *
 * <p>Applications will not normally use WindowManager directly, instead relying
 * on the higher-level facilities in {@link android.app.Activity} and
 * {@link android.app.Dialog}.
 *
 * <p>Even for low-level window manager access, it is almost never correct to use
 * this class.  For example, {@link android.app.Activity#getWindowManager}
 * provides a window manager for adding windows that are associated with that
 * activity -- the window manager will not normally allow you to add arbitrary
 * windows that are not associated with an activity.
 *
 * @see WindowManager
 * @see WindowManagerGlobal
 * @hide
 */
public final class WindowManagerImpl implements WindowManager {
    private static final String TAG = "WindowManager";

    @UnsupportedAppUsage
    private final WindowManagerGlobal mGlobal = WindowManagerGlobal.getInstance();
    @UiContext
    @VisibleForTesting
    public final Context mContext;
    private final Window mParentWindow;

    /**
     * If {@link LayoutParams#token} is {@code null} and no parent window is specified, the value
     * of {@link LayoutParams#token} will be overridden to {@code mDefaultToken}.
     */
    private IBinder mDefaultToken;

    /**
     * This token will be set to {@link LayoutParams#mWindowContextToken} and used to receive
     * configuration changes from the server side.
     */
    @Nullable
    private final IBinder mWindowContextToken;

    @GuardedBy("mOnFpsCallbackListenerProxies")
    private final ArrayList<OnFpsCallbackListenerProxy> mOnFpsCallbackListenerProxies =
            new ArrayList<>();

    /** A controller to handle {@link WindowMetrics} related APIs */
    @NonNull
    private final WindowMetricsController mWindowMetricsController;

    public WindowManagerImpl(Context context) {
        this(context, null /* parentWindow */, null /* clientToken */);
    }

    private WindowManagerImpl(Context context, Window parentWindow,
            @Nullable IBinder windowContextToken) {
        mContext = context;
        mParentWindow = parentWindow;
        mWindowContextToken = windowContextToken;
        mWindowMetricsController = new WindowMetricsController(mContext);
    }

    public WindowManagerImpl createLocalWindowManager(Window parentWindow) {
        return new WindowManagerImpl(mContext, parentWindow, mWindowContextToken);
    }

    public WindowManagerImpl createPresentationWindowManager(Context displayContext) {
        return new WindowManagerImpl(displayContext, mParentWindow, mWindowContextToken);
    }

    /** Creates a {@link WindowManager} for a {@link WindowContext}. */
    public static WindowManager createWindowContextWindowManager(Context context) {
        final IBinder clientToken = context.getWindowContextToken();
        return new WindowManagerImpl(context, null /* parentWindow */, clientToken);
    }

    /**
     * Sets the window token to assign when none is specified by the client or
     * available from the parent window.
     *
     * @param token The default token to assign.
     */
    public void setDefaultToken(IBinder token) {
        mDefaultToken = token;
    }

    @Override
    public void addView(@NonNull View view, @NonNull ViewGroup.LayoutParams params) {
        applyTokens(params);
        mGlobal.addView(view, params, mContext.getDisplayNoVerify(), mParentWindow,
                mContext.getUserId());
    }

    @Override
    public void updateViewLayout(@NonNull View view, @NonNull ViewGroup.LayoutParams params) {
        applyTokens(params);
        mGlobal.updateViewLayout(view, params);
    }

    private void applyTokens(@NonNull ViewGroup.LayoutParams params) {
        if (!(params instanceof WindowManager.LayoutParams)) {
            throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
        }
        final WindowManager.LayoutParams wparams = (WindowManager.LayoutParams) params;
        assertWindowContextTypeMatches(wparams.type);
        // Only use the default token if we don't have a parent window and a token.
        if (mDefaultToken != null && mParentWindow == null && wparams.token == null) {
            wparams.token = mDefaultToken;
        }
        wparams.mWindowContextToken = mWindowContextToken;
    }

    private void assertWindowContextTypeMatches(@LayoutParams.WindowType int windowType) {
        if (!(mContext instanceof WindowProvider)) {
            return;
        }
        // Don't need to check sub-window type because sub window should be allowed to be attached
        // to the parent window.
        if (windowType >= FIRST_SUB_WINDOW && windowType <= LAST_SUB_WINDOW) {
            return;
        }
        final WindowProvider windowProvider = (WindowProvider) mContext;
        if (windowProvider.getWindowType() == windowType) {
            return;
        }
        IllegalArgumentException exception = new IllegalArgumentException("Window type mismatch."
                + " Window Context's window type is " + windowProvider.getWindowType()
                + ", while LayoutParams' type is set to " + windowType + "."
                + " Please create another Window Context via"
                + " createWindowContext(getDisplay(), " + windowType + ", null)"
                + " to add window with type:" + windowType);
        if (!isWindowProviderService(windowProvider.getWindowContextOptions())) {
            throw exception;
        }
        // Throw IncorrectCorrectViolation if the Window Context is allowed to provide multiple
        // window types. Usually it's because the Window Context is a WindowProviderService.
        StrictMode.onIncorrectContextUsed("WindowContext's window type must"
                + " match type in WindowManager.LayoutParams", exception);
    }

    @Override
    public void removeView(View view) {
        mGlobal.removeView(view, false);
    }

    @Override
    public void removeViewImmediate(View view) {
        mGlobal.removeView(view, true);
    }

    @Override
    public void requestAppKeyboardShortcuts(
            final KeyboardShortcutsReceiver receiver, int deviceId) {
        IResultReceiver resultReceiver = new IResultReceiver.Stub() {
            @Override
            public void send(int resultCode, Bundle resultData) throws RemoteException {
                List<KeyboardShortcutGroup> result =
                        resultData.getParcelableArrayList(PARCEL_KEY_SHORTCUTS_ARRAY,
                                android.view.KeyboardShortcutGroup.class);
                receiver.onKeyboardShortcutsReceived(result);
            }
        };
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .requestAppKeyboardShortcuts(resultReceiver, deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public void requestImeKeyboardShortcuts(
            final KeyboardShortcutsReceiver receiver, int deviceId) {
        IResultReceiver resultReceiver = new IResultReceiver.Stub() {
            @Override
            public void send(int resultCode, Bundle resultData) throws RemoteException {
                List<KeyboardShortcutGroup> result =
                        resultData.getParcelableArrayList(PARCEL_KEY_SHORTCUTS_ARRAY,
                                android.view.KeyboardShortcutGroup.class);
                receiver.onKeyboardShortcutsReceived(result);
            }
        };
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .requestImeKeyboardShortcuts(resultReceiver, deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public Display getDefaultDisplay() {
        return mContext.getDisplayNoVerify();
    }

    @Override
    public Region getCurrentImeTouchRegion() {
        try {
            return WindowManagerGlobal.getWindowManagerService().getCurrentImeTouchRegion();
        } catch (RemoteException e) {
        }
        return null;
    }

    @Override
    public void setShouldShowWithInsecureKeyguard(int displayId, boolean shouldShow) {
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .setShouldShowWithInsecureKeyguard(displayId, shouldShow);
        } catch (RemoteException e) {
        }
    }

    @Override
    public void setShouldShowSystemDecors(int displayId, boolean shouldShow) {
        try {
            WindowManagerGlobal.getWindowManagerService()
                    .setShouldShowSystemDecors(displayId, shouldShow);
        } catch (RemoteException e) {
        }
    }

    @Override
    public boolean shouldShowSystemDecors(int displayId) {
        try {
            return WindowManagerGlobal.getWindowManagerService().shouldShowSystemDecors(displayId);
        } catch (RemoteException e) {
        }
        return false;
    }

    @Override
    public void setDisplayImePolicy(int displayId, @DisplayImePolicy int imePolicy) {
        try {
            WindowManagerGlobal.getWindowManagerService().setDisplayImePolicy(displayId, imePolicy);
        } catch (RemoteException e) {
        }
    }

    @Override
    public @DisplayImePolicy int getDisplayImePolicy(int displayId) {
        try {
            return WindowManagerGlobal.getWindowManagerService().getDisplayImePolicy(displayId);
        } catch (RemoteException e) {
        }
        return DISPLAY_IME_POLICY_FALLBACK_DISPLAY;
    }

    @Override
    public boolean isGlobalKey(int keyCode) {
        try {
            return WindowManagerGlobal.getWindowManagerService().isGlobalKey(keyCode);
        } catch (RemoteException e) {
        }
        return false;
    }

    @Override
    public WindowMetrics getCurrentWindowMetrics() {
        return mWindowMetricsController.getCurrentWindowMetrics();
    }

    @Override
    public WindowMetrics getMaximumWindowMetrics() {
        return mWindowMetricsController.getMaximumWindowMetrics();
    }

    @Override
    @NonNull
    public Set<WindowMetrics> getPossibleMaximumWindowMetrics(int displayId) {
        return mWindowMetricsController.getPossibleMaximumWindowMetrics(displayId);
    }

    @Override
    public void holdLock(IBinder token, int durationMs) {
        try {
            WindowManagerGlobal.getWindowManagerService().holdLock(token, durationMs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean isCrossWindowBlurEnabled() {
        return CrossWindowBlurListeners.getInstance().isCrossWindowBlurEnabled();
    }

    @Override
    public void addCrossWindowBlurEnabledListener(@NonNull Consumer<Boolean> listener) {
        addCrossWindowBlurEnabledListener(mContext.getMainExecutor(), listener);
    }

    @Override
    public void addCrossWindowBlurEnabledListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<Boolean> listener) {
        CrossWindowBlurListeners.getInstance().addListener(executor, listener);
    }

    @Override
    public void removeCrossWindowBlurEnabledListener(@NonNull Consumer<Boolean> listener) {
        CrossWindowBlurListeners.getInstance().removeListener(listener);
    }

    @Override
    public void addProposedRotationListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull IntConsumer listener) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(listener, "listener must not be null");
        final IBinder contextToken = Context.getToken(mContext);
        if (contextToken == null) {
            throw new UnsupportedOperationException("The context of this window manager instance "
                    + "must be a UI context, e.g. an Activity or a Context created by "
                    + "Context#createWindowContext()");
        }
        mGlobal.registerProposedRotationListener(contextToken, executor, listener);
    }

    @Override
    public void removeProposedRotationListener(@NonNull IntConsumer listener) {
        mGlobal.unregisterProposedRotationListener(Context.getToken(mContext), listener);
    }

    @Override
    public boolean isTaskSnapshotSupported() {
        try {
            return WindowManagerGlobal.getWindowManagerService().isTaskSnapshotSupported();
        } catch (RemoteException e) {
        }
        return false;
    }

    @Override
    public void registerTaskFpsCallback(@IntRange(from = 0) int taskId, @NonNull Executor executor,
            TaskFpsCallback callback) {
        final OnFpsCallbackListenerProxy onFpsCallbackListenerProxy =
                new OnFpsCallbackListenerProxy(executor, callback);
        try {
            WindowManagerGlobal.getWindowManagerService().registerTaskFpsCallback(
                    taskId, onFpsCallbackListenerProxy);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        synchronized (mOnFpsCallbackListenerProxies) {
            mOnFpsCallbackListenerProxies.add(onFpsCallbackListenerProxy);
        }
    }

    @Override
    public void unregisterTaskFpsCallback(TaskFpsCallback callback) {
        synchronized (mOnFpsCallbackListenerProxies) {
            final Iterator<OnFpsCallbackListenerProxy> iterator =
                    mOnFpsCallbackListenerProxies.iterator();
            while (iterator.hasNext()) {
                final OnFpsCallbackListenerProxy proxy = iterator.next();
                if (proxy.mCallback == callback) {
                    try {
                        WindowManagerGlobal.getWindowManagerService()
                                .unregisterTaskFpsCallback(proxy);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    iterator.remove();
                }
            }
        }
    }

    private static class OnFpsCallbackListenerProxy
            extends ITaskFpsCallback.Stub {
        private final Executor mExecutor;
        private final TaskFpsCallback mCallback;

        private OnFpsCallbackListenerProxy(Executor executor, TaskFpsCallback callback) {
            mExecutor = executor;
            mCallback = callback;
        }

        @Override
        public void onFpsReported(float fps) {
            mExecutor.execute(() -> {
                mCallback.onFpsReported(fps);
            });
        }
    }

    @Override
    public Bitmap snapshotTaskForRecents(int taskId) {
        try {
            return WindowManagerGlobal.getWindowManagerService().snapshotTaskForRecents(taskId);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        return null;
    }

    @Override
    @NonNull
    public IBinder getDefaultToken() {
        return mDefaultToken;
    }

    @Override
    @NonNull
    public List<ComponentName> notifyScreenshotListeners(int displayId) {
        try {
            return List.copyOf(WindowManagerGlobal.getWindowManagerService()
                    .notifyScreenshotListeners(displayId));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @Override
    public boolean replaceContentOnDisplayWithMirror(int displayId, @NonNull Window window) {
        View decorView = window.peekDecorView();
        if (decorView == null) {
            Log.e(TAG, "replaceContentOnDisplayWithMirror: Window's decorView was null.");
            return false;
        }

        ViewRootImpl viewRoot = decorView.getViewRootImpl();
        if (viewRoot == null) {
            Log.e(TAG, "replaceContentOnDisplayWithMirror: Window's viewRootImpl was null.");
            return false;
        }

        SurfaceControl sc = viewRoot.getSurfaceControl();
        if (!sc.isValid()) {
            Log.e(TAG, "replaceContentOnDisplayWithMirror: Window's SC is invalid.");
            return false;
        }
        return replaceContentOnDisplayWithSc(displayId, SurfaceControl.mirrorSurface(sc));
    }

    @Override
    public boolean replaceContentOnDisplayWithSc(int displayId, @NonNull SurfaceControl sc) {
        if (!sc.isValid()) {
            Log.e(TAG, "replaceContentOnDisplayWithSc: Invalid SC.");
            return false;
        }

        try {
            return WindowManagerGlobal.getWindowManagerService()
                    .replaceContentOnDisplay(displayId, sc);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        return false;
    }

    @Override
    public void registerTrustedPresentationListener(@NonNull IBinder window,
            @NonNull TrustedPresentationThresholds thresholds, @NonNull Executor executor,
            @NonNull Consumer<Boolean> listener) {
        mGlobal.registerTrustedPresentationListener(window, thresholds, executor, listener);
    }

    @Override
    public void unregisterTrustedPresentationListener(@NonNull Consumer<Boolean> listener) {
        mGlobal.unregisterTrustedPresentationListener(listener);
    }

    @Override
    public void registerBatchedSurfaceControlInputReceiver(int displayId,
            @NonNull IBinder hostToken, @NonNull SurfaceControl surfaceControl,
            @NonNull Choreographer choreographer, @NonNull SurfaceControlInputReceiver receiver) {
        mGlobal.registerBatchedSurfaceControlInputReceiver(displayId,
                new InputTransferToken(hostToken),
                surfaceControl, choreographer, receiver);
    }

    @Override
    public void registerUnbatchedSurfaceControlInputReceiver(
            int displayId, @NonNull IBinder hostToken, @NonNull SurfaceControl surfaceControl,
            @NonNull Looper looper, @NonNull SurfaceControlInputReceiver receiver) {
        mGlobal.registerUnbatchedSurfaceControlInputReceiver(displayId,
                new InputTransferToken(hostToken),
                surfaceControl, looper, receiver);
    }

    @Override
    public void unregisterSurfaceControlInputReceiver(@NonNull SurfaceControl surfaceControl) {
        mGlobal.unregisterSurfaceControlInputReceiver(surfaceControl);
    }

    @Override
    @Nullable
    public IBinder getSurfaceControlInputClientToken(@NonNull SurfaceControl surfaceControl) {
        return mGlobal.getSurfaceControlInputClientToken(surfaceControl);
    }

    @Override
    public @ScreenRecordingState int addScreenRecordingCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull Consumer<@ScreenRecordingState Integer> callback) {
        if (screenRecordingCallbacks()) {
            Objects.requireNonNull(executor, "executor must not be null");
            Objects.requireNonNull(callback, "callback must not be null");
            return ScreenRecordingCallbacks.getInstance().addCallback(executor, callback);
        }
        return SCREEN_RECORDING_STATE_NOT_VISIBLE;
    }

    @Override
    public void removeScreenRecordingCallback(
            @NonNull Consumer<@ScreenRecordingState Integer> callback) {
        if (screenRecordingCallbacks()) {
            Objects.requireNonNull(callback, "callback must not be null");
            ScreenRecordingCallbacks.getInstance().removeCallback(callback);
        }
    }
}
