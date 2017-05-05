/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.timezone;

/**
 * An easy-to-mock interface around intent sending / receiving for use by {@link PackageTracker};
 * it is not possible to test various cases with the real one because of the need to simulate
 * receiving and broadcasting intents.
 */
interface IntentHelper {

    void initialize(String updateAppPackageName, String dataAppPackageName, Listener listener);

    void sendTriggerUpdateCheck(CheckToken checkToken);

    void enableReliabilityTriggering();

    void disableReliabilityTriggering();

    interface Listener {
        void triggerUpdateIfNeeded(boolean packageUpdated);
    }
}
