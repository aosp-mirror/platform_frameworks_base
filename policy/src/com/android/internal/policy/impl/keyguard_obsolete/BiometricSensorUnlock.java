/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.internal.policy.impl.keyguard_obsolete;

import android.view.View;

interface BiometricSensorUnlock {
    /**
     * Initializes the view provided for the biometric unlock UI to work within.  The provided area
     * completely covers the backup unlock mechanism.
     * @param biometricUnlockView View provided for the biometric unlock UI.
     */
    public void initializeView(View biometricUnlockView);

    /**
     * Indicates whether the biometric unlock is running.  Before
     * {@link BiometricSensorUnlock#start} is called, isRunning() returns false.  After a successful
     * call to {@link BiometricSensorUnlock#start}, isRunning() returns true until the biometric
     * unlock completes, {@link BiometricSensorUnlock#stop} has been called, or an error has
     * forced the biometric unlock to stop.
     * @return whether the biometric unlock is currently running.
     */
    public boolean isRunning();

    /**
     * Covers the backup unlock mechanism by showing the contents of the view initialized in
     * {@link BiometricSensorUnlock#initializeView(View)}.  The view should disappear after the
     * specified timeout.  If the timeout is 0, the interface shows until another event, such as
     * calling {@link BiometricSensorUnlock#hide()}, causes it to disappear.  Called on the UI
     * thread.
     * @param timeoutMilliseconds Amount of time in milliseconds to display the view before
     * disappearing.  A value of 0 means the view should remain visible.
     */
    public void show(long timeoutMilliseconds);

    /**
     * Uncovers the backup unlock mechanism by hiding the contents of the view initialized in
     * {@link BiometricSensorUnlock#initializeView(View)}.
     */
    public void hide();

    /**
     * Binds to the biometric unlock service and starts the unlock procedure.  Called on the UI
     * thread.
     * @return false if it can't be started or the backup should be used.
     */
    public boolean start();

    /**
     * Stops the biometric unlock procedure and unbinds from the service.  Called on the UI thread.
     * @return whether the biometric unlock was running when called.
     */
    public boolean stop();

    /**
     * Cleans up any resources used by the biometric unlock.
     */
    public void cleanUp();

    /**
     * Gets the Device Policy Manager quality of the biometric unlock sensor
     * (e.g., PASSWORD_QUALITY_BIOMETRIC_WEAK).
     * @return biometric unlock sensor quality, as defined by Device Policy Manager.
     */
    public int getQuality();
}
