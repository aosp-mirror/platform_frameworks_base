/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.window;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Non-default ahead-of-time system OnBackInvokedCallback.
 * @hide
 */
public interface SystemOverrideOnBackInvokedCallback extends OnBackInvokedCallback {
    /**
     * No override request
     */
    int OVERRIDE_UNDEFINED = 0;

    /**
     * Navigating back will bring the task to back
     */
    int OVERRIDE_MOVE_TASK_TO_BACK = 1;

    /**
     * Navigating back will finish activity, and remove the task if this activity is root activity.
     */
    int OVERRIDE_FINISH_AND_REMOVE_TASK = 2;

    /** @hide */
    @IntDef({
            OVERRIDE_UNDEFINED,
            OVERRIDE_MOVE_TASK_TO_BACK,
            OVERRIDE_FINISH_AND_REMOVE_TASK,
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface OverrideBehavior {
    }

    /**
     * @return Override type of this callback.
     */
    @OverrideBehavior
    default int overrideBehavior() {
        return OVERRIDE_UNDEFINED;
    }
}
