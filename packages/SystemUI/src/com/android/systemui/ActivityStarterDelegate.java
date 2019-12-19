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
import android.view.View;

import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.phone.StatusBar;

import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Lazy;

/**
 * Single common instance of ActivityStarter that can be gotten and referenced from anywhere, but
 * delegates to an actual implementation (StatusBar).
 */
@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@Singleton
public class ActivityStarterDelegate implements ActivityStarter {

    private Optional<Lazy<StatusBar>> mActualStarter;

    @Inject
    public ActivityStarterDelegate(Optional<Lazy<StatusBar>> statusBar) {
        mActualStarter = statusBar;
    }

    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent intent) {
        mActualStarter.ifPresent(
                starter -> starter.get().startPendingIntentDismissingKeyguard(intent));
    }

    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent intent,
            Runnable intentSentCallback) {
        mActualStarter.ifPresent(
                starter -> starter.get().startPendingIntentDismissingKeyguard(intent,
                        intentSentCallback));
    }

    @Override
    public void startPendingIntentDismissingKeyguard(PendingIntent intent,
            Runnable intentSentCallback, View associatedView) {
        mActualStarter.ifPresent(
                starter -> starter.get().startPendingIntentDismissingKeyguard(intent,
                        intentSentCallback, associatedView));
    }

    @Override
    public void startActivity(Intent intent, boolean onlyProvisioned, boolean dismissShade,
            int flags) {
        mActualStarter.ifPresent(
                starter -> starter.get().startActivity(intent, onlyProvisioned, dismissShade,
                        flags));
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade) {
        mActualStarter.ifPresent(starter -> starter.get().startActivity(intent, dismissShade));
    }

    @Override
    public void startActivity(Intent intent, boolean onlyProvisioned, boolean dismissShade) {
        mActualStarter.ifPresent(
                starter -> starter.get().startActivity(intent, onlyProvisioned, dismissShade));
    }

    @Override
    public void startActivity(Intent intent, boolean dismissShade, Callback callback) {
        mActualStarter.ifPresent(
                starter -> starter.get().startActivity(intent, dismissShade, callback));
    }

    @Override
    public void postStartActivityDismissingKeyguard(Intent intent, int delay) {
        mActualStarter.ifPresent(
                starter -> starter.get().postStartActivityDismissingKeyguard(intent, delay));
    }

    @Override
    public void postStartActivityDismissingKeyguard(PendingIntent intent) {
        mActualStarter.ifPresent(
                starter -> starter.get().postStartActivityDismissingKeyguard(intent));
    }

    @Override
    public void postQSRunnableDismissingKeyguard(Runnable runnable) {
        mActualStarter.ifPresent(
                starter -> starter.get().postQSRunnableDismissingKeyguard(runnable));
    }

    @Override
    public void dismissKeyguardThenExecute(OnDismissAction action, Runnable cancel,
            boolean afterKeyguardGone) {
        mActualStarter.ifPresent(starter -> starter.get().dismissKeyguardThenExecute(action, cancel,
                afterKeyguardGone));
    }
}
