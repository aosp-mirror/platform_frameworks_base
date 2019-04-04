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

package android.net.ipmemorystore;

import android.net.ipmemorystore.Blob;
import android.net.ipmemorystore.StatusParcelable;

/** {@hide} */
oneway interface IOnBlobRetrievedListener {
    /**
     * Private data was retrieved for the L2 key and name specified.
     * Note this does not return the client ID, as clients are expected to only ever use one ID.
     */
     void onBlobRetrieved(in StatusParcelable status, in String l2Key, in String name,
             in Blob data);
}
