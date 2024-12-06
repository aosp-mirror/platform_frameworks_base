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

/** Wrapper around {@link FrameworkStatsLog} */
public class FrameworkStatsLogWrapper {

    /** Wrapper around {@link FrameworkStatsLog#write} for MediaProjectionStateChanged atom. */
    public void writeStateChanged(
            int code,
            int sessionId,
            int state,
            int previousState,
            int hostUid,
            int targetUid,
            int timeSinceLastActive,
            int creationSource,
            int stopSource) {
        FrameworkStatsLog.write(
                code,
                sessionId,
                state,
                previousState,
                hostUid,
                targetUid,
                timeSinceLastActive,
                creationSource,
                stopSource);
    }

    /** Wrapper around {@link FrameworkStatsLog#write} for MediaProjectionTargetChanged atom. */
    public void writeTargetChanged(
            int code,
            int sessionId,
            int targetType,
            int hostUid,
            int targetUid,
            int windowingMode,
            int width,
            int height,
            int centerX,
            int centerY,
            int targetChangeType) {
        FrameworkStatsLog.write(
                code,
                sessionId,
                targetType,
                hostUid,
                targetUid,
                windowingMode,
                width,
                height,
                centerX,
                centerY,
                targetChangeType);
    }
}
