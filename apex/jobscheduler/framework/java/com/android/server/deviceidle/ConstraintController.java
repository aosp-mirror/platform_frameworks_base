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

package com.android.server.deviceidle;

/**
 * Device idle constraints for a specific form factor or use-case.
 */
public interface ConstraintController {
    /**
     * Begin any general continuing work and register all constraints.
     */
    void start();

    /**
     * Unregister all constraints and stop any general work.
     */
    void stop();
}
