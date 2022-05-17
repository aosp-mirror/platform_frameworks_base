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
package android.view.contentcapture;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.app.ActivityThread;
import android.content.LocusId;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.IntArray;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Class used by apps to request the content capture service to remove data associated with
 * {@link LocusId LocusIds}.
 *
 * <p>An app which has tagged data with a LocusId can therefore delete them later. This is intended
 * to let apps propagate deletions of user data into the operating system.
 */
public final class DataRemovalRequest implements Parcelable {

    /**
     * When set, service should use the {@link LocusId#getId()} as prefix for the data to be
     * removed.
     */
    public static final int FLAG_IS_PREFIX = 0x1;

    /** @hide */
    @IntDef(prefix = { "FLAG" }, flag = true, value = {
            FLAG_IS_PREFIX
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface Flags {}

    private final String mPackageName;

    private final boolean mForEverything;
    private ArrayList<LocusIdRequest> mLocusIdRequests;

    private DataRemovalRequest(@NonNull Builder builder) {
        mPackageName = ActivityThread.currentActivityThread().getApplication().getPackageName();
        mForEverything = builder.mForEverything;
        if (builder.mLocusIds != null) {
            final int size = builder.mLocusIds.size();
            mLocusIdRequests = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                mLocusIdRequests.add(new LocusIdRequest(builder.mLocusIds.get(i),
                        builder.mFlags.get(i)));
            }
        }
    }

    private DataRemovalRequest(@NonNull Parcel parcel) {
        mPackageName = parcel.readString();
        mForEverything = parcel.readBoolean();
        if (!mForEverything) {
            final int size = parcel.readInt();
            mLocusIdRequests = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                mLocusIdRequests.add(new LocusIdRequest((LocusId) parcel.readValue(null),
                        parcel.readInt()));
            }
        }
    }

    /**
     * Gets the name of the app that's making the request.
     */
    @NonNull
    public String getPackageName() {
        return mPackageName;
    }

    /**
     * Checks if app is requesting to remove content capture data associated with its package.
     */
    public boolean isForEverything() {
        return mForEverything;
    }

    /**
     * Gets the list of {@code LousId}s the apps is requesting to remove.
     */
    @NonNull
    public List<LocusIdRequest> getLocusIdRequests() {
        return mLocusIdRequests;
    }

    /**
     * Builder for {@link DataRemovalRequest} objects.
     */
    public static final class Builder {

        private boolean mForEverything;
        private ArrayList<LocusId> mLocusIds;
        private IntArray mFlags;

        private boolean mDestroyed;

        /**
         * Requests servive to remove all content capture data associated with the app's package.
         *
         * @return this builder
         */
        @NonNull
        public Builder forEverything() {
            throwIfDestroyed();
            Preconditions.checkState(mLocusIds == null, "Already added LocusIds");

            mForEverything = true;
            return this;
        }

        /**
         * Request service to remove data associated with a given {@link LocusId}.
         *
         * @param locusId the {@link LocusId} being requested to be removed.
         * @param flags either {@link DataRemovalRequest#FLAG_IS_PREFIX} or {@code 0}
         *
         * @return this builder
         */
        @NonNull
        public Builder addLocusId(@NonNull LocusId locusId, @Flags int flags) {
            throwIfDestroyed();
            Preconditions.checkState(!mForEverything, "Already is for everything");
            Objects.requireNonNull(locusId);
            // felipeal: check flags

            if (mLocusIds == null) {
                mLocusIds = new ArrayList<>();
                mFlags = new IntArray();
            }

            mLocusIds.add(locusId);
            mFlags.add(flags);
            return this;
        }

        /**
         * Builds the {@link DataRemovalRequest}.
         */
        @NonNull
        public DataRemovalRequest build() {
            throwIfDestroyed();

            Preconditions.checkState(mForEverything || mLocusIds != null,
                    "must call either #forEverything() or add one #addLocusId()");

            mDestroyed = true;
            return new DataRemovalRequest(this);
        }

        private void throwIfDestroyed() {
            Preconditions.checkState(!mDestroyed, "Already destroyed!");
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeString(mPackageName);
        parcel.writeBoolean(mForEverything);
        if (!mForEverything) {
            final int size = mLocusIdRequests.size();
            parcel.writeInt(size);
            for (int i = 0; i < size; i++) {
                final LocusIdRequest request = mLocusIdRequests.get(i);
                parcel.writeValue(request.getLocusId());
                parcel.writeInt(request.getFlags());
            }
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<DataRemovalRequest> CREATOR =
            new Parcelable.Creator<DataRemovalRequest>() {

        @Override
        @NonNull
        public DataRemovalRequest createFromParcel(Parcel parcel) {
            return new DataRemovalRequest(parcel);
        }

        @Override
        @NonNull
        public DataRemovalRequest[] newArray(int size) {
            return new DataRemovalRequest[size];
        }
    };

    /**
     * Representation of a request to remove data associated with a {@link LocusId}.
     */
    public final class LocusIdRequest {
        private final @NonNull LocusId mLocusId;
        private final @Flags int mFlags;

        private LocusIdRequest(@NonNull LocusId locusId, @Flags int flags) {
            this.mLocusId = locusId;
            this.mFlags = flags;
        }

        /**
         * Gets the {@code LocusId} per se.
         */
        @NonNull
        public LocusId getLocusId() {
            return mLocusId;
        }

        /**
         * Gets the flags associates with request.
         *
         * @return either {@link DataRemovalRequest#FLAG_IS_PREFIX} or {@code 0}.
         */
        @NonNull
        public @Flags int getFlags() {
            return mFlags;
        }
    }
}
