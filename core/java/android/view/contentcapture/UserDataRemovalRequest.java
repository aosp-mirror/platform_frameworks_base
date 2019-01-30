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

import android.annotation.NonNull;
import android.app.ActivityThread;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.IntArray;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * Class used by apps to request the Content Capture service to remove user-data associated with
 * some context.
 */
public final class UserDataRemovalRequest implements Parcelable {

    private final String mPackageName;

    private final boolean mForEverything;
    private ArrayList<UriRequest> mUriRequests;

    private UserDataRemovalRequest(@NonNull Builder builder) {
        mPackageName = ActivityThread.currentActivityThread().getApplication().getPackageName();
        mForEverything = builder.mForEverything;
        if (builder.mUris != null) {
            final int size = builder.mUris.size();
            mUriRequests = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                mUriRequests.add(new UriRequest(builder.mUris.get(i),
                        builder.mRecursive.get(i) == 1));
            }
        }
    }

    private UserDataRemovalRequest(@NonNull Parcel parcel) {
        mPackageName = parcel.readString();
        mForEverything = parcel.readBoolean();
        if (!mForEverything) {
            final int size = parcel.readInt();
            mUriRequests = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                mUriRequests.add(new UriRequest((Uri) parcel.readValue(null),
                        parcel.readBoolean()));
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
     * Checks if app is requesting to remove all user data associated with its package.
     */
    public boolean isForEverything() {
        return mForEverything;
    }

    /**
     * Gets the list of {@code Uri}s the apps is requesting to remove.
     */
    @NonNull
    public List<UriRequest> getUriRequests() {
        return mUriRequests;
    }

    /**
     * Builder for {@link UserDataRemovalRequest} objects.
     */
    public static final class Builder {

        private boolean mForEverything;
        private ArrayList<Uri> mUris;
        private IntArray mRecursive;

        private boolean mDestroyed;

        /**
         * Requests servive to remove all user data associated with the app's package.
         *
         * @return this builder
         */
        @NonNull
        public Builder forEverything() {
            throwIfDestroyed();
            if (mUris != null) {
                throw new IllegalStateException("Already added Uris");
            }

            mForEverything = true;
            return this;
        }

        /**
         * Request service to remove data associated with a given {@link Uri}.
         *
         * @param uri URI being requested to be removed.
         * @param recursive whether it should remove the data associated with just the URI or its
         * tree of descendants.
         *
         * @return this builder
         */
        public Builder addUri(@NonNull Uri uri, boolean recursive) {
            throwIfDestroyed();
            if (mForEverything) {
                throw new IllegalStateException("Already is for everything");
            }
            Preconditions.checkNotNull(uri);

            if (mUris == null) {
                mUris = new ArrayList<>();
                mRecursive = new IntArray();
            }

            mUris.add(uri);
            mRecursive.add(recursive ? 1 : 0);
            return this;
        }

        /**
         * Builds the {@link UserDataRemovalRequest}.
         */
        @NonNull
        public UserDataRemovalRequest build() {
            throwIfDestroyed();

            Preconditions.checkState(mForEverything || mUris != null);

            mDestroyed = true;
            return new UserDataRemovalRequest(this);
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
            final int size = mUriRequests.size();
            parcel.writeInt(size);
            for (int i = 0; i < size; i++) {
                final UriRequest request = mUriRequests.get(i);
                parcel.writeValue(request.getUri());
                parcel.writeBoolean(request.isRecursive());
            }
        }
    }

    public static final Parcelable.Creator<UserDataRemovalRequest> CREATOR =
            new Parcelable.Creator<UserDataRemovalRequest>() {

        @Override
        public UserDataRemovalRequest createFromParcel(Parcel parcel) {
            return new UserDataRemovalRequest(parcel);
        }

        @Override
        public UserDataRemovalRequest[] newArray(int size) {
            return new UserDataRemovalRequest[size];
        }
    };

    /**
     * Representation of a request to remove data associated with an {@link Uri}.
     */
    public final class UriRequest {
        private final @NonNull Uri mUri;
        private final boolean mRecursive;

        private UriRequest(@NonNull Uri uri, boolean recursive) {
            this.mUri = uri;
            this.mRecursive = recursive;
        }

        /**
         * Gets the URI per se.
         */
        @NonNull
        public Uri getUri() {
            return mUri;
        }

        /**
         * Checks whether the request is to remove just the data associated with the URI per se, or
         * also its descendants.
         */
        @NonNull
        public boolean isRecursive() {
            return mRecursive;
        }
    }
}
