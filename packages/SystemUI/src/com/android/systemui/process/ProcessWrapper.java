/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.process;

import android.os.Process;
import android.os.UserHandle;

import javax.inject.Inject;

/**
 * A simple wrapper that provides access to process-related details. This facilitates testing by
 * providing a mockable target around these details.
 */
public class ProcessWrapper {
    @Inject
    public ProcessWrapper() {}

    /**
     * Returns {@code true} if System User is running the current process.
     */
    public boolean isSystemUser() {
        return myUserHandle().isSystem();
    }

    /**
     * Returns {@link UserHandle} as returned statically by {@link Process#myUserHandle()}.
     *
     * This should not be used to get the "current" user. This information only applies to the
     * current process, not the current state of SystemUI. Please use
     * {@link com.android.systemui.settings.UserTracker} if you want to learn about the currently
     * active user in SystemUI.
     */
    public UserHandle myUserHandle() {
        return Process.myUserHandle();
    }
}
