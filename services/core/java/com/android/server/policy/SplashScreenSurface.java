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

import android.view.View;
import android.view.WindowManagerPolicy;
import android.view.WindowManagerPolicy.StartingSurface;

import com.android.internal.policy.DecorView;
import com.android.internal.policy.PhoneWindow;

/**
 * Holds the contents of a splash screen starting window, i.e. the {@link DecorView} of a
 * {@link PhoneWindow}. This is just a wrapper such that we can return it from
 * {@link WindowManagerPolicy#addSplashScreen}.
 */
class SplashScreenSurface implements StartingSurface {

    final View view;

    SplashScreenSurface(View view) {
        this.view = view;
    }
}
