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

import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
import static android.view.View.SYSTEM_UI_FLAG_VISIBLE;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.LAST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
import static android.window.WindowProviderService.isWindowProviderService;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiContext;
import android.app.ResourcesManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.StrictMode;
import android.window.WindowContext;
import android.window.WindowProvider;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IResultReceiver;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

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

    public WindowManagerImpl(Context context) {
        this(context, null /* parentWindow */, null /* clientToken */);
    }

    private WindowManagerImpl(Context context, Window parentWindow,
            @Nullable IBinder windowContextToken) {
        mContext = context;
        mParentWindow = parentWindow;
        mWindowContextToken = windowContextToken;
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
                        resultData.getParcelableArrayList(PARCEL_KEY_SHORTCUTS_ARRAY);
                receiver.onKeyboardShortcutsReceived(result);
            }
        };
        try {
            WindowManagerGlobal.getWindowManagerService()
                .requestAppKeyboardShortcuts(resultReceiver, deviceId);
        } catch (RemoteException e) {
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
    public WindowMetrics getCurrentWindowMetrics() {
        final Context context = mParentWindow != null ? mParentWindow.getContext() : mContext;
        final Rect bounds = getCurrentBounds(context);

        return new WindowMetrics(bounds, computeWindowInsets(bounds));
    }

    private static Rect getCurrentBounds(Context context) {
        synchronized (ResourcesManager.getInstance()) {
            return context.getResources().getConfiguration().windowConfiguration.getBounds();
        }
    }

    @Override
    public WindowMetrics getMaximumWindowMetrics() {
        final Context context = mParentWindow != null ? mParentWindow.getContext() : mContext;
        final Rect maxBounds = getMaximumBounds(context);

        return new WindowMetrics(maxBounds, computeWindowInsets(maxBounds));
    }

    private static Rect getMaximumBounds(Context context) {
        synchronized (ResourcesManager.getInstance()) {
            return context.getResources().getConfiguration().windowConfiguration.getMaxBounds();
        }
    }

    // TODO(b/150095967): Set window type to LayoutParams
    private WindowInsets computeWindowInsets(Rect bounds) {
        // Initialize params which used for obtaining all system insets.
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.flags = FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        final Context context = (mParentWindow != null) ? mParentWindow.getContext() : mContext;
        params.token = Context.getToken(context);
        params.systemUiVisibility = SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        params.setFitInsetsTypes(0);
        params.setFitInsetsSides(0);

        return getWindowInsetsFromServer(params, bounds);
    }

    private WindowInsets getWindowInsetsFromServer(WindowManager.LayoutParams attrs, Rect bounds) {
        try {
            final InsetsState insetsState = new InsetsState();
            final boolean alwaysConsumeSystemBars = WindowManagerGlobal.getWindowManagerService()
                    .getWindowInsets(attrs, mContext.getDisplayId(), insetsState);
            final Configuration config = mContext.getResources().getConfiguration();
            final boolean isScreenRound = config.isScreenRound();
            final int windowingMode = config.windowConfiguration.getWindowingMode();
            return insetsState.calculateInsets(bounds, null /* ignoringVisibilityState*/,
                    isScreenRound, alwaysConsumeSystemBars, SOFT_INPUT_ADJUST_NOTHING, attrs.flags,
                    SYSTEM_UI_FLAG_VISIBLE, attrs.type, windowingMode, null /* typeSideMap */);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
    public boolean isTaskSnapshotSupported() {
        try {
            return WindowManagerGlobal.getWindowManagerService().isTaskSnapshotSupported();
        } catch (RemoteException e) {
        }
        return false;
    }
}
