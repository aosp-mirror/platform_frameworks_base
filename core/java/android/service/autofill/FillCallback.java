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

import android.annotation.Nullable;
import android.app.Activity;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.view.autofill.FillResponse;

/**
 * Handles auto-fill requests from the {@link AutoFillService} into the {@link Activity} being
 * auto-filled.
 */
public final class FillCallback implements Parcelable {
    private final IFillCallback mCallback;
    private boolean mCalled;

    /** @hide */
    public FillCallback(IFillCallback callback) {
        mCallback = callback;
    }

    /** @hide */
    private FillCallback(Parcel parcel) {
        mCallback = IFillCallback.Stub.asInterface(parcel.readStrongBinder());
    }

    /**
     * Notifies the Android System that an
     * {@link AutoFillService#onFillRequest(android.app.assist.AssistStructure, Bundle,
     * android.os.CancellationSignal, FillCallback)} was successfully fulfilled by the service.
     *
     * @param response auto-fill information for that activity, or {@code null} when the activity
     * cannot be auto-filled (for example, if it only contains read-only fields). See
     * {@link FillResponse} for examples.
     */
    public void onSuccess(@Nullable FillResponse response) {
        assertNotCalled();
        mCalled = true;
        try {
            mCallback.onSuccess(response);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Notifies the Android System that an
     * {@link AutoFillService#onFillRequest(android.app.assist.AssistStructure,
     * Bundle, android.os.CancellationSignal, FillCallback)}
     * could not be fulfilled by the service.
     *
     * @param message error message to be displayed to the user.
     */
    public void onFailure(@Nullable CharSequence message) {
        assertNotCalled();
        mCalled = true;
        try {
            mCallback.onFailure(message);
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeStrongBinder(mCallback.asBinder());
    }

    private void assertNotCalled() {
        if (mCalled) {
            throw new IllegalStateException("Already called");
        }
    }

    public static final Creator<FillCallback> CREATOR = new Creator<FillCallback>() {
        @Override
        public FillCallback createFromParcel(Parcel parcel) {
            return new FillCallback(parcel);
        }

        @Override
        public FillCallback[] newArray(int size) {
            return new FillCallback[size];
        }
    };
}
