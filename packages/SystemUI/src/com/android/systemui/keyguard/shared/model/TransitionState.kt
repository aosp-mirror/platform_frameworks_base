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
 * limitations under the License
 */
package com.android.systemui.keyguard.shared.model

/** Possible states for a running transition between [State] */
enum class TransitionState {
    /* Transition has begun. */
    STARTED {
        override fun isTransitioning() = true
    },
    /* Transition is actively running. */
    RUNNING {
        override fun isTransitioning() = true
    },
    /* Transition has completed successfully. */
    FINISHED {
        override fun isTransitioning() = false
    },
    /* Transition has been interrupted, and not completed successfully. */
    CANCELED {
        override fun isTransitioning() = false
    };

    abstract fun isTransitioning(): Boolean
}
