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

package com.android.systemui.dreams.touch.scrim;

import android.os.PowerManager;
import android.os.SystemClock;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.unfold.util.CallbackController;

import java.util.HashSet;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * {@link BouncerlessScrimController} handles scrim progression when no keyguard is set. When
 * fully expanded, the controller dismisses the dream.
 */
@SysUISingleton
public class BouncerlessScrimController implements ScrimController,
        CallbackController<BouncerlessScrimController.Callback> {
    private static final String TAG = "BLScrimController";

    /**
     * {@link Callback} allows {@link BouncerlessScrimController} clients to be informed of
     * expansion progression and wakeup
     */
    public interface Callback {
        /**
         * Invoked when there is a change to the scrim expansion.
         */
        void onExpansion(ShadeExpansionChangeEvent event);

        /**
         * Invoked after {@link BouncerlessScrimController} has started waking up the device.
         */
        void onWakeup();
    }

    private final Executor mExecutor;
    private final PowerManager mPowerManager;

    @Override
    public void addCallback(Callback listener) {
        mExecutor.execute(() -> mCallbacks.add(listener));
    }

    @Override
    public void removeCallback(Callback listener) {
        mExecutor.execute(() -> mCallbacks.remove(listener));
    }

    private final HashSet<Callback> mCallbacks;


    @Inject
    public BouncerlessScrimController(@Main Executor executor,
            PowerManager powerManager) {
        mExecutor = executor;
        mPowerManager = powerManager;
        mCallbacks = new HashSet<>();
    }

    @Override
    public void expand(ShadeExpansionChangeEvent event) {
        if (event.getExpanded())  {
            mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_GESTURE,
                    "com.android.systemui:SwipeUp");
            mExecutor.execute(() -> mCallbacks.forEach(callback -> callback.onWakeup()));
        } else {
            mExecutor.execute(() -> mCallbacks.forEach(callback -> callback.onExpansion(event)));
        }
    }
}
