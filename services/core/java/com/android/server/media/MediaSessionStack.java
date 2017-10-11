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

import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Debug;
import android.os.UserHandle;
import android.util.IntArray;
import android.util.Log;
import android.util.SparseArray;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

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
        void onMediaButtonSessionChanged(MediaSessionRecord oldMediaButtonSession,
                MediaSessionRecord newMediaButtonSession);
    }

    /**
     * These are states that usually indicate the user took an action and should
     * bump priority regardless of the old state.
     */
    private static final int[] ALWAYS_PRIORITY_STATES = {
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_NEXT };
    /**
     * These are states that usually indicate the user took an action if they
     * were entered from a non-priority state.
     */
    private static final int[] TRANSITION_PRIORITY_STATES = {
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_PLAYING };

    /**
     * Sorted list of the media sessions.
     * The session of which PlaybackState is changed to ALWAYS_PRIORITY_STATES or
     * TRANSITION_PRIORITY_STATES comes first.
     * @see #shouldUpdatePriority
     */
    private final List<MediaSessionRecord> mSessions = new ArrayList<MediaSessionRecord>();

    private final AudioPlaybackMonitor mAudioPlaybackMonitor;
    private final OnMediaButtonSessionChangedListener mOnMediaButtonSessionChangedListener;

    /**
     * The media button session which receives media key events.
     * It could be null if the previous media buttion session is released.
     */
    private MediaSessionRecord mMediaButtonSession;

    private MediaSessionRecord mCachedDefault;
    private MediaSessionRecord mCachedVolumeDefault;

    /**
     * Cache the result of the {@link #getActiveSessions} per user.
     */
    private final SparseArray<ArrayList<MediaSessionRecord>> mCachedActiveLists =
            new SparseArray<>();

    MediaSessionStack(AudioPlaybackMonitor monitor, OnMediaButtonSessionChangedListener listener) {
        mAudioPlaybackMonitor = monitor;
        mOnMediaButtonSessionChangedListener = listener;
    }

    /**
     * Add a record to the priority tracker.
     *
     * @param record The record to add.
     */
    public void addSession(MediaSessionRecord record) {
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
    public void removeSession(MediaSessionRecord record) {
        mSessions.remove(record);
        if (mMediaButtonSession == record) {
            // When the media button session is removed, nullify the media button session and do not
            // search for the alternative media session within the app. It's because the alternative
            // media session might be a dummy which isn't able to handle the media key events.
            updateMediaButtonSession(null);
        }
        clearCache(record.getUserId());
    }

    /**
     * Return if the record exists in the priority tracker.
     */
    public boolean contains(MediaSessionRecord record) {
        return mSessions.contains(record);
    }

    /**
     * Notify the priority tracker that a session's playback state changed.
     *
     * @param record The record that changed.
     * @param oldState Its old playback state.
     * @param newState Its new playback state.
     */
    public void onPlaystateChanged(MediaSessionRecord record, int oldState, int newState) {
        if (shouldUpdatePriority(oldState, newState)) {
            mSessions.remove(record);
            mSessions.add(0, record);
            clearCache(record.getUserId());
        } else if (!MediaSession.isActiveState(newState)) {
            // Just clear the volume cache when a state goes inactive
            mCachedVolumeDefault = null;
        }

        // In most cases, playback state isn't needed for finding media button session,
        // but we only use it as a hint if an app has multiple local media sessions.
        // In that case, we pick the media session whose PlaybackState matches
        // the audio playback configuration.
        if (mMediaButtonSession != null && mMediaButtonSession.getUid() == record.getUid()) {
            MediaSessionRecord newMediaButtonSession =
                    findMediaButtonSession(mMediaButtonSession.getUid());
            if (newMediaButtonSession != mMediaButtonSession) {
                updateMediaButtonSession(newMediaButtonSession);
            }
        }
    }

    /**
     * Handle the change in activeness for a session.
     *
     * @param record The record that changed.
     */
    public void onSessionStateChange(MediaSessionRecord record) {
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
        IntArray audioPlaybackUids = mAudioPlaybackMonitor.getSortedAudioPlaybackClientUids();
        for (int i = 0; i < audioPlaybackUids.size(); i++) {
            MediaSessionRecord mediaButtonSession =
                    findMediaButtonSession(audioPlaybackUids.get(i));
            if (mediaButtonSession != null) {
                // Found the media button session.
                mAudioPlaybackMonitor.cleanUpAudioPlaybackUids(mediaButtonSession.getUid());
                if (mMediaButtonSession != mediaButtonSession) {
                    updateMediaButtonSession(mediaButtonSession);
                }
                return;
            }
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
    private MediaSessionRecord findMediaButtonSession(int uid) {
        MediaSessionRecord mediaButtonSession = null;
        for (MediaSessionRecord session : mSessions) {
            if (uid == session.getUid()) {
                if (session.getPlaybackState() != null && session.isPlaybackActive() ==
                        mAudioPlaybackMonitor.isPlaybackActive(session.getUid())) {
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
    public ArrayList<MediaSessionRecord> getActiveSessions(int userId) {
        ArrayList<MediaSessionRecord> cachedActiveList = mCachedActiveLists.get(userId);
        if (cachedActiveList == null) {
            cachedActiveList = getPriorityList(true, userId);
            mCachedActiveLists.put(userId, cachedActiveList);
        }
        return cachedActiveList;
    }

    /**
     * Get the media button session which receives the media button events.
     *
     * @return The media button session or null.
     */
    public MediaSessionRecord getMediaButtonSession() {
        return mMediaButtonSession;
    }

    private void updateMediaButtonSession(MediaSessionRecord newMediaButtonSession) {
        MediaSessionRecord oldMediaButtonSession = mMediaButtonSession;
        mMediaButtonSession = newMediaButtonSession;
        mOnMediaButtonSessionChangedListener.onMediaButtonSessionChanged(
                oldMediaButtonSession, newMediaButtonSession);
    }

    public MediaSessionRecord getDefaultVolumeSession() {
        if (mCachedVolumeDefault != null) {
            return mCachedVolumeDefault;
        }
        ArrayList<MediaSessionRecord> records = getPriorityList(true, UserHandle.USER_ALL);
        int size = records.size();
        for (int i = 0; i < size; i++) {
            MediaSessionRecord record = records.get(i);
            if (record.isPlaybackActive()) {
                mCachedVolumeDefault = record;
                return record;
            }
        }
        return null;
    }

    public MediaSessionRecord getDefaultRemoteSession(int userId) {
        ArrayList<MediaSessionRecord> records = getPriorityList(true, userId);

        int size = records.size();
        for (int i = 0; i < size; i++) {
            MediaSessionRecord record = records.get(i);
            if (record.getPlaybackType() == PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
                return record;
            }
        }
        return null;
    }

    public void dump(PrintWriter pw, String prefix) {
        ArrayList<MediaSessionRecord> sortedSessions = getPriorityList(false,
                UserHandle.USER_ALL);
        int count = sortedSessions.size();
        pw.println(prefix + "Media button session is " + mMediaButtonSession);
        pw.println(prefix + "Sessions Stack - have " + count + " sessions:");
        String indent = prefix + "  ";
        for (int i = 0; i < count; i++) {
            MediaSessionRecord record = sortedSessions.get(i);
            record.dump(pw, indent);
            pw.println();
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
    public ArrayList<MediaSessionRecord> getPriorityList(boolean activeOnly, int userId) {
        ArrayList<MediaSessionRecord> result = new ArrayList<MediaSessionRecord>();
        int lastPlaybackActiveIndex = 0;
        int lastActiveIndex = 0;

        int size = mSessions.size();
        for (int i = 0; i < size; i++) {
            final MediaSessionRecord session = mSessions.get(i);

            if (userId != UserHandle.USER_ALL && userId != session.getUserId()) {
                // Filter out sessions for the wrong user
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

            if (session.isPlaybackActive()) {
                result.add(lastPlaybackActiveIndex++, session);
                lastActiveIndex++;
            } else {
                result.add(lastActiveIndex++, session);
            }
        }

        return result;
    }

    private boolean shouldUpdatePriority(int oldState, int newState) {
        if (containsState(newState, ALWAYS_PRIORITY_STATES)) {
            return true;
        }
        if (!containsState(oldState, TRANSITION_PRIORITY_STATES)
                && containsState(newState, TRANSITION_PRIORITY_STATES)) {
            return true;
        }
        return false;
    }

    private boolean containsState(int state, int[] states) {
        for (int i = 0; i < states.length; i++) {
            if (states[i] == state) {
                return true;
            }
        }
        return false;
    }

    private void clearCache(int userId) {
        mCachedDefault = null;
        mCachedVolumeDefault = null;
        mCachedActiveLists.remove(userId);
        // mCachedActiveLists may also include the list of sessions for UserHandle.USER_ALL,
        // so they also need to be cleared.
        mCachedActiveLists.remove(UserHandle.USER_ALL);
    }
}
