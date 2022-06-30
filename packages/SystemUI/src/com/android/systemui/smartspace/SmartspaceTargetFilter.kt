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
package com.android.systemui.smartspace

import android.app.smartspace.SmartspaceTarget

/**
 * {@link SmartspaceTargetFilter} defines a way to locally filter targets from inclusion. This
 * should be used for filtering that isn't available further upstream.
 */
interface SmartspaceTargetFilter {
    /**
     * An interface implemented by clients to receive updates when the filtering criteria changes.
     * When this happens, the client should refresh their target set.
     */
    interface Listener {
        fun onCriteriaChanged()
    }

    /**
     * Adds a listener to receive future updates. {@link Listener#onCriteriaChanged} will be
     * invoked immediately after.
     */
    fun addListener(listener: Listener)

    /**
     * Removes listener from receiving future updates.
     */
    fun removeListener(listener: Listener)

    /**
     * Returns {@code true} if the {@link SmartspaceTarget} should be included in the current
     * target set, {@code false} otherwise.
     */
    fun filterSmartspaceTarget(t: SmartspaceTarget): Boolean
}