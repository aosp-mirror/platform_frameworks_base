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

import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.view.autofill.AutoFillId;
import android.view.autofill.AutoFillValue;

/**
 * Mediator between apps being auto-filled and auto-fill service implementations.
 *
 * {@hide}
 */
oneway interface IAutoFillManagerService {
    // Methods called by AutoFillManager
    void startSession(in IBinder activityToken, in IBinder appCallback, in AutoFillId autoFillId,
                      in Rect bounds, in AutoFillValue value);
    void updateSession(in IBinder activityToken, in AutoFillId id, in Rect bounds,
                       in AutoFillValue value, int flags);
    void finishSession(in IBinder activityToken);
}
