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
    private static final boolean DEBUG = true;

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
        if (DEBUG) {
            Log.d(TAG, "Attempting to get helper with context " + context);
        }
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

    public void sendMediaButtonEvent(KeyEvent keyEvent, boolean needWakeLock) {
        mSessionManager.dispatchMediaKeyEvent(keyEvent, needWakeLock);
        if (DEBUG) {
            Log.d(TAG, "dispatched media key " + keyEvent);
        }
    }

    public void addRccListener(PendingIntent pi, TransportPerformer.Listener listener) {
        if (pi == null) {
            Log.w(TAG, "Pending intent was null, can't add rcc listener.");
            return;
        }
        SessionHolder holder = getHolder(pi, true);
        TransportPerformer performer = holder.mSession.getTransportPerformer();
        if (holder.mRccListener != null) {
            if (holder.mRccListener == listener) {
                if (DEBUG) {
                    Log.d(TAG, "addRccListener listener already added.");
                }
                // This is already the registered listener, ignore
                return;
            }
            // Otherwise it changed so we need to switch to the new one
            performer.removeListener(holder.mRccListener);
        }
        performer.addListener(listener, mHandler);
        holder.mRccListener = listener;
        holder.mFlags |= Session.FLAG_HANDLES_TRANSPORT_CONTROLS;
        holder.mSession.setFlags(holder.mFlags);
        holder.update();
        if (DEBUG) {
            Log.d(TAG, "Added rcc listener for " + pi + ".");
        }
    }

    public void removeRccListener(PendingIntent pi) {
        if (pi == null) {
            return;
        }
        SessionHolder holder = getHolder(pi, false);
        if (holder != null && holder.mRccListener != null) {
            holder.mSession.getTransportPerformer().removeListener(holder.mRccListener);
            holder.mRccListener = null;
            holder.mFlags &= ~Session.FLAG_HANDLES_TRANSPORT_CONTROLS;
            holder.mSession.setFlags(holder.mFlags);
            holder.update();
            if (DEBUG) {
                Log.d(TAG, "Removed rcc listener for " + pi + ".");
            }
        }
    }

    public void addMediaButtonListener(PendingIntent pi,
            Context context) {
        if (pi == null) {
            Log.w(TAG, "Pending intent was null, can't addMediaButtonListener.");
            return;
        }
        SessionHolder holder = getHolder(pi, true);
        if (holder.mMediaButtonListener != null) {
            // Already have this listener registered, but update it anyway as
            // the extras may have changed.
            if (DEBUG) {
                Log.d(TAG, "addMediaButtonListener already added " + pi);
            }
            return;
        }
        holder.mMediaButtonListener = new MediaButtonListener(pi, context);
        holder.mFlags |= Session.FLAG_HANDLES_MEDIA_BUTTONS;
        holder.mSession.setFlags(holder.mFlags);
        holder.mSession.getTransportPerformer().addListener(holder.mMediaButtonListener, mHandler);

        holder.mMediaButtonReceiver = new MediaButtonReceiver(pi, context);
        holder.mSession.addCallback(holder.mMediaButtonReceiver, mHandler);
        if (DEBUG) {
            Log.d(TAG, "addMediaButtonListener added " + pi);
        }
    }

    public void removeMediaButtonListener(PendingIntent pi) {
        if (pi == null) {
            return;
        }
        SessionHolder holder = getHolder(pi, false);
        if (holder != null && holder.mMediaButtonListener != null) {
            holder.mSession.getTransportPerformer().removeListener(holder.mMediaButtonListener);
            holder.mFlags &= ~Session.FLAG_HANDLES_MEDIA_BUTTONS;
            holder.mSession.setFlags(holder.mFlags);
            holder.mMediaButtonListener = null;

            holder.mSession.removeCallback(holder.mMediaButtonReceiver);
            holder.mMediaButtonReceiver = null;
            holder.update();
            if (DEBUG) {
                Log.d(TAG, "removeMediaButtonListener removed " + pi);
            }
        }
    }

    private SessionHolder getHolder(PendingIntent pi, boolean createIfMissing) {
        SessionHolder holder = mSessions.get(pi);
        if (holder == null && createIfMissing) {
            Session session = mSessionManager.createSession(TAG);
            session.setActive(true);
            holder = new SessionHolder(session, pi);
            mSessions.put(pi, holder);
        }
        return holder;
    }

    private static void sendKeyEvent(PendingIntent pi, Context context, Intent intent) {
        try {
            pi.send(context, 0, intent);
        } catch (CanceledException e) {
            Log.e(TAG, "Error sending media key down event:", e);
            // Don't bother sending up if down failed
            return;
        }
    }

    private static final class MediaButtonReceiver extends Session.Callback {
        private final PendingIntent mPendingIntent;
        private final Context mContext;

        public MediaButtonReceiver(PendingIntent pi, Context context) {
            mPendingIntent = pi;
            mContext = context;
        }

        @Override
        public void onMediaButton(Intent mediaButtonIntent) {
            MediaSessionLegacyHelper.sendKeyEvent(mPendingIntent, mContext, mediaButtonIntent);
        }
    }

    private static final class MediaButtonListener extends TransportPerformer.Listener {
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
            MediaSessionLegacyHelper.sendKeyEvent(mPendingIntent, mContext, intent);

            ke = new KeyEvent(KeyEvent.ACTION_UP, keyCode);
            intent.putExtra(Intent.EXTRA_KEY_EVENT, ke);
            MediaSessionLegacyHelper.sendKeyEvent(mPendingIntent, mContext, intent);

            if (DEBUG) {
                Log.d(TAG, "Sent " + keyCode + " to pending intent " + mPendingIntent);
            }
        }
    }

    private class SessionHolder {
        public final Session mSession;
        public final PendingIntent mPi;
        public MediaButtonListener mMediaButtonListener;
        public MediaButtonReceiver mMediaButtonReceiver;
        public TransportPerformer.Listener mRccListener;
        public int mFlags;

        public SessionHolder(Session session, PendingIntent pi) {
            mSession = session;
            mPi = pi;
        }

        public void update() {
            if (mMediaButtonListener == null && mRccListener == null) {
                mSession.release();
                mSessions.remove(mPi);
            }
        }
    }
}
