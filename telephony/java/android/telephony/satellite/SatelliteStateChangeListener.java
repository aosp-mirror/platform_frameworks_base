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

package android.telephony.satellite;

import android.annotation.FlaggedApi;

import com.android.internal.telephony.flags.Flags;

import java.util.concurrent.Executor;

/**
 * A listener interface to monitor satellite state change events.
 *
 * <p>Call
 * {@link SatelliteManager#registerStateChangeListener(Executor, SatelliteStateChangeListener)}
 * to monitor. Call
 * {@link SatelliteManager#unregisterStateChangeListener(SatelliteStateChangeListener)} to cancel.
 *
 * @see SatelliteManager#registerStateChangeListener(Executor, SatelliteStateChangeListener)
 * @see SatelliteManager#unregisterStateChangeListener(SatelliteStateChangeListener)
 */
@FlaggedApi(Flags.FLAG_SATELLITE_STATE_CHANGE_LISTENER)
public interface SatelliteStateChangeListener {
    /**
     * Called when satellite modem enabled state may have changed.
     *
     * <p>Note:there is no guarantee that this callback will only be invoked upon a change of state.
     * In other word, in some cases, the callback may report with the same enabled states. It is the
     * caller's responsibility to filter uninterested states.
     *
     * <p>Note:satellite enabled state is a device state that is NOT associated with subscription or
     * SIM slot.
     *
     * @param isEnabled {@code true} means satellite modem is enabled.
     */
    void onEnabledStateChanged(boolean isEnabled);
}
