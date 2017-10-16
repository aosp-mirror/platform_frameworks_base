/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWallpaperVisibilityListener;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManagerGlobal;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.Dependency;
import com.android.systemui.R;

public final class NavigationBarTransitions extends BarTransitions {

    private final NavigationBarView mView;
    private final IStatusBarService mBarService;
    private final LightBarTransitionsController mLightTransitionsController;
    private boolean mWallpaperVisible;

    private boolean mLightsOut;
    private boolean mAutoDim;

    public NavigationBarTransitions(NavigationBarView view) {
        super(view, R.drawable.nav_background);
        mView = view;
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
        mLightTransitionsController = new LightBarTransitionsController(view.getContext(),
                this::applyDarkIntensity);

        IWindowManager windowManagerService = Dependency.get(IWindowManager.class);
        Handler handler = Handler.getMain();
        try {
            mWallpaperVisible = windowManagerService.registerWallpaperVisibilityListener(
                new IWallpaperVisibilityListener.Stub() {
                    @Override
                    public void onWallpaperVisibilityChanged(boolean newVisibility,
                            int displayId) throws RemoteException {
                        mWallpaperVisible = newVisibility;
                        handler.post(() -> applyLightsOut(true, false));
                    }
                }, Display.DEFAULT_DISPLAY);
        } catch (RemoteException e) {
        }
    }

    public void init() {
        applyModeBackground(-1, getMode(), false /*animate*/);
        applyLightsOut(false /*animate*/, true /*force*/);
    }

    @Override
    public void setAutoDim(boolean autoDim) {
        if (mAutoDim == autoDim) return;
        mAutoDim = autoDim;
        applyLightsOut(true, false);
    }

    @Override
    protected boolean isLightsOut(int mode) {
        return super.isLightsOut(mode) || (mAutoDim && !mWallpaperVisible);
    }

    public LightBarTransitionsController getLightTransitionsController() {
        return mLightTransitionsController;
    }

    @Override
    protected void onTransition(int oldMode, int newMode, boolean animate) {
        super.onTransition(oldMode, newMode, animate);
        applyLightsOut(animate, false /*force*/);
    }

    private void applyLightsOut(boolean animate, boolean force) {
        // apply to lights out
        applyLightsOut(isLightsOut(getMode()), animate, force);
    }

    private void applyLightsOut(boolean lightsOut, boolean animate, boolean force) {
        if (!force && lightsOut == mLightsOut) return;

        mLightsOut = lightsOut;

        final View navButtons = mView.getCurrentView().findViewById(R.id.nav_buttons);

        // ok, everyone, stop it right there
        navButtons.animate().cancel();

        final float navButtonsAlpha = lightsOut ? 0.6f : 1f;

        if (!animate) {
            navButtons.setAlpha(navButtonsAlpha);
        } else {
            final int duration = lightsOut ? LIGHTS_OUT_DURATION : LIGHTS_IN_DURATION;
            navButtons.animate()
                .alpha(navButtonsAlpha)
                .setDuration(duration)
                .start();
        }
    }

    public void reapplyDarkIntensity() {
        applyDarkIntensity(mLightTransitionsController.getCurrentDarkIntensity());
    }

    public void applyDarkIntensity(float darkIntensity) {
        SparseArray<ButtonDispatcher> buttonDispatchers = mView.getButtonDispatchers();
        for (int i = buttonDispatchers.size() - 1; i >= 0; i--) {
            buttonDispatchers.valueAt(i).setDarkIntensity(darkIntensity);
        }
    }

    private final View.OnTouchListener mLightsOutListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                // even though setting the systemUI visibility below will turn these views
                // on, we need them to come up faster so that they can catch this motion
                // event
                applyLightsOut(false, false, false);

                try {
                    mBarService.setSystemUiVisibility(0, View.SYSTEM_UI_FLAG_LOW_PROFILE,
                            "LightsOutListener");
                } catch (android.os.RemoteException ex) {
                }
            }
            return false;
        }
    };
}
