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
import android.content.pm.ParceledListSlice;
import android.media.IRemoteVolumeController;
import android.media.Session2Token;
import android.media.session.IActiveSessionsListener;
import android.media.session.ICallback;
import android.media.session.IOnMediaKeyListener;
import android.media.session.IOnVolumeKeyLongPressListener;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.media.session.ISession2TokensListener;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.view.KeyEvent;

/**
 * Interface to the MediaSessionManagerService
 * @hide
 */
interface ISessionManager {
    ISession createSession(String packageName, in ISessionCallback sessionCb, String tag,
            in Bundle sessionInfo, int userId);
    void notifySession2Created(in Session2Token sessionToken);
    List<MediaSession.Token> getSessions(in ComponentName compName, int userId);
    ParceledListSlice getSession2Tokens(int userId);
    void dispatchMediaKeyEvent(String packageName, boolean asSystemService, in KeyEvent keyEvent,
            boolean needWakeLock);
    boolean dispatchMediaKeyEventToSessionAsSystemService(String packageName,
            in MediaSession.Token sessionToken, in KeyEvent keyEvent);
    void dispatchVolumeKeyEvent(String packageName, String opPackageName, boolean asSystemService,
            in KeyEvent keyEvent, int stream, boolean musicOnly);
    void dispatchVolumeKeyEventToSessionAsSystemService(String packageName, String opPackageName,
            in MediaSession.Token sessionToken, in KeyEvent keyEvent);
    void dispatchAdjustVolume(String packageName, String opPackageName, int suggestedStream,
            int delta, int flags);
    void addSessionsListener(in IActiveSessionsListener listener, in ComponentName compName,
            int userId);
    void removeSessionsListener(in IActiveSessionsListener listener);
    void addSession2TokensListener(in ISession2TokensListener listener, int userId);
    void removeSession2TokensListener(in ISession2TokensListener listener);

    void registerRemoteVolumeController(in IRemoteVolumeController rvc);
    void unregisterRemoteVolumeController(in IRemoteVolumeController rvc);

    // For PhoneWindowManager to precheck media keys
    boolean isGlobalPriorityActive();

    void setCallback(in ICallback callback);
    void setOnVolumeKeyLongPressListener(in IOnVolumeKeyLongPressListener listener);
    void setOnMediaKeyListener(in IOnMediaKeyListener listener);

    boolean isTrusted(String controllerPackageName, int controllerPid, int controllerUid);
}
