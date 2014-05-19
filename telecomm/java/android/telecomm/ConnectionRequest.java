/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.telecomm;

import android.os.Bundle;
import android.net.Uri;

/**
 * Simple data container encapsulating a request to some entity to
 * create a new {@link Connection}.
 */
public final class ConnectionRequest {

    // TODO: Token to limit recursive invocations
    // TODO: Consider upgrading "mHandle" to ordered list of handles, indicating a set of phone
    //         numbers that would satisfy the client's needs, in order of preference
    private final Uri mHandle;
    private final Bundle mExtras;

    public ConnectionRequest(Uri handle, Bundle extras) {
        mHandle = handle; mExtras = extras;
    }

    /**
     * The handle (e.g., phone number) to which the {@link Connection} is to connect.
     */
    public Uri getHandle() { return mHandle; }

    /**
     * Application-specific extra data. Used for passing back information from an incoming
     * call {@code Intent}, and for any proprietary extensions arranged between a client
     * and servant {@code ConnectionService} which agree on a vocabulary for such data.
     */
    public Bundle getExtras() { return mExtras; }

    public String toString() {
        return String.format("PhoneConnectionRequest %s %s",
                mHandle == null
                        ? Uri.EMPTY
                        : ConnectionService.toLogSafePhoneNumber(mHandle.toString()),
                mExtras == null ? "" : mExtras);
    }
}
