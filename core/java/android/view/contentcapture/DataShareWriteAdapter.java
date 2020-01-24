/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;

/** Adapter class used by apps to share data with the Content Capture service. */
public interface DataShareWriteAdapter {

    /** Request has been rejected, because a concurrent data share sessions is in progress. */
    int ERROR_CONCURRENT_REQUEST = 1;

    /** Data share session timed out. */
    int ERROR_UNKNOWN = 2;

    /**
     * Method invoked when the data share session has been started and the app needs to start
     * writing into the file used for sharing.
     *
     * <p>App needs to handle explicitly cases when the file descriptor is closed and handle
     * gracefully if IOExceptions happen.
     *
     * @param destination file descriptor used to write data into
     * @param cancellationSignal cancellation signal that the app can use to subscribe to cancel
     *                           operations.
     */
    void onWrite(@NonNull ParcelFileDescriptor destination,
            @NonNull CancellationSignal cancellationSignal);

    /** Data share sessions has been rejected by the Content Capture service. */
    void onRejected();

    /**
     * Method invoked when an error occurred, for example sessions has not been started or
     * terminated unsuccessfully.
     *
     * @param errorCode the error code corresponding to an ERROR_* value.
     */
    default void onError(int errorCode) {
        /* do nothing - stub */
    }
}
