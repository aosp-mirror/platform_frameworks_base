/*
 * Copyright 2021 The Android Open Source Project
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

package com.android.server.media;

import android.annotation.Nullable;
import android.media.Session2Token;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.server.media.MediaCommunicationService.Session2Record;

import java.util.ArrayList;
import java.util.List;

//TODO: Define the priority specifically.
/**
 * Keeps track of media sessions and their priority for notifications, media
 * button dispatch, etc.
 * Higher priority session has more chance to be selected as media button session,
 * which receives the media button events.
 */
class SessionPriorityList {
    private static final String TAG = "SessionPriorityList";
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final List<Session2Record> mSessions = new ArrayList<>();

    @Nullable
    private Session2Record mMediaButtonSession;
    @Nullable
    private Session2Record mCachedVolumeSession;

    //TODO: integrate AudioPlayerStateMonitor

    public void addSession(Session2Record record) {
        synchronized (mLock) {
            mSessions.add(record);
        }
    }

    public void removeSession(Session2Record record) {
        synchronized (mLock) {
            mSessions.remove(record);
        }
        if (record == mMediaButtonSession) {
            updateMediaButtonSession(null);
        }
    }

    public void destroyAllSessions() {
        synchronized (mLock) {
            for (Session2Record session : mSessions) {
                session.close();
            }
            mSessions.clear();
        }
    }

    public boolean destroySessionsByUserId(int userId) {
        boolean changed = false;
        synchronized (mLock) {
            for (int i = mSessions.size() - 1; i >= 0; i--) {
                Session2Record session = mSessions.get(i);
                if (session.getUserId() == userId) {
                    mSessions.remove(i);
                    session.close();
                    changed = true;
                }
            }
        }
        return changed;
    }

    public List<Session2Token> getAllTokens() {
        List<Session2Token> sessions = new ArrayList<>();
        synchronized (mLock) {
            for (Session2Record session : mSessions) {
                sessions.add(session.getSessionToken());
            }
        }
        return sessions;
    }

    public List<Session2Token> getTokensByUserId(int userId) {
        List<Session2Token> sessions = new ArrayList<>();
        synchronized (mLock) {
            for (Session2Record session : mSessions) {
                if (session.getUserId() == userId) {
                    sessions.add(session.getSessionToken());
                }
            }
        }
        return sessions;
    }

    /** Gets the media button session which receives the media button events. */
    @Nullable
    public Session2Record getMediaButtonSession() {
        return mMediaButtonSession;
    }

    /** Gets the media volume session which receives the volume key events. */
    @Nullable
    public Session2Record getMediaVolumeSession() {
        //TODO: if null, calculate it.
        return mCachedVolumeSession;
    }

    public boolean contains(Session2Record session) {
        synchronized (mLock) {
            return mSessions.contains(session);
        }
    }

    public void onPlaybackStateChanged(Session2Record session, boolean promotePriority) {
        if (promotePriority) {
            synchronized (mLock) {
                if (mSessions.remove(session)) {
                    mSessions.add(0, session);
                } else {
                    Log.w(TAG, "onPlaybackStateChanged: Ignoring unknown session");
                    return;
                }
            }
        } else if (session.checkPlaybackActiveState(false)) {
            // Just clear the cached volume session when a state goes inactive
            mCachedVolumeSession = null;
        }

        // In most cases, playback state isn't needed for finding the media button session,
        // but we only use it as a hint if an app has multiple local media sessions.
        // In that case, we pick the media session whose PlaybackState matches
        // the audio playback configuration.
        if (mMediaButtonSession != null
                && mMediaButtonSession.getSessionToken().getUid()
                == session.getSessionToken().getUid()) {
            Session2Record newMediaButtonSession =
                    findMediaButtonSession(mMediaButtonSession.getSessionToken().getUid());
            if (newMediaButtonSession != mMediaButtonSession) {
                // Check if the policy states that this session should not be updated as a media
                // button session.
                updateMediaButtonSession(newMediaButtonSession);
            }
        }
    }

    private void updateMediaButtonSession(@Nullable Session2Record newSession) {
        mMediaButtonSession = newSession;
        //TODO: invoke callbacks for media button session changed listeners
    }

    /**
     * Finds the media button session with the given {@param uid}.
     * If the app has multiple media sessions, the media session whose playback state is not null
     * and matches the audio playback state becomes the media button session. Otherwise the top
     * priority session becomes the media button session.
     *
     * @return The media button session. Returns {@code null} if the app doesn't have a media
     *   session.
     */
    @Nullable
    private Session2Record findMediaButtonSession(int uid) {
        Session2Record mediaButtonSession = null;
        synchronized (mLock) {
            for (Session2Record session : mSessions) {
                if (uid != session.getSessionToken().getUid()) {
                    continue;
                }
                // TODO: check audio player state monitor
                if (mediaButtonSession == null) {
                    // Pick the top priority session as a default.
                    mediaButtonSession = session;
                }
            }
        }
        return mediaButtonSession;
    }
}
