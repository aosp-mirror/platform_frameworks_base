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
 * A listener for the IpMemoryStore to return a response about network sameness.
 * @hide
 */
public interface OnSameL3NetworkResponseListener {
    /**
     * The memory store has come up with the answer to a query that was sent.
     */
    void onSameL3NetworkResponse(Status status, SameL3NetworkResponse response);

    /** Converts this OnSameL3NetworkResponseListener to a parcelable object */
    @NonNull
    static IOnSameL3NetworkResponseListener toAIDL(
            @NonNull final OnSameL3NetworkResponseListener listener) {
        return new IOnSameL3NetworkResponseListener.Stub() {
            @Override
            public void onSameL3NetworkResponse(final StatusParcelable statusParcelable,
                    final SameL3NetworkResponseParcelable sameL3NetworkResponseParcelable) {
                // NonNull, but still don't crash the system server if null
                if (null != listener) {
                    listener.onSameL3NetworkResponse(
                            new Status(statusParcelable),
                            new SameL3NetworkResponse(sameL3NetworkResponseParcelable));
                }
            }

            @Override
            public int getInterfaceVersion() {
                return this.VERSION;
            }
        };
    }
}
