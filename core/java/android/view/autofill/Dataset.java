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

import static android.view.autofill.Helper.DEBUG;
import static android.view.autofill.Helper.append;

import android.app.Activity;
import android.app.assist.AssistStructure.ViewNode;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.autofill.AutoFillService;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A set of data that can be used to auto-fill an {@link Activity}.
 *
 * <p>It contains:
 *
 * <ol>
 *   <li>A name used to identify the dataset in the UI.
 *   <li>A list of id/value pairs for the fields that can be auto-filled.
 *   <li>An optional {@link Bundle} with extras (used only by the service creating it).
 * </ol>
 *
 * See {@link FillResponse} for examples.
 */
public final class Dataset implements Parcelable {

    private final CharSequence mName;
    private final ArrayList<DatasetField> mFields;
    private final Bundle mExtras;

    private Dataset(Dataset.Builder builder) {
        mName = builder.mName;
        // TODO(b/33197203): make an immutable copy of mFields?
        mFields = builder.mFields;
        mExtras = builder.mExtras;
    }

    /** @hide */
    public CharSequence getName() {
        return mName;
    }

    /** @hide */
    public List<DatasetField> getFields() {
        return mFields;
    }

    /** @hide */
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public String toString() {
        if (!DEBUG) return super.toString();

        final StringBuilder builder = new StringBuilder("Dataset [name=").append(mName)
                .append(", fields=").append(mFields).append(", extras=");
        append(builder, mExtras);
        return builder.append(']').toString();
    }

    /**
     * A builder for {@link Dataset} objects.
     */
    public static final class Builder {
        private CharSequence mName;
        private final ArrayList<DatasetField> mFields = new ArrayList<>();
        private Bundle mExtras;

        /**
         * Creates a new builder.
         *
         * @param name Name used to identify the dataset in the UI. Typically it's the same value as
         * the first field in the dataset (like username or email address) or an user-provided name
         * (like "My Work Address").
         */
        public Builder(CharSequence name) {
            mName = Preconditions.checkStringNotEmpty(name, "name cannot be empty or null");
        }

        /**
         * Sets the value of a field.
         *
         * @param id id returned by {@link ViewNode#getAutoFillId()}.
         * @param value value to be auto filled.
         */
        public Dataset.Builder setValue(AutoFillId id, AutoFillValue value) {
            putField(new DatasetField(id, value));
            return this;
        }

        /**
         * Creates a new {@link Dataset} instance.
         */
        public Dataset build() {
            return new Dataset(this);
        }

        /**
         * Sets a {@link Bundle} that will be passed to subsequent calls to {@link AutoFillService}
         * methods such as
         * {@link AutoFillService#onSaveRequest(android.app.assist.AssistStructure, Bundle,
         * android.os.CancellationSignal, android.service.autofill.SaveCallback)}, using
         * {@link AutoFillService#EXTRA_DATASET_EXTRAS} as the key.
         *
         * <p>It can be used to keep service state in between calls.
         */
        public Builder setExtras(Bundle extras) {
            mExtras = Objects.requireNonNull(extras, "extras cannot be null");
            return this;
        }

        /**
         * Emulates {@code Map.put()} by adding a new field to the list if its id is not the yet,
         * or replacing the existing one.
         */
        private void putField(DatasetField field) {
            // TODO(b/33197203): check if already exists and replaces it if so
            mFields.add(field);
        }
    }

    /////////////////////////////////////
    //  Parcelable "contract" methods. //
    /////////////////////////////////////

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeCharSequence(mName);
        parcel.writeList(mFields);
        parcel.writeBundle(mExtras);
    }

    @SuppressWarnings("unchecked")
    private Dataset(Parcel parcel) {
        mName = parcel.readCharSequence();
        mFields = parcel.readArrayList(null);
        mExtras = parcel.readBundle();
    }

    public static final Parcelable.Creator<Dataset> CREATOR = new Parcelable.Creator<Dataset>() {
        @Override
        public Dataset createFromParcel(Parcel source) {
            return new Dataset(source);
        }

        @Override
        public Dataset[] newArray(int size) {
            return new Dataset[size];
        }
    };
}
