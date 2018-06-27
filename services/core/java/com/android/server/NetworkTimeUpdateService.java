/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server;

import android.os.IBinder;

/**
 * An interface for NetworkTimeUpdateService implementations. Eventually part or all of this service
 * will be subsumed into {@link com.android.server.timedetector.TimeDetectorService}. In the
 * meantime this interface allows Android to use either the old or new implementation.
 */
public interface NetworkTimeUpdateService extends IBinder {

    /** Initialize the receivers and initiate the first NTP request */
    void systemRunning();
}
