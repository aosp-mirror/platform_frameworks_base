/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.idle;

import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.shared.system.InputMonitorCompat;

import javax.inject.Inject;

/**
 * {@link InputMonitorFactory} generates instances of {@link InputMonitorCompat}.
 */
public class InputMonitorFactory {
    private final int mDisplayId;

    @Inject
    public InputMonitorFactory(@DisplayId int displayId) {
        mDisplayId = displayId;
    }

    /**
     * Generates a new {@link InputMonitorCompat}.
     *
     * @param identifier Identifier to generate monitor with.
     * @return A {@link InputMonitorCompat} instance.
     */
    public InputMonitorCompat getInputMonitor(String identifier) {
        return new InputMonitorCompat(identifier, mDisplayId);
    }
}
