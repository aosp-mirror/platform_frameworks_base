/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.util.sensors;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * Convenience class allowing for briefly checking the proximity sensor.
 */
public class ProximityCheck implements Runnable {

    private final ProximitySensor mSensor;
    private final DelayableExecutor mDelayableExecutor;
    private List<Consumer<Boolean>> mCallbacks = new ArrayList<>();
    private final ThresholdSensor.Listener mListener;
    private final AtomicBoolean mRegistered = new AtomicBoolean();

    @Inject
    public ProximityCheck(
            ProximitySensor sensor,
            @Main DelayableExecutor delayableExecutor) {
        mSensor = sensor;
        mSensor.setTag("prox_check");
        mDelayableExecutor = delayableExecutor;
        mListener = this::onProximityEvent;
    }

    /** Set a descriptive tag for the sensors registration. */
    public void setTag(String tag) {
        mSensor.setTag(tag);
    }

    @Override
    public void run() {
        unregister();
        onProximityEvent(null);
    }

    /**
     * Query the proximity sensor, timing out if no result.
     */
    public void check(long timeoutMs, Consumer<Boolean> callback) {
        if (!mSensor.isLoaded()) {
            callback.accept(null);
            return;
        }
        mCallbacks.add(callback);
        if (!mRegistered.getAndSet(true)) {
            mSensor.register(mListener);
            mDelayableExecutor.executeDelayed(this, timeoutMs);
        }
    }

    /**
     * Cleanup after no longer needed.
     */
    public void destroy() {
        mSensor.destroy();
    }

    private void unregister() {
        mSensor.unregister(mListener);
        mRegistered.set(false);
    }

    private void onProximityEvent(ThresholdSensorEvent proximityEvent) {
        // Move the callbacks to a local to avoid ConcurrentModificationException
        List<Consumer<Boolean>> oldCallbacks = mCallbacks;
        mCallbacks = new ArrayList<>();
        // Unregister from the ProximitySensor to ensure a re-entrant check will re-register
        unregister();
        // Notify the callbacks
        oldCallbacks.forEach(
                booleanConsumer ->
                        booleanConsumer.accept(
                                proximityEvent == null ? null : proximityEvent.getBelow()));
    }
}
