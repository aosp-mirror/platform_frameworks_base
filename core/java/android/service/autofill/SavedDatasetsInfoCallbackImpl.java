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

package android.service.autofill;

import static android.service.autofill.AutofillService.EXTRA_ERROR;
import static android.service.autofill.AutofillService.EXTRA_RESULT;

import static com.android.internal.util.Preconditions.checkArgumentInRange;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.os.IResultReceiver;

import java.util.Set;

final class SavedDatasetsInfoCallbackImpl implements SavedDatasetsInfoCallback {
    private static final String TAG = "AutofillService";

    @NonNull
    private final IResultReceiver mReceiver;
    @NonNull
    private final String mType;

    /**
     * Creates a {@link SavedDatasetsInfoCallback} that returns the {@link
     * SavedDatasetsInfo#getCount() number} of saved datasets of {@code type} to the {@code
     * receiver}.
     */
    SavedDatasetsInfoCallbackImpl(@NonNull IResultReceiver receiver, @NonNull String type) {
        mReceiver = requireNonNull(receiver);
        mType = requireNonNull(type);
    }

    @Override
    public void onSuccess(@NonNull Set<SavedDatasetsInfo> results) {
        requireNonNull(results);
        if (results.isEmpty()) {
            send(1, null);
            return;
        }
        int count = -1;
        for (SavedDatasetsInfo info : results) {
            if (mType.equals(info.getType())) {
                count = info.getCount();
            }
        }
        if (count < 0) {
            send(1, null);
            return;
        }
        Bundle bundle = new Bundle(/* capacity= */ 1);
        bundle.putInt(EXTRA_RESULT, count);
        send(0, bundle);
    }

    @Override
    public void onError(@Error int error) {
        checkArgumentInRange(error, ERROR_OTHER, ERROR_NEEDS_USER_ACTION, "error");
        Bundle bundle = new Bundle(/* capacity= */ 1);
        bundle.putInt(EXTRA_ERROR, error);
        send(1, bundle);
    }

    private void send(int resultCode, Bundle bundle) {
        try {
            mReceiver.send(resultCode, bundle);
        } catch (DeadObjectException e) {
            Log.w(TAG, "Failed to send onSavedPasswordCountRequest result: " + e);
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }
}
