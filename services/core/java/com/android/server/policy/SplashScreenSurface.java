/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.policy;

import static com.android.server.policy.PhoneWindowManager.DEBUG_SPLASH_SCREEN;

import android.os.Debug;
import android.os.IBinder;
import android.util.Slog;
import android.view.View;
import android.view.WindowManager;

import com.android.internal.policy.DecorView;
import com.android.internal.policy.PhoneWindow;
import com.android.server.policy.WindowManagerPolicy.StartingSurface;

/**
 * Holds the contents of a splash screen starting window, i.e. the {@link DecorView} of a
 * {@link PhoneWindow}. This is just a wrapper such that we can return it from
 * {@link WindowManagerPolicy#addSplashScreen}.
 */
class SplashScreenSurface implements StartingSurface {

    private static final String TAG = PhoneWindowManager.TAG;
    private final View mView;
    private final IBinder mAppToken;

    SplashScreenSurface(View view, IBinder appToken) {
        mView = view;
        mAppToken = appToken;
    }

    @Override
    public void remove(boolean animate) {
        if (DEBUG_SPLASH_SCREEN) Slog.v(TAG, "Removing splash screen window for " + mAppToken + ": "
                        + this + " Callers=" + Debug.getCallers(4));

        final WindowManager wm = mView.getContext().getSystemService(WindowManager.class);
        wm.removeView(mView);
    }
}
