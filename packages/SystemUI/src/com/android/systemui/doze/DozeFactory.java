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

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.Context;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.PowerManager;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.plugins.doze.DozeProvider;
import com.android.systemui.statusbar.phone.DozeParameters;

public class DozeFactory {

    private final DozeProvider mDozePlugin;

    public DozeFactory(DozeProvider plugin) {
        mDozePlugin = plugin;
    }

    /** Creates a DozeMachine with its parts for {@code dozeService}. */
    public DozeMachine assembleMachine(DozeService dozeService) {
        Context context = dozeService;
        SensorManager sensorManager = context.getSystemService(SensorManager.class);
        PowerManager powerManager = context.getSystemService(PowerManager.class);
        AlarmManager alarmManager = context.getSystemService(AlarmManager.class);

        DozeHost host = getHost(dozeService);
        AmbientDisplayConfiguration config = new AmbientDisplayConfiguration(context);
        DozeParameters params = new DozeParameters(context);
        Handler handler = new Handler();
        DozeFactory.WakeLock wakeLock = new DozeFactory.WakeLock(powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "Doze"));

        DozeMachine machine = new DozeMachine(
                DozeScreenStatePreventingAdapter.wrapIfNeeded(dozeService, params),
                config,
                wakeLock);
        machine.setParts(new DozeMachine.Part[]{
                createDozeTriggers(context, sensorManager, host, config, params, handler, wakeLock,
                        machine),
                createDozeUi(context, host, wakeLock, machine, handler, alarmManager),
        });

        return machine;
    }

    private DozeTriggers createDozeTriggers(Context context, SensorManager sensorManager,
            DozeHost host, AmbientDisplayConfiguration config, DozeParameters params,
            Handler handler, WakeLock wakeLock, DozeMachine machine) {
        boolean allowPulseTriggers = mDozePlugin == null || mDozePlugin.allowDefaultPulseTriggers();
        return new DozeTriggers(context, machine, host, config, params,
                sensorManager, handler, wakeLock, allowPulseTriggers);
    }

    private DozeMachine.Part createDozeUi(Context context, DozeHost host, WakeLock wakeLock,
            DozeMachine machine, Handler handler, AlarmManager alarmManager) {
        if (mDozePlugin != null) {
            DozeProvider.DozeUi dozeUi = mDozePlugin.provideDozeUi(context,
                    pluginMachine(context, machine, host),
                    wakeLock);
            return (oldState, newState) -> {
                dozeUi.transitionTo(pluginState(oldState),
                        pluginState(newState));
            };
        } else {
            return new DozeUi(context, alarmManager, machine, wakeLock, host, handler);
        }
    }

    private DozeProvider.DozeMachine pluginMachine(Context context, DozeMachine machine,
            DozeHost host) {
        return new DozeProvider.DozeMachine() {
            @Override
            public void requestState(DozeProvider.DozeState state) {
                if (state == DozeProvider.DozeState.WAKE_UP) {
                    machine.wakeUp();
                    return;
                }
                machine.requestState(implState(state));
            }

            @Override
            public void requestSendIntent(PendingIntent intent) {
                host.startPendingIntentDismissingKeyguard(intent);
            }
        };
    }

    private DozeMachine.State implState(DozeProvider.DozeState s) {
        switch (s) {
            case UNINITIALIZED:
                return DozeMachine.State.UNINITIALIZED;
            case INITIALIZED:
                return DozeMachine.State.INITIALIZED;
            case DOZE:
                return DozeMachine.State.DOZE;
            case DOZE_AOD:
                return DozeMachine.State.DOZE_AOD;
            case DOZE_REQUEST_PULSE:
                return DozeMachine.State.DOZE_REQUEST_PULSE;
            case DOZE_PULSING:
                return DozeMachine.State.DOZE_PULSING;
            case DOZE_PULSE_DONE:
                return DozeMachine.State.DOZE_PULSE_DONE;
            case FINISH:
                return DozeMachine.State.FINISH;
            default:
                throw new IllegalArgumentException("Unknown state: " + s);
        }
    }

    private DozeProvider.DozeState pluginState(DozeMachine.State s) {
        switch (s) {
            case UNINITIALIZED:
                return DozeProvider.DozeState.UNINITIALIZED;
            case INITIALIZED:
                return DozeProvider.DozeState.INITIALIZED;
            case DOZE:
                return DozeProvider.DozeState.DOZE;
            case DOZE_AOD:
                return DozeProvider.DozeState.DOZE_AOD;
            case DOZE_REQUEST_PULSE:
                return DozeProvider.DozeState.DOZE_REQUEST_PULSE;
            case DOZE_PULSING:
                return DozeProvider.DozeState.DOZE_PULSING;
            case DOZE_PULSE_DONE:
                return DozeProvider.DozeState.DOZE_PULSE_DONE;
            case FINISH:
                return DozeProvider.DozeState.FINISH;
            default:
                throw new IllegalArgumentException("Unknown state: " + s);
        }
    }

    public static DozeHost getHost(DozeService service) {
        Application appCandidate = service.getApplication();
        final SystemUIApplication app = (SystemUIApplication) appCandidate;
        return app.getComponent(DozeHost.class);
    }

    /** A wrapper around {@link PowerManager.WakeLock} for testability. */
    public static class WakeLock implements DozeProvider.WakeLock {
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
