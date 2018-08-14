/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.recents.misc;

import android.content.Context;

import com.android.systemui.shared.system.TaskStackChangeListener;

/**
 * An implementation of {@link TaskStackChangeListener}.
 */
public abstract class SysUiTaskStackChangeListener extends TaskStackChangeListener {

    /**
     * Checks that the current user matches the user's SystemUI process.
     */
    protected final boolean checkCurrentUserId(Context context, boolean debug) {
        int currentUserId = SystemServicesProxy.getInstance(context).getCurrentUser();
        return checkCurrentUserId(currentUserId, debug);
    }
}
