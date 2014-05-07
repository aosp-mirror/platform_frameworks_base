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

package android.media.session;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.ISessionManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * MediaSessionManager allows the creation and control of MediaSessions in the
 * system. A MediaSession enables publishing information about ongoing media and
 * interacting with MediaControllers and MediaRoutes.
 * <p>
 * Use <code>Context.getSystemService(Context.MEDIA_SESSION_SERVICE)</code> to
 * get an instance of this class.
 * <p>
 *
 * @see Session
 * @see SessionController
 */
public final class SessionManager {
    private static final String TAG = "SessionManager";

    private final ISessionManager mService;

    private Context mContext;

    /**
     * @hide
     */
    public SessionManager(Context context) {
        // Consider rewriting like DisplayManagerGlobal
        // Decide if we need context
        mContext = context;
        IBinder b = ServiceManager.getService(Context.MEDIA_SESSION_SERVICE);
        mService = ISessionManager.Stub.asInterface(b);
    }

    /**
     * Creates a new session.
     *
     * @param tag A short name for debugging purposes
     * @return a {@link Session} for the new session
     */
    public Session createSession(String tag) {
        try {
            Session.CallbackStub cbStub = new Session.CallbackStub();
            Session session = new Session(mService
                    .createSession(mContext.getPackageName(), cbStub, tag), cbStub);
            cbStub.setMediaSession(session);

            return session;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to create session: ", e);
            return null;
        }
    }

    /**
     * Get a list of controllers for all ongoing sessions. This requires the
     * android.Manifest.permission.MEDIA_CONTENT_CONTROL permission be held by
     * the calling app. You may also retrieve this list if your app is an
     * enabled notification listener using the
     * {@link NotificationListenerService} APIs, in which case you must pass the
     * {@link ComponentName} of your enabled listener.
     *
     * @param notificationListener The enabled notification listener component.
     *            May be null.
     * @return A list of controllers for ongoing sessions
     */
    public List<SessionController> getActiveSessions(ComponentName notificationListener) {
        ArrayList<SessionController> controllers = new ArrayList<SessionController>();
        try {
            List<IBinder> binders = mService.getSessions(notificationListener);
            for (int i = binders.size() - 1; i >= 0; i--) {
                SessionController controller = SessionController.fromBinder(ISessionController.Stub
                        .asInterface(binders.get(i)));
                controllers.add(controller);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to get active sessions: ", e);
        }
        return controllers;
    }
}
