/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.media;

import android.os.IBinder;

/** @hide */
public class MediaHTTPService extends IMediaHTTPService.Stub {
    private static final String TAG = "MediaHTTPService";

    public MediaHTTPService() {
    }

    public IMediaHTTPConnection makeHTTPConnection() {
        return new MediaHTTPConnection();
    }

    /* package private */static IBinder createHttpServiceBinderIfNecessary(
            String path) {
        if (path.startsWith("http://")
                || path.startsWith("https://")
                || path.startsWith("widevine://")) {
            return (new MediaHTTPService()).asBinder();
        }

        return null;
    }
}
