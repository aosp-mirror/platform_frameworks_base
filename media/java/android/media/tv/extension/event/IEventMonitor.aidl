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

import android.media.tv.extension.event.IEventMonitorListener;
import android.net.Uri;
import android.os.Bundle;

/**
 * @hide
 */
interface IEventMonitor {
    // Get present event information.
    Bundle getPresentEventInfo(long channelDbId);
    // Add present event information listener.
    void addPresentEventInfoListener(in IEventMonitorListener listener);
    // Remove present event information listener.
    void removePresentEventInfoListener(in IEventMonitorListener listener);
    // Get following event information.
    Bundle getFollowingEventInfo(long channelDbId);
    // Add following event information listener.
    void addFollowingEventInfoListener(in IEventMonitorListener listener);
    // Remove following event information listener.
    void removeFollowingEventInfoListener(in IEventMonitorListener listener);
    // Get SDT guidance information.
    Bundle getSdtGuidanceInfo(long channelDbId);
    // Set Event Background channel list info.
    void setBgmTuneChannelInfo(in Uri[] tuneChannelInfos);
}
