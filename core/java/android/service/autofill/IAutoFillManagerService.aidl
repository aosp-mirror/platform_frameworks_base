/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.service.autofill;

import android.os.Bundle;

/**
 * Mediator between apps being auto-filled and auto-fill service implementations.
 *
 * {@hide}
 */
interface IAutoFillManagerService {

    /**
     * Request auto-fill on the top activity of a given user.
     *
     * @param userId user handle.
     * @param activityToken optional token of activity that needs to be on top.
     *
     * @return whether the request succeeded  (for example, if the activity's
     *         user does not have an auto-fill service associated with, it will return false).
     */
    boolean requestAutoFill(int userId, IBinder activityToken);
}
