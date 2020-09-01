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

package com.android.server.media;

import static com.android.server.media.SessionPolicyProvider.SESSION_POLICY_IGNORE_BUTTON_SESSION;

import android.media.Session2Token;
import android.media.session.MediaSession;
import android.os.Debug;
import android.os.UserHandle;
import android.util.IntArray;
import android.util.Log;
import android.util.SparseArray;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Keeps track of media sessions and their priority for notifications, media
 * button dispatch, etc.
 * <p>This class isn't thread-safe. The caller should take care of the synchronization.
 */
class MediaSessionStack {
    private static final boolean DEBUG = MediaSessionService.DEBUG;
    private static final String TAG = "MediaSessionStack";

    /**
     * Listen the change in the media button session.
     */
    interface OnMediaButtonSessionChangedListener {
        /**
         * Called when the media button session is changed.
         */
        void onMediaButtonSessionChanged(MediaSessionRecordImpl oldMediaButtonSession,
                MediaSessionRecordImpl newMediaButtonSession);
    }

    /**
     * Sorted list of the media sessions
     */
    private final List<MediaSessionRecordImpl> mSessions = new ArrayList<>();

    private final AudioPlayerStateMonitor mAudioPlayerStateMonitor;
    private final OnMediaButtonSessionChangedListener mOnMediaButtonSessionChangedListener;

    /**
     * The media button session which receives media key events.
     * It could be null if the previous media button session is released.
     */
    private MediaSessionRecordImpl mMediaButtonSession;

    private MediaSessionRecordImpl mCachedVolumeDefault;

    /**
     * Cache the result of the {@link #getActiveSessions} per user.
     */
    private final SparseArray<List<MediaSessionRecord>> mCachedActiveLists =
            new SparseArray<>();

    MediaSessionStack(AudioPlayerStateMonitor monitor, OnMediaButtonSessionChangedListener listener) {
        mAudioPlayerStateMonitor = monitor;
        mOnMediaButtonSessionChangedListener = listener;
    }

    /**
     * Add a record to the priority tracker.
     *
     * @param record The record to add.
     */
    public void addSession(MediaSessionRecordImpl record) {
        mSessions.add(record);
        clearCache(record.getUserId());

        // Update the media button session.
        // The added session could be the session from the package with the audio playback.
        // This can happen if an app starts audio playback before creating media session.
        updateMediaButtonSessionIfNeeded();
    }

    /**
     * Remove a record from the priority tracker.
     *
     * @param record The record to remove.
     */
    public void removeSession(MediaSessionRecordImpl record) {
        mSessions.remove(record);
        if (mMediaButtonSession == record) {
            // When the media button session is removed, nullify the media button session and do not
            // search for the alternative media session within the app. It's because the alternative
            // media session might be a dummy which isn't able to handle the media key events.
            // TODO(b/154456172): Make this decision unaltered by non-media app's playback.
            updateMediaButtonSession(null);
        }
        clearCache(record.getUserId());
    }

    /**
     * Return if the record exists in the priority tracker.
     */
    public boolean contains(MediaSessionRecordImpl record) {
        return mSessions.contains(record);
    }

    /**
     * Gets the {@link MediaSessionRecord} with the {@link MediaSession.Token}.
     *
     * @param sessionToken session token
     * @return the MediaSessionRecord. Can be {@code null} if the session is gone meanwhile.
     */
    public MediaSessionRecord getMediaSessionRecord(MediaSession.Token sessionToken) {
        for (MediaSessionRecordImpl record : mSessions) {
            if (record instanceof MediaSessionRecord) {
                MediaSessionRecord session1 = (MediaSessionRecord) record;
                if (Objects.equals(session1.getSessionToken(), sessionToken)) {
                    return session1;
                }
            }
        }
        return null;
    }

    /**
     * Notify the priority tracker that a session's playback state changed.
     *
     * @param record The record that changed.
     * @param shouldUpdatePriority {@code true} if the record needs to prioritized
     */
    public void onPlaybackStateChanged(
            MediaSessionRecordImpl record, boolean shouldUpdatePriority) {
        if (shouldUpdatePriority) {
            mSessions.remove(record);
            mSessions.add(0, record);
            clearCache(record.getUserId());
        } else if (record.checkPlaybackActiveState(false)) {
            // Just clear the volume cache when a state goes inactive
            mCachedVolumeDefault = null;
        }

        // In most cases, playback state isn't needed for finding media button session,
        // but we only use it as a hint if an app has multiple local media sessions.
        // In that case, we pick the media session whose PlaybackState matches
        // the audio playback configuration.
        if (mMediaButtonSession != null && mMediaButtonSession.getUid() == record.getUid()) {
            MediaSessionRecordImpl newMediaButtonSession =
                    findMediaButtonSession(mMediaButtonSession.getUid());
            if (newMediaButtonSession != mMediaButtonSession
                    && (newMediaButtonSession.getSessionPolicies()
                            & SESSION_POLICY_IGNORE_BUTTON_SESSION) == 0) {
                // Check if the policy states that this session should not be updated as a media
                // button session.
                updateMediaButtonSession(newMediaButtonSession);
            }
        }
    }

