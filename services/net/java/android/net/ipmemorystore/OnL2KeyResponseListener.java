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
 * A listener for the IpMemoryStore to return a L2 key.
 * @hide
 */
public interface OnL2KeyResponseListener {
    /**
     * The operation has completed with the specified status.
     */
    void onL2KeyResponse(Status status, String l2Key);

    /** Converts this OnL2KeyResponseListener to a parcelable object */
    @NonNull
    static IOnL2KeyResponseListener toAIDL(@NonNull final OnL2KeyResponseListener listener) {
        return new IOnL2KeyResponseListener.Stub() {
            @Override
            public void onL2KeyResponse(final StatusParcelable statusParcelable,
                    final String l2Key) {
                // NonNull, but still don't crash the system server if null
                if (null != listener) {
                    listener.onL2KeyResponse(new Status(statusParcelable), l2Key);
                }
            }

            @Override
            public int getInterfaceVersion() {
                return this.VERSION;
            }
        };
    }
}
