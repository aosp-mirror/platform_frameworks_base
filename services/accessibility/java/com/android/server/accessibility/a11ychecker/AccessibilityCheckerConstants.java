/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.accessibility.a11ychecker;

import java.time.Duration;

/**
 * Constants used by the accessibility checker.
 *
 * @hide
 */
final class AccessibilityCheckerConstants {

    // The min required duration between two consecutive runs of the a11y checker.
    static final Duration MIN_DURATION_BETWEEN_CHECKS = Duration.ofMinutes(1);

    // The max number of cached results at a time.
    static final int MAX_CACHE_CAPACITY = 10000;
}
