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

import static android.service.autofill.AutoFillService.DEBUG;

import android.app.Activity;
import android.app.assist.AssistStructure.ViewNode;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.util.Preconditions;

/**
 * Handles save requests from the {@link AutoFillService} into the {@link Activity} being
 * auto-filled.
 */
public final class SaveCallback {

    private static final String TAG = "SaveCallback";

    private final IAutoFillCallback mCallback;

    /** @hide */
    SaveCallback(IBinder binder) {
        mCallback = IAutoFillCallback.Stub.asInterface(binder);
    }

    /**
     * Notifies the {@link Activity} that the save request succeeded.
     *
     * @param ids ids ({@link ViewNode#getAutoFillId()}) of the fields that were saved.
     *
     * @throws RuntimeException if an error occurred while saving the data.
     */
    public void onSuccess(int[] ids) {
        Preconditions.checkArgument(ids != null, "ids cannot be null");

        Preconditions.checkArgument(ids.length > 0, "ids cannot be empty");

        if (DEBUG) Log.d(TAG, "onSuccess(): ids=" + ids.length);

        // TODO(b/33197203): display which ids were saved
    }

    /**
     * Notifies the {@link Activity} that the save request failed.
     *
     * @param message error message to be displayed.
     *
     * @throws RuntimeException if an error occurred while notifying the activity.
     */
    public void onFailure(CharSequence message) {
        if (DEBUG) Log.d(TAG, "onFailure(): message=" + message);

        Preconditions.checkArgument(message != null, "message cannot be null");

        try {
            mCallback.showError(message.toString());
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }
}
