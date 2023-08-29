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

package com.android.server.media.projection;


import com.android.internal.util.FrameworkStatsLog;

/**
 * Class for emitting logs describing a MediaProjection session.
 */
public class MediaProjectionMetricsLogger {
    private static MediaProjectionMetricsLogger sSingleton = null;

    public static MediaProjectionMetricsLogger getInstance() {
        if (sSingleton == null) {
            sSingleton = new MediaProjectionMetricsLogger();
        }
        return sSingleton;
    }

    void notifyProjectionStateChange(int hostUid, int state, int sessionCreationSource) {
        write(hostUid, state, sessionCreationSource);
    }

    private void write(int hostUid, int state, int sessionCreationSource) {
        FrameworkStatsLog.write(
                /* code */ FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED,
                /* session_id */ 123,
                /* state */ state,
                /* previous_state */ FrameworkStatsLog.MEDIA_PROJECTION_STATE_CHANGED__STATE__MEDIA_PROJECTION_STATE_UNKNOWN,
                /* host_uid */ hostUid,
                /* target_uid */ -1,
                /* time_since_last_active */ 0,
                /* creation_source */ sessionCreationSource);
    }
}
