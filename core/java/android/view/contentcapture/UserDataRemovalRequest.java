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
import android.annotation.SystemApi;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.List;

/**
 * Class used by apps to request the Content Capture service to remove user-data associated with
 * some context.
 */
public final class UserDataRemovalRequest implements Parcelable {

    private UserDataRemovalRequest(Builder builder) {
        // TODO(b/111276913): implement
    }

    /**
     * Gets the name of the app that's making the request.
     * @hide
     */
    @SystemApi
    @NonNull
    public String getPackageName() {
        // TODO(b/111276913): implement
        // TODO(b/111276913): make sure it's set on system_service so it cannot be faked by app
        return null;
    }

    /**
     * Checks if app is requesting to remove all user data associated with its package.
     *
     * @hide
     */
    @SystemApi
    public boolean isForEverything() {
        // TODO(b/111276913): implement
        return false;
    }

    /**
     * Gets the list of {@code Uri}s the apps is requesting to remove.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public List<UriRequest> getUriRequests() {
        // TODO(b/111276913): implement
        return null;
    }

    /**
     * Builder for {@link UserDataRemovalRequest} objects.
     */
    public static final class Builder {

        /**
         * Requests servive to remove all user data associated with the app's package.
         *
         * @return this builder
         */
        @NonNull
        public Builder forEverything() {
            // TODO(b/111276913): implement
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
            // TODO(b/111276913): implement
            return this;
        }

        /**
         * Builds the {@link UserDataRemovalRequest}.
         */
        @NonNull
        public UserDataRemovalRequest build() {
            // TODO(b/111276913): implement / unit test / check built / document exceptions
            return null;
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        // TODO(b/111276913): implement
    }

    public static final Parcelable.Creator<UserDataRemovalRequest> CREATOR =
            new Parcelable.Creator<UserDataRemovalRequest>() {

        @Override
        public UserDataRemovalRequest createFromParcel(Parcel parcel) {
            // TODO(b/111276913): implement
            return null;
        }

        @Override
        public UserDataRemovalRequest[] newArray(int size) {
            return new UserDataRemovalRequest[size];
        }
    };

    /**
     * Representation of a request to remove data associated with an {@link Uri}.
     * @hide
     */
    @SystemApi
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
