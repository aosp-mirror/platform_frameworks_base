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
import static android.service.autofill.AutoFillService.TAG;

import android.app.Activity;
import android.app.assist.AssistStructure.ViewNode;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Handles auto-fill requests from the {@link AutoFillService} into the {@link Activity} being
 * auto-filled.
 */
public final class FillCallback {

    private final IAutoFillCallback mCallback;

    /** @hide */
    FillCallback(IBinder binder) {
        mCallback = IAutoFillCallback.Stub.asInterface(binder);
    }

    /**
     * Auto-fills the {@link Activity}.
     *
     * @throws RuntimeException if an error occurred while auto-filling it.
     */
    public void onSuccess(FillData data) {
        if (DEBUG) Log.d(TAG, "onSuccess(): data=" + data);

        Preconditions.checkArgument(data != null, "data cannot be null");

        try {
            mCallback.autofill(data.asList());
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
    }

    /**
     * Notifies the activity that the auto-fill request failed.
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

    /**
     * Data used to fill the fields of an {@link Activity}.
     *
     * <p>This class is immutable.
     */
    public static final class FillData {

        private final List<FillableInputField> mList;

        private FillData(Builder builder) {
            final int size = builder.mFields.size();
            final List<FillableInputField> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                list.add(builder.mFields.valueAt(i));
            }
            mList = Collections.unmodifiableList(list);
            // TODO: use FastImmutableArraySet or a similar structure instead?
        }

        /**
         * Gets the response as a {@code List} so it can be used in a binder call.
         */
        List<FillableInputField> asList() {
            return mList;
        }

        @Override
        public String toString() {
            return "[AutoFillResponse: " + mList + "]";
        }

        /**
         * Builder for {@link FillData} objects.
         *
         * <p>Typical usage:
         *
         * <pre class="prettyprint">
         * FillCallback.FillData data = new FillCallback.FillData.Builder()
         *     .setTextField(id1, "value 1")
         *     .setTextField(id2, "value 2")
         *     .build()
         * </pre>
         */
        public static class Builder {
            private final SparseArray<FillableInputField> mFields = new SparseArray<>();

            /**
             * Auto-fills a text field.
             *
             * @param id view id as returned by {@link ViewNode#getAutoFillId()}.
             * @param text text to be auto-filled.
             * @return same builder so it can be chained.
             */
            public Builder setTextField(int id, String text) {
                mFields.put(id, FillableInputField.forText(id, text));
                return this;
            }

            /**
             * Builds a new {@link FillData} instance.
             */
            public FillData build() {
                return new FillData(this);
            }
        }
    }
}
