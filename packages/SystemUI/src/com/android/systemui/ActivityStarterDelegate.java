/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.UserHandle;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.phone.CentralSurfaces;

import java.util.Optional;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * Single common instance of ActivityStarter that can be gotten and referenced from anywhere, but
 * delegates to an actual implementation (CentralSurfaces).
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@SysUISingleton
public class ActivityStarterDelegate implements ActivityStarter {

    private Lazy<Optional<CentralSurfaces>> mActualStarterOptionalLazy;

    @Inject
    public ActivityStarterDelegate(Lazy<Optional<CentralSurfaces>> centralSurfacesOptionalLazy) {
        mActualStarterOptionalLazy = centralSurfacesOptionalLazy;
    }

    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent intent) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.startPendingIntentDismissingKeyguard(intent));
    }

    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent intent,
            Runnable intentSentUiThreadCallback) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.startPendingIntentDismissingKeyguard(
                        intent, intentSentUiThreadCallback));
    }

    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent intent,
            Runnable intentSentUiThreadCallback, View associatedView) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.startPendingIntentDismissingKeyguard(
                        intent, intentSentUiThreadCallback, associatedView));
    }

    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent intent,
            Runnable intentSentUiThreadCallback,
            ActivityLaunchAnimator.Controller animationController) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.startPendingIntentDismissingKeyguard(
                        intent, intentSentUiThreadCallback, animationController));
    }

    @Override
    public void startActivity(Intent intent, boolean onlyProvisioned, boolean dismissShade,
            int flags) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.startActivity(intent, onlyProvisioned, dismissShade, flags));
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.startActivity(intent, dismissShade));
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade,
            @Nullable ActivityLaunchAnimator.Controller animationController,
            boolean showOverLockscreenWhenLocked) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.startActivity(intent, dismissShade, animationController,
                    showOverLockscreenWhenLocked));
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade,
            @Nullable ActivityLaunchAnimator.Controller animationController,
            boolean showOverLockscreenWhenLocked, UserHandle userHandle) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.startActivity(intent, dismissShade, animationController,
                    showOverLockscreenWhenLocked, userHandle));
    }

    @Override
    public void startActivity(Intent intent, boolean onlyProvisioned, boolean dismissShade) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.startActivity(intent, onlyProvisioned, dismissShade));
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade, Callback callback) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.startActivity(intent, dismissShade, callback));
    }

    @Override
    public void postStartActivityDismissingKeyguard(Intent intent, int delay) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.postStartActivityDismissingKeyguard(intent, delay));
    }

    @Override
    public void postStartActivityDismissingKeyguard(Intent intent, int delay,
            @Nullable ActivityLaunchAnimator.Controller animationController) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.postStartActivityDismissingKeyguard(
                        intent, delay, animationController));
    }

    @Override
    public void postStartActivityDismissingKeyguard(PendingIntent intent) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.postStartActivityDismissingKeyguard(intent));
    }

    @Override
    public void postStartActivityDismissingKeyguard(PendingIntent intent,
            ActivityLaunchAnimator.Controller animationController) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.postStartActivityDismissingKeyguard(
                        intent, animationController));
    }

    @Override
    public void postQSRunnableDismissingKeyguard(Runnable runnable) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.postQSRunnableDismissingKeyguard(runnable));
    }

    @Override
    public void dismissKeyguardThenExecute(OnDismissAction action, Runnable cancel,
            boolean afterKeyguardGone) {
        mActualStarterOptionalLazy.get().ifPresent(
                starter -> starter.dismissKeyguardThenExecute(action, cancel, afterKeyguardGone));
    }
}
