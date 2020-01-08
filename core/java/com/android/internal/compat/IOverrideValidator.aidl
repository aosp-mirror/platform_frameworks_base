/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.compat;

import android.content.pm.ApplicationInfo;

import com.android.internal.compat.OverrideAllowedState;

/**
 * Platform private API for determining whether a changeId can be overridden.
 *
 * {@hide}
 */
interface IOverrideValidator
{
    /**
     * Validation function.
     * @param changeId    id of the change to be toggled on or off.
     * @param packageName package of the app for which the change should be overridden.
     * @return {@link OverrideAllowedState} specifying whether the change can be overridden for
     * the given package or a reason why not.
     */
    OverrideAllowedState getOverrideAllowedState(long changeId, String packageName);
}
