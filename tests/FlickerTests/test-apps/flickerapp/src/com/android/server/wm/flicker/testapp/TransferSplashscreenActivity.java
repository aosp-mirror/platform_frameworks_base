/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wm.flicker.testapp;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.window.SplashScreen;
import android.window.SplashScreenView;

public class TransferSplashscreenActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final SplashScreen splashScreen = getSplashScreen();
        // Register setOnExitAnimationListener to transfer the splash screen window to client.
        splashScreen.setOnExitAnimationListener(this::onSplashScreenExit);
        final View content = findViewById(android.R.id.content);
        // By register preDrawListener to defer app window draw signal about 500ms, which to ensure
        // the splash screen must show when cold launch.
        content.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    final long mCreateTime = SystemClock.uptimeMillis();
                    @Override
                    public boolean onPreDraw() {
                        return SystemClock.uptimeMillis() - mCreateTime > 500;
                    }
                }
        );
    }

    private void onSplashScreenExit(SplashScreenView view) {
        view.remove();
    }
}
