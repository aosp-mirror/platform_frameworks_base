/*
 * Copyright 2021 The Android Open Source Project
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

package android.media.tv.interactive;

import android.media.tv.interactive.AppLinkInfo;
import android.media.tv.interactive.ITvInteractiveAppServiceCallback;
import android.media.tv.interactive.ITvInteractiveAppSessionCallback;
import android.os.Bundle;
import android.view.InputChannel;

/**
 * Top-level interface to a TV Interactive App component (implemented in a Service). It's used for
 * TvInteractiveAppManagerService to communicate with TvInteractiveAppService.
 * @hide
 */
oneway interface ITvInteractiveAppService {
    void registerCallback(in ITvInteractiveAppServiceCallback callback);
    void unregisterCallback(in ITvInteractiveAppServiceCallback callback);
    void createSession(in InputChannel channel, in ITvInteractiveAppSessionCallback callback,
            in String iAppServiceId, int type);
    void registerAppLinkInfo(in AppLinkInfo info);
    void unregisterAppLinkInfo(in AppLinkInfo info);
    void sendAppLinkCommand(in Bundle command);
}