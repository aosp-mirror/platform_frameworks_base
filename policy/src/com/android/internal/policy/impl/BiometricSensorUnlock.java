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

package com.android.internal.policy.impl;

import android.view.View;

interface BiometricSensorUnlock {
    // Returns 'true' if the biometric sensor is available and is selected by user.
    public boolean installedAndSelected();

    // Returns 'true' if the biometric sensor has started its unlock procedure but has not yet
    // accepted or rejected the user.
    public boolean isRunning();

    // Show the interface, but don't start the unlock procedure.  The interface should disappear
    // after the specified timeout.  If the timeout is 0, the interface shows until another event,
    // such as calling hide(), causes it to disappear.
    // Called on the UI Thread
    public void show(long timeoutMilliseconds);

    // Hide the interface, if any, exposing the lockscreen.
    public void hide();

    // Stop the unlock procedure if running.  Returns 'true' if it was in fact running.
    public boolean stop();

    // Start the unlock procedure.  Returns ‘false’ if it can’t be started or if the backup should
    // be used.
    // Called on the UI thread.
    public boolean start(boolean suppressBiometricUnlock);

    // Provide a view to work within.
    public void initializeAreaView(View topView);

    // Clean up any resources used by the biometric unlock.
    public void cleanUp();

    // Returns the Device Policy Manager quality (e.g. PASSWORD_QUALITY_BIOMETRIC_WEAK).
    public int getQuality();
}
