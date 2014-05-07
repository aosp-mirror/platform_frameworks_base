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

import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.view.KeyEvent;

/**
 * Helper for connecting existing APIs up to the new session APIs. This can be
 * used by RCC, AudioFocus, etc. to create a single session that translates to
 * all those components.
 *
 * @hide
 */
public class MediaSessionLegacyHelper {
    private static final String TAG = "MediaSessionHelper";

    private static final Object sLock = new Object();
    private static MediaSessionLegacyHelper sInstance;

    private SessionManager mSessionManager;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    // The legacy APIs use PendingIntents to register/unregister media button
    // receivers and these are associated with RCC.
    private ArrayMap<PendingIntent, SessionHolder> mSessions
            = new ArrayMap<PendingIntent, SessionHolder>();

    private MediaSessionLegacyHelper(Context context) {
        mSessionManager = (SessionManager) context
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    public static MediaSessionLegacyHelper getHelper(Context context) {
        synchronized (sLock) {
            if (sInstance == null) {
                sInstance = new MediaSessionLegacyHelper(context);
            }
        }
        return sInstance;
    }

    public Session getSession(PendingIntent pi) {
        SessionHolder holder = mSessions.get(pi);
        return holder == null ? null : holder.mSession;
    }

    public void addRccListener(PendingIntent pi, TransportPerformer.Listener listener) {

        SessionHolder holder = getHolder(pi, true);
        TransportPerformer performer = holder.mSession.getTransportPerformer();
        if (holder.mRccListener != null) {
            if (holder.mRccListener == listener) {
                // This is already the registered listener, ignore
                return;
            }
            // Otherwise it changed so we need to switch to the new one
            performer.removeListener(holder.mRccListener);
        }
        performer.addListener(listener, mHandler);
        holder.mRccListener = listener;
        holder.update();
    }

    public void removeRccListener(PendingIntent pi) {
        SessionHolder holder = getHolder(pi, false);
        if (holder != null && holder.mRccListener != null) {
            holder.mSession.getTransportPerformer().removeListener(holder.mRccListener);
            holder.mRccListener = null;
            holder.update();
        }
    }

    public void addMediaButtonListener(PendingIntent pi,
            Context context) {
        SessionHolder holder = getHolder(pi, true);
        if (holder.mMediaButtonListener != null) {
            // Already have this listener registered
            return;
        }
        holder.mMediaButtonListener = new MediaButtonListener(pi, context);
        holder.mSession.getTransportPerformer().addListener(holder.mMediaButtonListener, mHandler);
    }

    public void removeMediaButtonListener(PendingIntent pi) {
        SessionHolder holder = getHolder(pi, false);
        if (holder != null && holder.mMediaButtonListener != null) {
            holder.mSession.getTransportPerformer().removeListener(holder.mMediaButtonListener);
            holder.update();
        }
    }

    private SessionHolder getHolder(PendingIntent pi, boolean createIfMissing) {
        SessionHolder holder = mSessions.get(pi);
        if (holder == null && createIfMissing) {
            Session session = mSessionManager.createSession(TAG);
            session.setTransportPerformerEnabled();
            session.publish();
            holder = new SessionHolder(session, pi);
            mSessions.put(pi, holder);
        }
        return holder;
    }

    public static class MediaButtonListener extends TransportPerformer.Listener {
        private final PendingIntent mPendingIntent;
        private final Context mContext;

        public MediaButtonListener(PendingIntent pi, Context context) {
            mPendingIntent = pi;
            mContext = context;
        }

        @Override
        public void onPlay() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_PLAY);
        }

        @Override
        public void onPause() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_PAUSE);
        }

        @Override
        public void onNext() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_NEXT);
        }

        @Override
        public void onPrevious() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }

        @Override
        public void onFastForward() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD);
        }

        @Override
        public void onRewind() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_REWIND);
        }

        @Override
        public void onStop() {
            sendKeyEvent(KeyEvent.KEYCODE_MEDIA_STOP);
        }

        private void sendKeyEvent(int keyCode) {
            KeyEvent ke = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
            Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);

            intent.putExtra(Intent.EXTRA_KEY_EVENT, ke);
            try {
                mPendingIntent.send(mContext, 0, intent);
            } catch (CanceledException e) {
                Log.e(TAG, "Error sending media key down event:", e);
                // Don't bother sending up if down failed
                return;
            }

            ke = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
            intent.putExtra(Intent.EXTRA_KEY_EVENT, ke);
            try {
                mPendingIntent.send(mContext, 0, intent);
            } catch (CanceledException e) {
                Log.e(TAG, "Error sending media key up event:", e);
            }
        }
    }

    private class SessionHolder {
        public final Session mSession;
        public final PendingIntent mPi;
        public MediaButtonListener mMediaButtonListener;
        public TransportPerformer.Listener mRccListener;

        public SessionHolder(Session session, PendingIntent pi) {
            mSession = session;
            mPi = pi;
        }

        public void update() {
            if (mMediaButtonListener == null && mRccListener == null) {
                mSession.release();
                mSessions.remove(mPi);
            } else if (mMediaButtonListener != null && mRccListener != null) {
                // TODO set session to active
            } else {
                // TODO set session to inactive
            }
        }
    }
}
