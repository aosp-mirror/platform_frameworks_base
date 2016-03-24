/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

/**
 * Constants
 */
public class Constants {

    // TODO: Move into RecentsMetrics
    public static class Metrics {
        // DO NOT MODIFY THE ORDER OF THESE METRICS
        public static final int DismissSourceKeyboard = 0;
        public static final int DismissSourceSwipeGesture = 1;
        public static final int DismissSourceHeaderButton = 2;
        @Deprecated
        public static final int DismissSourceHistorySwipeGesture = 3;
    }

}
