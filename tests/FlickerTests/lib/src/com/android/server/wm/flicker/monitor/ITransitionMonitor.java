/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.flicker.monitor;

import android.os.Environment;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Collects test artifacts during a UI transition.
 */
public interface ITransitionMonitor {
    Path OUTPUT_DIR = Paths.get(Environment.getExternalStorageDirectory().toString(), "flicker");

    /**
     * Starts monitor.
     */
    void start();

    /**
     * Stops monitor.
     */
    void stop();

    /**
     * Saves any monitor artifacts to file adding {@code testTag} and {@code iteration}
     * to the file name.
     *
     * @param testTag   suffix added to artifact name
     * @param iteration suffix added to artifact name
     *
     * @return Path to saved artifact
     */
    default Path save(String testTag, int iteration) {
        return save(testTag + "_" + iteration);
    }

    /**
     * Saves any monitor artifacts to file adding {@code testTag} to the file name.
     *
     * @param testTag suffix added to artifact name
     *
     * @return Path to saved artifact
     */
    default Path save(String testTag) {
        throw new UnsupportedOperationException("Save not implemented for this monitor");
    }
}
