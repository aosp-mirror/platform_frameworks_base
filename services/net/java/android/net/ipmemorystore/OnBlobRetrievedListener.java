/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net.ipmemorystore;

import android.annotation.NonNull;

/**
 * A listener for the IpMemoryStore to return a blob.
 * @hide
 */
public interface OnBlobRetrievedListener {
    /**
     * The memory store has come up with the answer to a query that was sent.
     */
    void onBlobRetrieved(Status status, String l2Key, String name, Blob blob);

    /** Converts this OnBlobRetrievedListener to a parcelable object */
    @NonNull
    static IOnBlobRetrievedListener toAIDL(@NonNull final OnBlobRetrievedListener listener) {
        return new IOnBlobRetrievedListener.Stub() {
            @Override
            public void onBlobRetrieved(final StatusParcelable statusParcelable, final String l2Key,
                    final String name, final Blob blob) {
                // NonNull, but still don't crash the system server if null
                if (null != listener) {
                    listener.onBlobRetrieved(new Status(statusParcelable), l2Key, name, blob);
                }
            }

            @Override
            public int getInterfaceVersion() {
                return this.VERSION;
            }
        };
    }
}
