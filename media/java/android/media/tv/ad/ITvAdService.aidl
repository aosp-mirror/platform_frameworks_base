/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.media.tv.ad;

import android.media.tv.ad.ITvAdServiceCallback;
import android.media.tv.ad.ITvAdSessionCallback;
import android.os.Bundle;
import android.view.InputChannel;

/**
 * Top-level interface to a TV AD component (implemented in a Service). It's used for
 * TvAdManagerService to communicate with TvAdService.
 * @hide
 */
oneway interface ITvAdService {
    void registerCallback(in ITvAdServiceCallback callback);
    void unregisterCallback(in ITvAdServiceCallback callback);
    void createSession(in InputChannel channel, in ITvAdSessionCallback callback,
            in String serviceId, in String type);
    void sendAppLinkCommand(in Bundle command);
}