/* Copyright (C) 2014 The Android Open Source Project
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

package android.media.session;

import android.content.ComponentName;
import android.media.IRemoteVolumeController;
import android.media.session.IActiveSessionsListener;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * Interface to the MediaSessionManagerService
 * @hide
 */
interface ISessionManager {
    ISession createSession(String packageName, in ISessionCallback cb, String tag, int userId);
    List<IBinder> getSessions(in ComponentName compName, int userId);
    void dispatchMediaKeyEvent(in KeyEvent keyEvent, boolean needWakeLock);
    void dispatchAdjustVolume(int suggestedStream, int delta, int flags);
    void addSessionsListener(in IActiveSessionsListener listener, in ComponentName compName,
            int userId);
    void removeSessionsListener(in IActiveSessionsListener listener);

    // This is for the system volume UI only
    void setRemoteVolumeController(in IRemoteVolumeController rvc);

    // For PhoneWindowManager to precheck media keys
    boolean isGlobalPriorityActive();
}