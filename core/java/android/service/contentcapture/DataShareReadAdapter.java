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

package android.service.contentcapture;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.ParcelFileDescriptor;
import android.view.contentcapture.ContentCaptureManager.DataShareError;

/**
 * Adapter class to be used for the Content Capture Service app to propagate the status of the
 * session
 *
 * @hide
 **/
@SystemApi
@TestApi
public interface DataShareReadAdapter {

    /**
     * Signals the start of the data sharing session.
     *
     * @param fd file descriptor to use for reading data, that's being shared
     **/
    void onStart(@NonNull ParcelFileDescriptor fd);

    /**
     * Signals that the session failed to start or terminated unsuccessfully.
     *
     * <p>Important: together with the error, file sharing stream might be closed, and therefore
     * reading from {@code fd} from {@link #onStart} will result in the end of stream. The order of
     * these 2 events is not defined, and it's important that the service treats end of stream
     * correctly in this situation.
     **/
    void onError(@DataShareError int errorCode);
}
