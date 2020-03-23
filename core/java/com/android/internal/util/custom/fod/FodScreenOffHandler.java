/*
 * Copyright (C) 2020 The PixelExperience Project
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

package com.android.internal.util.custom.fod;

public interface FodScreenOffHandler {

    /**
     * Invoked when screen state changes
     *
     * @param interactive Whether device is interactive
     */
    public void onScreenStateChanged(boolean interactive);

    /**
     * Invoked when fingerprint running state changes
     *
     * @param interactive Whether is listening for finger
     */
    public void onFingerprintRunningStateChanged(boolean running);

    /**
     * Invoked when dreaming state changes
     *
     * @param dreaming Whether device is dreaming
     */
    public void onDreamingStateChanged(boolean dreaming);
}
