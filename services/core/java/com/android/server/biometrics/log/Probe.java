/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.biometrics.log;

/**
 * Probe for loggable attributes that can be continuously monitored, such as ambient light.
 *
 * Disable probes when the sensors are in states that are not interesting for monitoring
 * purposes to save power.
 */
public interface Probe {
    /** Ensure the probe is actively sampling for new data. */
    void enable();
    /** Stop sampling data. */
    void disable();
    /** Same as {@link #disable()} and ignores all future calls to this probe. */
    void destroy();
}
