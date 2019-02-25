/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.projection;

import android.media.projection.IMediaProjection;
import android.media.projection.IMediaProjectionCallback;
import android.media.projection.IMediaProjectionWatcherCallback;
import android.media.projection.MediaProjectionInfo;
import android.os.IBinder;

/** {@hide} */
interface IMediaProjectionManager {
    @UnsupportedAppUsage
    boolean hasProjectionPermission(int uid, String packageName);
    IMediaProjection createProjection(int uid, String packageName, int type,
            boolean permanentGrant);
    boolean isValidMediaProjection(IMediaProjection projection);
    MediaProjectionInfo getActiveProjectionInfo();
    void stopActiveProjection();
    void addCallback(IMediaProjectionWatcherCallback callback);
    void removeCallback(IMediaProjectionWatcherCallback callback);
}
