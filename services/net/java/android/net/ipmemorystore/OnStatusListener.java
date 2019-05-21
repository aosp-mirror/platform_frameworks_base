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
import android.annotation.Nullable;

/**
 * A listener for the IpMemoryStore to return a status to a client.
 * @hide
 */
public interface OnStatusListener {
    /**
     * The operation has completed with the specified status.
     */
    void onComplete(Status status);

    /** Converts this OnStatusListener to a parcelable object */
    @NonNull
    static IOnStatusListener toAIDL(@Nullable final OnStatusListener listener) {
        return new IOnStatusListener.Stub() {
            @Override
            public void onComplete(final StatusParcelable statusParcelable) {
                if (null != listener) {
                    listener.onComplete(new Status(statusParcelable));
                }
            }

            @Override
            public int getInterfaceVersion() {
                return this.VERSION;
            }
        };
    }
}
