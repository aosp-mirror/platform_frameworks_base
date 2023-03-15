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

import static com.android.systemui.biometrics.SideFpsControllerKt.hasSideFpsSensor;

import android.annotation.Nullable;
import android.hardware.fingerprint.FingerprintManager;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import com.android.keyguard.KeyguardHostView;
import com.android.keyguard.KeyguardSecurityContainer;
import com.android.keyguard.KeyguardSecurityViewFlipper;
import com.android.systemui.R;
import com.android.systemui.biometrics.SideFpsController;
import com.android.systemui.dagger.qualifiers.RootView;
import com.android.systemui.statusbar.phone.KeyguardBouncer;

import java.util.Optional;

import javax.inject.Provider;

import dagger.Module;
import dagger.Provides;

/**
 * Module to create and access view related to the {@link KeyguardBouncer}.
 */
@Module
public interface KeyguardBouncerModule {

    /** */
    @Provides
    @KeyguardBouncerScope
    static KeyguardHostView providesKeyguardHostView(@RootView ViewGroup rootView,
            LayoutInflater layoutInflater) {
        KeyguardHostView hostView = (KeyguardHostView) layoutInflater.inflate(
                R.layout.keyguard_host_view, rootView, false);
        rootView.addView(hostView);
        return hostView;
    }

    /** */
    @Provides
    @KeyguardBouncerScope
    static KeyguardSecurityContainer providesKeyguardSecurityContainer(KeyguardHostView hostView) {
        return hostView.findViewById(R.id.keyguard_security_container);
    }

    /** */
    @Provides
    @KeyguardBouncerScope
    static KeyguardSecurityViewFlipper providesKeyguardSecurityViewFlipper(
            KeyguardSecurityContainer containerView) {
        return containerView.findViewById(R.id.view_flipper);
    }

    /** Provides {@link SideFpsController} if the device has the side fingerprint sensor. */
    @Provides
    @KeyguardBouncerScope
    static Optional<SideFpsController> providesOptionalSidefpsController(
            @Nullable FingerprintManager fingerprintManager,
            Provider<SideFpsController> sidefpsControllerProvider) {
        if (!hasSideFpsSensor(fingerprintManager)) {
            return Optional.empty();
        }
        return Optional.of(sidefpsControllerProvider.get());
    }
}
