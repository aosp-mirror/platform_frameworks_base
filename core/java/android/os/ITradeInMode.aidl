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

package android.os;

/** @hide */
interface ITradeInMode {
    /**
     * Enable adb in limited-privilege trade-in mode. Returns true if trade-in
     * mode was enabled.
     *
     * Trade-in mode can be enabled if the following conditions are all true:
     *   ro.debuggable is 0.
     *   Settings.Global.ADB_ENABLED is 0.
     *   Settings.Global.USER_SETUP_COMPLETE is 0.
     *   Settings.Secure.DEVICE_PROVISIONED is 0.
     *
     * It is stopped automatically when any of the following conditions become
     * true:
     *
     *   Settings.Global.USER_SETUP_COMPLETE is 1.
     *   Settings.Secure.DEVICE_PROVISIONED is 1.
     *   A change in network configuration occurs.
     *   An account is added.
     *
     * ENTER_TRADE_IN_MODE permission is required.
     */
    boolean start();

    /**
     * Returns whether evaluation mode is allowed on this device. It will return
     * false if any kind of device protection (such as FRP) is detected.
     *
     * ENTER_TRADE_IN_MODE permission is required.
     */
    boolean isEvaluationModeAllowed();

    /**
     * Enable full adb access and provision the device. This forces a factory
     * reset on the next boot.
     *
     * This will return false if start() was not called, if factory reset
     * protection is active, or if trade-in mode was disabled due to any of the
     * conditions listed above for start().
     *
     * ENTER_TRADE_IN_MODE permission is required.
     */
    boolean enterEvaluationMode();
}
