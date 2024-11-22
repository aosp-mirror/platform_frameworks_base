/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.media.tv.extension.event;

import android.net.Uri;
import android.os.Bundle;

/**
 * @hide
 */
interface IEventDownloadSession {
    // Determine to execute barker channel or silent tune flow for related service type
    int isBarkerOrSequentialDownloadByServiceType(in Bundle eventDownloadParams);
    // Determine whether to start barker channel or silent tune flow.
    int isBarkerOrSequentialDownloadByServiceRecord(in Bundle eventDownloadParams);
    // Start event download.
    void startTuningMultiplex(in Uri channelUri);
    // Set active window channels.
    void setActiveWindowChannelInfo(in Uri[] activeWinChannelInfos);
    // Cancel barker channel or silent tune flow.
    void cancel();
    // Release barker channel or silent tune flow.
    void release();
}
