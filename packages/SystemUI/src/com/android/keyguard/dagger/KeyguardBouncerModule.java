/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.keyguard.dagger;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import com.android.keyguard.KeyguardSecurityContainer;
import com.android.keyguard.KeyguardSecurityViewFlipper;
import com.android.systemui.bouncer.domain.interactor.PrimaryBouncerInteractor;
import com.android.systemui.dagger.qualifiers.RootView;
import com.android.systemui.res.R;

import dagger.Module;
import dagger.Provides;

/**
 * Module to create and access view related to the {@link PrimaryBouncerInteractor}.
 */
@Module
public interface KeyguardBouncerModule {

    /** */
    @Provides
    @KeyguardBouncerScope
    static KeyguardSecurityContainer providesKeyguardSecurityContainer(@RootView ViewGroup rootView,
            LayoutInflater layoutInflater) {
        KeyguardSecurityContainer securityContainer =
                (KeyguardSecurityContainer) layoutInflater.inflate(
                        R.layout.keyguard_security_container_view, rootView, false);
        rootView.addView(securityContainer);
        return securityContainer;
    }

    /** */
    @Provides
    @KeyguardBouncerScope
    static KeyguardSecurityViewFlipper providesKeyguardSecurityViewFlipper(
            KeyguardSecurityContainer containerView) {
        return containerView.findViewById(R.id.view_flipper);
    }
}