    /**
     * Handle the change in activeness for a session.
     *
     * @param record The record that changed.
     */
    public void onSessionActiveStateChanged(MediaSessionRecordImpl record) {
        // For now just clear the cache. Eventually we'll selectively clear
        // depending on what changed.
        clearCache(record.getUserId());
    }

    /**
     * Update the media button session if needed.
     * <p>The media button session is the session that will receive the media button events.
     * <p>We send the media button events to the lastly played app. If the app has the media
     * session, the session will receive the media button events.
     */
    public void updateMediaButtonSessionIfNeeded() {
        if (DEBUG) {
            Log.d(TAG, "updateMediaButtonSessionIfNeeded, callers=" + Debug.getCallers(2));
        }
        IntArray audioPlaybackUids = mAudioPlayerStateMonitor.getSortedAudioPlaybackClientUids();
        for (int i = 0; i < audioPlaybackUids.size(); i++) {
            int audioPlaybackUid = audioPlaybackUids.get(i);
            MediaSessionRecordImpl mediaButtonSession = findMediaButtonSession(audioPlaybackUid);
            if (mediaButtonSession == null) {
                if (DEBUG) {
                    Log.d(TAG, "updateMediaButtonSessionIfNeeded, skipping uid="
                            + audioPlaybackUid);
                }
                // Ignore if the lastly played app isn't a media app (i.e. has no media session)
                continue;
            }
            boolean ignoreButtonSession =
                    (mediaButtonSession.getSessionPolicies()
                            & SESSION_POLICY_IGNORE_BUTTON_SESSION) != 0;
            if (DEBUG) {
                Log.d(TAG, "updateMediaButtonSessionIfNeeded, checking uid=" + audioPlaybackUid
                        + ", mediaButtonSession=" + mediaButtonSession
                        + ", ignoreButtonSession=" + ignoreButtonSession);
            }
            if (!ignoreButtonSession) {
                mAudioPlayerStateMonitor.cleanUpAudioPlaybackUids(mediaButtonSession.getUid());
                if (mediaButtonSession != mMediaButtonSession) {
                    updateMediaButtonSession(mediaButtonSession);
                }
                return;
            }
        }
    }

    // TODO: Remove this and make updateMediaButtonSessionIfNeeded() to also cover this case.
    public void updateMediaButtonSessionBySessionPolicyChange(MediaSessionRecord record) {
        if ((record.getSessionPolicies() & SESSION_POLICY_IGNORE_BUTTON_SESSION) != 0) {
            if (record == mMediaButtonSession) {
                // TODO(b/154456172): Make this decision unaltered by non-media app's playback.
                updateMediaButtonSession(null);
            }
        } else {
            updateMediaButtonSessionIfNeeded();
        }
    }

    /**
     * Find the media button session with the given {@param uid}.
     * If the app has multiple media sessions, the media session whose playback state is not null
     * and matches the audio playback state becomes the media button session. Otherwise the top
     * priority session becomes the media button session.
     *
     * @return The media button session. Returns {@code null} if the app doesn't have a media
     *   session.
     */
    private MediaSessionRecordImpl findMediaButtonSession(int uid) {
        MediaSessionRecordImpl mediaButtonSession = null;
        for (MediaSessionRecordImpl session : mSessions) {
            if (session instanceof MediaSession2Record) {
                // TODO(jaewan): Make MediaSession2 to receive media key event
                continue;
            }
            if (uid == session.getUid()) {
                if (session.checkPlaybackActiveState(
                        mAudioPlayerStateMonitor.isPlaybackActive(session.getUid()))) {
                    // If there's a media session whose PlaybackState matches
                    // the audio playback state, return it immediately.
                    return session;
                }
                if (mediaButtonSession == null) {
                    // Among the media sessions whose PlaybackState doesn't match
                    // the audio playback state, pick the top priority.
                    mediaButtonSession = session;
                }
            }
        }
        return mediaButtonSession;
    }

    /**
     * Get the current priority sorted list of active sessions. The most
     * important session is at index 0 and the least important at size - 1.
     *
     * @param userId The user to check. It can be {@link UserHandle#USER_ALL} to get all sessions
     *    for all users in this {@link MediaSessionStack}.
     * @return All the active sessions in priority order.
     */
    public List<MediaSessionRecord> getActiveSessions(int userId) {
        List<MediaSessionRecord> cachedActiveList = mCachedActiveLists.get(userId);
        if (cachedActiveList == null) {
            cachedActiveList = getPriorityList(true, userId);
            mCachedActiveLists.put(userId, cachedActiveList);
        }
        return cachedActiveList;
    }

