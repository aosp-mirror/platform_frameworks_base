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

package com.android.systemui.plugins.doze;

import android.app.PendingIntent;
import android.content.Context;

import com.android.systemui.plugins.Plugin;
import com.android.systemui.plugins.annotations.ProvidesInterface;

/**
 * Provides a {@link DozeUi}.
 */
@ProvidesInterface(action = DozeProvider.ACTION, version = DozeProvider.VERSION)
public interface DozeProvider extends Plugin {

    String ACTION = "com.android.systemui.action.PLUGIN_DOZE";
    int VERSION = 1;

    /**
     * Caution: Even if this is called, the DozeUi provided may still be in use until it transitions
     * to DozeState.FINISH
     */
    @Override
    default void onDestroy() {
    }

    /**
     * @return the plugin's implementation of DozeUi.
     */
    DozeUi provideDozeUi(Context context, DozeMachine machine, WakeLock wakeLock);

    /**
     * If true, the plugin allows the default pulse triggers to fire, otherwise they are disabled.
     */
    default boolean allowDefaultPulseTriggers() {
        return false;
    }

    /**
     * Ui for use in DozeMachine.
     */
    interface DozeUi {
        /** Called whenever the DozeMachine state transitions */
        void transitionTo(DozeState oldState, DozeState newState);
    }

    /** WakeLock wrapper for testability */
    interface WakeLock {
        /** @see android.os.PowerManager.WakeLock#acquire() */
        void acquire();
        /** @see android.os.PowerManager.WakeLock#release() */
        void release();
        /** @see android.os.PowerManager.WakeLock#wrap(Runnable) */
        Runnable wrap(Runnable r);
    }

    /** Plugin version of the DozeMachine's state */
    enum DozeState {
        /** Default state. Transition to INITIALIZED to get Doze going. */
        UNINITIALIZED,
        /** Doze components are set up. Followed by transition to DOZE or DOZE_AOD. */
        INITIALIZED,
        /** Regular doze. Device is asleep and listening for pulse triggers. */
        DOZE,
        /** Always-on doze. Device is asleep, showing UI and listening for pulse triggers. */
        DOZE_AOD,
        /** Pulse has been requested. Device is awake and preparing UI */
        DOZE_REQUEST_PULSE,
        /** Pulse is showing. Device is awake and showing UI. */
        DOZE_PULSING,
        /** Pulse is done showing. Followed by transition to DOZE or DOZE_AOD. */
        DOZE_PULSE_DONE,
        /** Doze is done. DozeService is finished. */
        FINISH,
        /** WakeUp. */
        WAKE_UP,
    }

    /** Plugin interface for the doze machine. */
    interface DozeMachine {
        /** Request that the DozeMachine transitions to {@code state} */
        void requestState(DozeState state);

        /** Request that the PendingIntent is sent. */
        void requestSendIntent(PendingIntent intent);
    }
}
