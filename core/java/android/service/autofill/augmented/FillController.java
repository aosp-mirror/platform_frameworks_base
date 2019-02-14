/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.service.autofill.augmented;

import static android.service.autofill.augmented.AugmentedAutofillService.DEBUG;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.RemoteException;
import android.service.autofill.augmented.AugmentedAutofillService.AutofillProxy;
import android.util.Log;
import android.util.Pair;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import com.android.internal.util.Preconditions;

import java.util.List;

/**
 * Object used to interact with the autofill system.
 *
 * @hide
 */
@SystemApi
@TestApi
public final class FillController {
    private static final String TAG = FillController.class.getSimpleName();

    private final AutofillProxy mProxy;

    FillController(@NonNull AutofillProxy proxy) {
        mProxy = proxy;
    }

    /**
     * Fills the activity with the provided values.
     *
     * <p>As a side effect, the {@link FillWindow} associated with the {@link FillResponse} will be
     * automatically {@link FillWindow#destroy() destroyed}.
     */
    public void autofill(@NonNull List<Pair<AutofillId, AutofillValue>> values) {
        Preconditions.checkNotNull(values);

        if (DEBUG) {
            Log.d(TAG, "autofill() with " + values.size() + " values");
        }

        try {
            mProxy.autofill(values);
            final FillWindow fillWindow = mProxy.getFillWindow();
            if (fillWindow != null) {
                fillWindow.destroy();
            }
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }
}
