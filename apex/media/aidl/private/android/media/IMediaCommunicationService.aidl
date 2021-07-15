/**
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.media;

import android.media.Session2Token;
import android.media.IMediaCommunicationServiceCallback;
import android.media.MediaParceledListSlice;

/** {@hide} */
interface IMediaCommunicationService {
    void notifySession2Created(in Session2Token sessionToken);
    boolean isTrusted(String controllerPackageName, int controllerPid, int controllerUid);
    MediaParceledListSlice getSession2Tokens(int userId);

    void registerCallback(IMediaCommunicationServiceCallback callback, String packageName);
    void unregisterCallback(IMediaCommunicationServiceCallback callback);
}

