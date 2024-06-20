/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.ambient.touch.scrim;

import static com.android.systemui.ambient.touch.scrim.dagger.ScrimModule.BOUNCERLESS_SCRIM_CONTROLLER;
import static com.android.systemui.ambient.touch.scrim.dagger.ScrimModule.BOUNCER_SCRIM_CONTROLLER;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.util.HashSet;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * {@link ScrimManager} helps manage multiple {@link ScrimController} instances, specifying the
 * appropriate one to use at the current moment and managing the handoff between controllers.
 */
public class ScrimManager {
    private final ScrimController mBouncerScrimController;
    private final ScrimController mBouncerlessScrimController;
    private final KeyguardStateController mKeyguardStateController;
    private final Executor mExecutor;

    private ScrimController mCurrentController;
    private final HashSet<Callback> mCallbacks;

    /**
     * Interface implemented for receiving updates to the active {@link ScrimController}.
     */
    public interface Callback {
        /**
         * Invoked when the controller changes.
         * @param controller The currently active {@link ScrimController}.
         */
        void onScrimControllerChanged(ScrimController controller);
    }

    private final KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
                @Override
                public void onKeyguardShowingChanged() {
                    mExecutor.execute(() -> updateController());
                }
            };

    @Inject
    ScrimManager(@Main Executor executor,
            @Named(BOUNCER_SCRIM_CONTROLLER) ScrimController bouncerScrimController,
            @Named(BOUNCERLESS_SCRIM_CONTROLLER)ScrimController bouncerlessScrimController,
            KeyguardStateController keyguardStateController) {
        mExecutor = executor;
        mCallbacks = new HashSet<>();
        mBouncerlessScrimController = bouncerlessScrimController;
        mBouncerScrimController = bouncerScrimController;
        mKeyguardStateController = keyguardStateController;

        mKeyguardStateController.addCallback(mKeyguardStateCallback);
        updateController();
    }

    private void updateController() {
        final ScrimController existingController = mCurrentController;
        mCurrentController =  mKeyguardStateController.canDismissLockScreen()
                ? mBouncerlessScrimController
                : mBouncerScrimController;

        if (existingController == mCurrentController) {
            return;
        }

        mCallbacks.forEach(callback -> callback.onScrimControllerChanged(mCurrentController));
    }

    /**
     * Adds a {@link Callback} to receive future changes to the active {@link ScrimController}.
     */
    public void addCallback(Callback callback) {
        mExecutor.execute(() -> mCallbacks.add(callback));
    }

    /**
     * Removes the {@link Callback} from receiving further updates.
     */
    public void removeCallback(Callback callback) {
        mExecutor.execute(() -> mCallbacks.remove(callback));
    }

    /**
     * Returns the currently get {@link ScrimController}.
     * @return
     */
    public ScrimController getCurrentController() {
        return mCurrentController;
    }
}