    /**
     * Gets the session2 tokens.
     *
     * @param userId The user to check. It can be {@link UserHandle#USER_ALL} to get all session2
     *    tokens for all users in this {@link MediaSessionStack}.
     * @return All session2 tokens.
     */
    public List<Session2Token> getSession2Tokens(int userId) {
        ArrayList<Session2Token> session2Records = new ArrayList<>();
        for (MediaSessionRecordImpl record : mSessions) {
            if ((userId == UserHandle.USER_ALL || record.getUserId() == userId)
                    && record.isActive()
                    && record instanceof MediaSession2Record) {
                MediaSession2Record session2 = (MediaSession2Record) record;
                session2Records.add(session2.getSession2Token());
            }
        }
        return session2Records;
    }

    /**
     * Get the media button session which receives the media button events.
     *
     * @return The media button session or null.
     */
    public MediaSessionRecordImpl getMediaButtonSession() {
        return mMediaButtonSession;
    }

    public void updateMediaButtonSession(MediaSessionRecordImpl newMediaButtonSession) {
        MediaSessionRecordImpl oldMediaButtonSession = mMediaButtonSession;
        mMediaButtonSession = newMediaButtonSession;
        mOnMediaButtonSessionChangedListener.onMediaButtonSessionChanged(
                oldMediaButtonSession, newMediaButtonSession);
    }

    public MediaSessionRecordImpl getDefaultVolumeSession() {
        if (mCachedVolumeDefault != null) {
            return mCachedVolumeDefault;
        }
        List<MediaSessionRecord> records = getPriorityList(true, UserHandle.USER_ALL);
        int size = records.size();
        for (int i = 0; i < size; i++) {
            MediaSessionRecord record = records.get(i);
            if (record.checkPlaybackActiveState(true)) {
                mCachedVolumeDefault = record;
                return record;
            }
        }
        return null;
    }

    public MediaSessionRecordImpl getDefaultRemoteSession(int userId) {
        List<MediaSessionRecord> records = getPriorityList(true, userId);

        int size = records.size();
        for (int i = 0; i < size; i++) {
            MediaSessionRecord record = records.get(i);
            if (!record.isPlaybackTypeLocal()) {
                return record;
            }
        }
        return null;
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "Media button session is " + mMediaButtonSession);
        pw.println(prefix + "Sessions Stack - have " + mSessions.size() + " sessions:");
        String indent = prefix + "  ";
        for (MediaSessionRecordImpl record : mSessions) {
            record.dump(pw, indent);
        }
    }

    /**
     * Get a priority sorted list of sessions. Can filter to only return active
     * sessions or sessions.
     * <p>Here's the priority order.
     * <li>Active sessions whose PlaybackState is active</li>
     * <li>Active sessions whose PlaybackState is inactive</li>
     * <li>Inactive sessions</li>
     *
     * @param activeOnly True to only return active sessions, false to return
     *            all sessions.
     * @param userId The user to get sessions for. {@link UserHandle#USER_ALL}
     *            will return sessions for all users.
     * @return The priority sorted list of sessions.
     */
    public List<MediaSessionRecord> getPriorityList(boolean activeOnly, int userId) {
        List<MediaSessionRecord> result = new ArrayList<MediaSessionRecord>();
        int lastPlaybackActiveIndex = 0;
        int lastActiveIndex = 0;

        for (MediaSessionRecordImpl record : mSessions) {
            if (!(record instanceof MediaSessionRecord)) {
                continue;
            }
            final MediaSessionRecord session = (MediaSessionRecord) record;

            if ((userId != UserHandle.USER_ALL && userId != session.getUserId())) {
                // Filter out sessions for the wrong user or session2.
                continue;
            }

            if (!session.isActive()) {
                if (!activeOnly) {
                    // If we're getting unpublished as well always put them at
                    // the end
                    result.add(session);
                }
                continue;
            }

            if (session.checkPlaybackActiveState(true)) {
                result.add(lastPlaybackActiveIndex++, session);
                lastActiveIndex++;
            } else {
                result.add(lastActiveIndex++, session);
            }
        }

        return result;
    }

    private void clearCache(int userId) {
        mCachedVolumeDefault = null;
        mCachedActiveLists.remove(userId);
        // mCachedActiveLists may also include the list of sessions for UserHandle.USER_ALL,
        // so they also need to be cleared.
        mCachedActiveLists.remove(UserHandle.USER_ALL);
    }
}
