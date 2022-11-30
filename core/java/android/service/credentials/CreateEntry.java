/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.credentials;

import android.annotation.NonNull;
import android.app.PendingIntent;
import android.app.slice.Slice;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * An entry to be shown on the UI. This entry represents where the credential to be created will
 * be stored. Examples include user's account, family group etc.
 */
public final class CreateEntry implements Parcelable {
    private final @NonNull Slice mSlice;
    private final @NonNull PendingIntent mPendingIntent;

    private CreateEntry(@NonNull Parcel in) {
        mSlice = in.readTypedObject(Slice.CREATOR);
        mPendingIntent = in.readTypedObject(PendingIntent.CREATOR);
    }

    public static final @NonNull Creator<CreateEntry> CREATOR = new Creator<CreateEntry>() {
        @Override
        public CreateEntry createFromParcel(@NonNull Parcel in) {
            return new CreateEntry(in);
        }

        @Override
        public CreateEntry[] newArray(int size) {
            return new CreateEntry[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mSlice, flags);
        dest.writeTypedObject(mPendingIntent, flags);
    }

    /**
     * Constructs a CreateEntry to be displayed on the UI.
     *
     * @param slice the display content to be displayed on the UI, along with this entry
     * @param pendingIntent the intent to be invoked when the user selects this entry
     */
    public CreateEntry(
            @NonNull Slice slice,
            @NonNull PendingIntent pendingIntent) {
        this.mSlice = slice;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSlice);
        this.mPendingIntent = pendingIntent;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mPendingIntent);
    }

    /** Returns the content to be displayed with this create entry on the UI. */
    public @NonNull Slice getSlice() {
        return mSlice;
    }

    /** Returns the pendingIntent to be invoked when this create entry on the UI is selectcd. */
    public @NonNull PendingIntent getPendingIntent() {
        return mPendingIntent;
    }
}
