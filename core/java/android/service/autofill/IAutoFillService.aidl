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

import android.app.assist.AssistStructure;
import android.os.Bundle;
import android.service.autofill.IAutoFillServerCallback;
import com.android.internal.os.IResultReceiver;

/**
 * @hide
 */
// TODO(b/33197203): document class and methods
oneway interface IAutoFillService {
    // TODO(b/33197203): rename method to make them more consistent
    void autoFill(in AssistStructure structure, in IAutoFillServerCallback callback, int flags);
    void authenticateFillResponse(in Bundle extras, int flags);
    void authenticateDataset(in Bundle extras, int flags);
    void onConnected();
    void onDisconnected();
}
