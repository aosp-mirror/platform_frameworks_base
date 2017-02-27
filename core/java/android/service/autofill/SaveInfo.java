/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.view.autofill.Helper.DEBUG;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.view.autofill.AutoFillId;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;

/**
 * Information used to indicate that a service is interested on saving the user-inputed data for
 * future use.
 *
 * <p>A {@link SaveInfo} is always associated with a {@link FillResponse}.
 *
 * <p>A {@link SaveInfo} must define the type it represents, and contain at least one
 * {@code savableId}. A {@code savableId} is the {@link AutoFillId} of a view the service is
 * interested to save in a {@code onSaveRequest()}; the ids of all {@link Dataset} present in the
 * {@link FillResponse} associated with this {@link SaveInfo} are already marked as savable,
 * but additional ids can be added through {@link Builder#addSavableIds(AutoFillId...)}.
 *
 * <p>See {@link AutoFillService#onSaveRequest(android.app.assist.AssistStructure, Bundle,
 * SaveCallback)} and {@link FillResponse} for more info.
 */
public final class SaveInfo implements Parcelable {

    /**
     * Type used on when the service can save the contents of an activity, but cannot describe what
     * the content is for.
     */
    public static final int SAVE_DATA_TYPE_GENERIC = 0;

    /**
     * Type used when the {@link FillResponse} represents user credentials that have a password.
     */
    public static final int SAVE_DATA_TYPE_PASSWORD = 1;


    /**
     * Type used on when the {@link FillResponse} represents a physical address (such as street,
     * city, state, etc).
     */
    public static final int SAVE_DATA_TYPE_ADDRESS = 2;

    /**
     * Type used when the {@link FillResponse} represents a credit card.
     */
    public static final int SAVE_DATA_TYPE_CREDIT_CARD = 3;

    private final @SaveDataType int mType;
    private ArraySet<AutoFillId> mSavableIds;
    private final CharSequence mDescription;

    /** @hide */
    @IntDef({
        SAVE_DATA_TYPE_GENERIC,
        SAVE_DATA_TYPE_PASSWORD,
        SAVE_DATA_TYPE_ADDRESS,
        SAVE_DATA_TYPE_CREDIT_CARD
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SaveDataType {
    }

    private SaveInfo(Builder builder) {
        mType = builder.mType;
        mSavableIds = builder.mSavableIds;
        mDescription = builder.mDescription;
    }

    /** @hide */
    public @Nullable ArraySet<AutoFillId> getSavableIds() {
        return mSavableIds;
    }

    /** @hide */
    public int getType() {
        return mType;
    }

    /** @hide */
    public CharSequence getDescription() {
        return mDescription;
    }

    /** @hide */
    public void addSavableIds(@Nullable ArrayList<Dataset> datasets) {
        if (datasets != null) {
            for (Dataset dataset : datasets) {
                final ArrayList<AutoFillId> ids = dataset.getFieldIds();
                if (ids != null) {
                    final int fieldCount = ids.size();
                    for (int i = 0; i < fieldCount; i++) {
                        final AutoFillId id = ids.get(i);
                        if (mSavableIds == null) {
                            mSavableIds = new ArraySet<>();
                        }
                        mSavableIds.add(id);
                    }
                }
            }
        }
    }

    /**
     * A builder for {@link SaveInfo} objects.
     */
    public static final class Builder {

        private final @SaveDataType int mType;
        private ArraySet<AutoFillId> mSavableIds;
        private CharSequence mDescription;
        private boolean mDestroyed;

        /**
         * Creates a new builder.
         *
         * @param type the type of information the associated {@link FillResponse} represents. Must
         * be {@link SaveInfo#SAVE_DATA_TYPE_GENERIC}, {@link SaveInfo#SAVE_DATA_TYPE_PASSWORD},
         * {@link SaveInfo#SAVE_DATA_TYPE_ADDRESS}, or {@link SaveInfo#SAVE_DATA_TYPE_CREDIT_CARD};
         * otherwise it will assume {@link SaveInfo#SAVE_DATA_TYPE_GENERIC}.
         */
        public Builder(@SaveDataType int type) {
            switch (type) {
                case SAVE_DATA_TYPE_PASSWORD:
                case SAVE_DATA_TYPE_ADDRESS:
                case SAVE_DATA_TYPE_CREDIT_CARD:
                    mType = type;
                    break;
                default:
                    mType = SAVE_DATA_TYPE_GENERIC;
            }
        }

        /**
         * Adds ids of additional views the service would be interested to save, but were not
         * indirectly set through {@link FillResponse.Builder#addDataset(Dataset)}.
         *
         * @param ids The savable ids.
         * @return This builder.
         *
         * @see FillResponse
         */
        public @NonNull Builder addSavableIds(@Nullable AutoFillId... ids) {
            throwIfDestroyed();

            if (ids == null) {
                return this;
            }
            for (AutoFillId id : ids) {
                if (mSavableIds == null) {
                    mSavableIds = new ArraySet<>();
                }
                mSavableIds.add(id);
            }
            return this;
        }

        /**
         * Sets an optional description to be shown in the UI when the user is asked to save.
         *
         * <p>Typically, it describes how the data will be stored by the service, so it can help
         * users to decide whether they can trust the service to save their data.
         *
         * @param description a succint description.
         * @return This Builder.
         */
        public @NonNull Builder setDescription(@Nullable CharSequence description) {
            mDescription = description;
            return this;
        }

        /**
         * Builds a new {@link SaveInfo} instance.
         */
        public SaveInfo build() {
            throwIfDestroyed();
            mDestroyed = true;
            return new SaveInfo(this);
        }

        private void throwIfDestroyed() {
            if (mDestroyed) {
                throw new IllegalStateException("Already called #build()");
            }
        }

    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!DEBUG) return super.toString();

        return new StringBuilder("SaveInfo: [type=").append(mType)
                .append(", savableIds=").append(mSavableIds)
                .append("]").toString();
    }

    /////////////////////////////////////
    // Parcelable "contract" methods. //
    /////////////////////////////////////

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mType);
        parcel.writeTypedArraySet(mSavableIds, flags);
        parcel.writeCharSequence(mDescription);
    }

    public static final Parcelable.Creator<SaveInfo> CREATOR = new Parcelable.Creator<SaveInfo>() {
        @Override
        public SaveInfo createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final Builder builder = new Builder(parcel.readInt());
            final ArraySet<AutoFillId> savableIds = parcel.readTypedArraySet(null);
            final int savableIdsCount = (savableIds != null) ? savableIds.size() : 0;
            for (int i = 0; i < savableIdsCount; i++) {
                builder.addSavableIds(savableIds.valueAt(i));
            }
            builder.setDescription(parcel.readCharSequence());
            return builder.build();
        }

        @Override
        public SaveInfo[] newArray(int size) {
            return new SaveInfo[size];
        }
    };
}
