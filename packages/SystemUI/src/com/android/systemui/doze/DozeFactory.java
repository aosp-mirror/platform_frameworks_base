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
 * limitations under the License.
 */

package com.android.systemui.doze;

import android.app.Application;
import android.content.Context;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.statusbar.phone.DozeParameters;

public class DozeFactory {

    /** Creates a DozeMachine with its parts for {@code dozeService}. */
    public static DozeMachine assembleMachine(DozeService dozeService) {
        Context context = dozeService;
        SensorManager sensorManager = context.getSystemService(SensorManager.class);
        PowerManager powerManager = context.getSystemService(PowerManager.class);

        DozeHost host = getHost(dozeService);
        AmbientDisplayConfiguration config = new AmbientDisplayConfiguration(context);
        DozeParameters params = new DozeParameters(context);
        Handler handler = new Handler();
        DozeFactory.WakeLock wakeLock = new DozeFactory.WakeLock(powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "Doze"));

        DozeMachine machine = new DozeMachine(dozeService, params, wakeLock);
        machine.setParts(new DozeMachine.Part[]{
                new DozeTriggers(context, machine, host, config, params,
                        sensorManager, handler, wakeLock),
                new DozeUi(context, machine, wakeLock, host),
        });

        return machine;
    }

    private static DozeHost getHost(DozeService service) {
        Application appCandidate = service.getApplication();
        final SystemUIApplication app = (SystemUIApplication) appCandidate;
        return app.getComponent(DozeHost.class);
    }

    /** A wrapper around {@link PowerManager.WakeLock} for testability. */
    public static class WakeLock {
        private final PowerManager.WakeLock mInner;

        public WakeLock(PowerManager.WakeLock inner) {
            mInner = inner;
        }

        /** @see PowerManager.WakeLock#acquire() */
        public void acquire() {
            mInner.acquire();
        }

        /** @see PowerManager.WakeLock#release() */
        public void release() {
            mInner.release();
        }

        /** @see PowerManager.WakeLock#wrap(Runnable) */
        public Runnable wrap(Runnable runnable) {
            return mInner.wrap(runnable);
        }
    }
}
