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

package android.tv;

import android.os.Bundle;
import android.tv.ITvInputSession;

/**
 * Helper interface for ITvInputSession to allow the TV input to notify the system service when a
 * new session has been created.
 * @hide
 */
oneway interface ITvInputSessionCallback {
    void onSessionCreated(ITvInputSession session);
    void onSessionEvent(in String name, in Bundle args);
    void onVideoSizeChanged(int width, int height);
}
