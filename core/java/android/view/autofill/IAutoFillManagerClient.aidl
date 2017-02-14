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

package android.view.autofill;

import java.util.List;

import android.content.Intent;
import android.content.IntentSender;
import android.view.autofill.AutoFillId;
import android.view.autofill.AutoFillValue;

/**
 * Object running in the application process and responsible for auto-filling it.
 *
 * @hide
 */
oneway interface IAutoFillManagerClient {
    /**
     * Notifies the client when the auto-fill enabled state changed.
     */
    void setState(boolean enabled);

    /**
      * Auto-fills the activity with the contents of a dataset.
      */
    void autoFill(in List<AutoFillId> ids, in List<AutoFillValue> values);

    /**
      * Authenticates a fill response or a data set.
      */
    void authenticate(in IntentSender intent, in Intent fillInIntent);
}
