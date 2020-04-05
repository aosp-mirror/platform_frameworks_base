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
import static android.view.ViewRootImpl.NEW_INSETS_MODE_FULL;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;

import android.annotation.NonNull;
import android.app.ResourcesManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.internal.os.IResultReceiver;

import java.util.List;

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
    private final Context mContext;
    private final Window mParentWindow;

    private IBinder mDefaultToken;

    public WindowManagerImpl(Context context) {
        this(context, null);
    }

    private WindowManagerImpl(Context context, Window parentWindow) {
        mContext = context;
        mParentWindow = parentWindow;
    }

    public WindowManagerImpl createLocalWindowManager(Window parentWindow) {
        return new WindowManagerImpl(mContext, parentWindow);
    }

    public WindowManagerImpl createPresentationWindowManager(Context displayContext) {
        return new WindowManagerImpl(displayContext, mParentWindow);
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
        applyDefaultToken(params);
        mGlobal.addView(view, params, mContext.getDisplayNoVerify(), mParentWindow);
    }

    @Override
    public void updateViewLayout(@NonNull View view, @NonNull ViewGroup.LayoutParams params) {
        applyDefaultToken(params);
        mGlobal.updateViewLayout(view, params);
    }

    private void applyDefaultToken(@NonNull ViewGroup.LayoutParams params) {
        // Only use the default token if we don't have a parent window.
        if (mDefaultToken != null && mParentWindow == null) {
            if (!(params instanceof WindowManager.LayoutParams)) {
                throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
            }

            // Only use the default token if we don't already have a token.
            final WindowManager.LayoutParams wparams = (WindowManager.LayoutParams) params;
            if (wparams.token == null) {
                wparams.token = mDefaultToken;
            }
        }
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
    public void setShouldShowIme(int displayId, boolean shouldShow) {
        try {
            WindowManagerGlobal.getWindowManagerService().setShouldShowIme(displayId, shouldShow);
        } catch (RemoteException e) {
        }
    }

    @Override
    public boolean shouldShowIme(int displayId) {
        try {
            return WindowManagerGlobal.getWindowManagerService().shouldShowIme(displayId);
        } catch (RemoteException e) {
        }
        return false;
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
        final Rect maxBounds = getMaximumBounds();
        return new WindowMetrics(maxBounds, computeWindowInsets(maxBounds));
    }

    private Rect getMaximumBounds() {
        // TODO(b/128338354): Current maximum bound is display size, but it should be displayArea
        //  bound after displayArea feature is finished.
        final Display display = mContext.getDisplayNoVerify();
        final Point displaySize = new Point();
        display.getRealSize(displaySize);
        return new Rect(0, 0, displaySize.x, displaySize.y);
    }

    // TODO(b/150095967): Set window type to LayoutParams
    private WindowInsets computeWindowInsets(Rect bounds) {
        // Initialize params which used for obtaining all system insets.
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.flags = FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        params.token = (mParentWindow != null) ? mParentWindow.getContext().getActivityToken()
                : mContext.getActivityToken();
        params.systemUiVisibility = SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        params.setFitInsetsTypes(0);
        params.setFitInsetsSides(0);

        return getWindowInsetsFromServer(params, bounds);
    }

    private WindowInsets getWindowInsetsFromServer(WindowManager.LayoutParams attrs, Rect bounds) {
        try {
            final Rect systemWindowInsets = new Rect();
            final Rect stableInsets = new Rect();
            final DisplayCutout.ParcelableWrapper displayCutout =
                    new DisplayCutout.ParcelableWrapper();
            final InsetsState insetsState = new InsetsState();
            final boolean alwaysConsumeSystemBars = WindowManagerGlobal.getWindowManagerService()
                    .getWindowInsets(attrs, mContext.getDisplayId(), systemWindowInsets,
                    stableInsets, displayCutout, insetsState);
            final boolean isScreenRound =
                    mContext.getResources().getConfiguration().isScreenRound();
            if (ViewRootImpl.sNewInsetsMode == NEW_INSETS_MODE_FULL) {
                return insetsState.calculateInsets(bounds, null /* ignoringVisibilityState*/,
                        isScreenRound, alwaysConsumeSystemBars, displayCutout.get(),
                        SOFT_INPUT_ADJUST_NOTHING,
                        SYSTEM_UI_FLAG_VISIBLE, null /* typeSideMap */);
            } else {
                return new WindowInsets.Builder()
                        .setAlwaysConsumeSystemBars(alwaysConsumeSystemBars)
                        .setRound(isScreenRound)
                        .setSystemWindowInsets(Insets.of(systemWindowInsets))
                        .setStableInsets(Insets.of(stableInsets))
                        .setDisplayCutout(displayCutout.get()).build();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
