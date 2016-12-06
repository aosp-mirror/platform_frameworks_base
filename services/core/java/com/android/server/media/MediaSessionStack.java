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

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.PlaybackState;
import android.media.session.MediaSession;
import android.os.RemoteException;
import android.os.UserHandle;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps track of media sessions and their priority for notifications, media
 * button dispatch, etc.
 */
class MediaSessionStack {
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

    private final ArrayList<MediaSessionRecord> mSessions = new ArrayList<MediaSessionRecord>();

    private MediaSessionRecord mGlobalPrioritySession;

    // The last record that either entered one of the playing states or was
    // added.
    private MediaSessionRecord mLastInterestingRecord;
    private MediaSessionRecord mCachedButtonReceiver;
    private MediaSessionRecord mCachedDefault;
    private MediaSessionRecord mCachedVolumeDefault;
    private ArrayList<MediaSessionRecord> mCachedActiveList;
    private ArrayList<MediaSessionRecord> mCachedTransportControlList;

    /**
     * Checks if a media session is created from the most recent app.
     *
     * @param record A media session record to be examined.
     * @return {@code true} if the media session's package name equals to the most recent app, false
     *            otherwise.
     */
    private static boolean isFromMostRecentApp(MediaSessionRecord record) {
        try {
            List<ActivityManager.RecentTaskInfo> tasks =
                    ActivityManagerNative.getDefault().getRecentTasks(1,
                            ActivityManager.RECENT_IGNORE_HOME_STACK_TASKS |
                            ActivityManager.RECENT_IGNORE_UNAVAILABLE |
                            ActivityManager.RECENT_INCLUDE_PROFILES |
                            ActivityManager.RECENT_WITH_EXCLUDED, record.getUserId()).getList();
            if (tasks != null && !tasks.isEmpty()) {
                ActivityManager.RecentTaskInfo recentTask = tasks.get(0);
                if (recentTask.userId == record.getUserId() && recentTask.baseIntent != null) {
                    return recentTask.baseIntent.getComponent().getPackageName()
                            .equals(record.getPackageName());
                }
            }
        } catch (RemoteException e) {
            return false;
        }
        return false;
    }

    /**
     * Add a record to the priority tracker.
     *
     * @param record The record to add.
     * @param fromForegroundUser {@code true} if the session is created by the foreground user.
     */
    public void addSession(MediaSessionRecord record, boolean fromForegroundUser) {
        mSessions.add(record);
        clearCache();
        if (fromForegroundUser && isFromMostRecentApp(record)) {
            mLastInterestingRecord = record;
        }
    }

    /**
     * Remove a record from the priority tracker.
     *
     * @param record The record to remove.
     */
    public void removeSession(MediaSessionRecord record) {
        mSessions.remove(record);
        if (record == mGlobalPrioritySession) {
            mGlobalPrioritySession = null;
        }
        clearCache();
    }

    /**
     * Notify the priority tracker that a session's state changed.
     *
     * @param record The record that changed.
     * @param oldState Its old playback state.
     * @param newState Its new playback state.
     * @return true if the priority order was updated, false otherwise.
     */
    public boolean onPlaystateChange(MediaSessionRecord record, int oldState, int newState) {
        if (shouldUpdatePriority(oldState, newState)) {
            mSessions.remove(record);
            mSessions.add(0, record);
            clearCache();
            // This becomes the last interesting record since it entered a
            // playing state
            mLastInterestingRecord = record;
            return true;
        } else if (!MediaSession.isActiveState(newState)) {
            // Just clear the volume cache when a state goes inactive
            mCachedVolumeDefault = null;
        }
        return false;
    }

    /**
     * Handle any stack changes that need to occur in response to a session
     * state change. TODO add the old and new session state as params
     *
     * @param record The record that changed.
     */
    public void onSessionStateChange(MediaSessionRecord record) {
        if ((record.getFlags() & MediaSession.FLAG_EXCLUSIVE_GLOBAL_PRIORITY) != 0) {
            mGlobalPrioritySession = record;
        }
        // For now just clear the cache. Eventually we'll selectively clear
        // depending on what changed.
        clearCache();
    }

    /**
     * Get the current priority sorted list of active sessions. The most
     * important session is at index 0 and the least important at size - 1.
     *
     * @param userId The user to check.
     * @return All the active sessions in priority order.
     */
    public ArrayList<MediaSessionRecord> getActiveSessions(int userId) {
        if (mCachedActiveList == null) {
            mCachedActiveList = getPriorityListLocked(true, 0, userId);
        }
        return mCachedActiveList;
    }

    /**
     * Get the current priority sorted list of active sessions that use
     * transport controls. The most important session is at index 0 and the
     * least important at size -1.
     *
     * @param userId The user to check.
     * @return All the active sessions that handle transport controls in
     *         priority order.
     */
    public ArrayList<MediaSessionRecord> getTransportControlSessions(int userId) {
        if (mCachedTransportControlList == null) {
            mCachedTransportControlList = getPriorityListLocked(true,
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS, userId);
        }
        return mCachedTransportControlList;
    }

    /**
     * Get the highest priority active session.
     *
     * @param userId The user to check.
     * @return The current highest priority session or null.
     */
    public MediaSessionRecord getDefaultSession(int userId) {
        if (mCachedDefault != null) {
            return mCachedDefault;
        }
        ArrayList<MediaSessionRecord> records = getPriorityListLocked(true, 0, userId);
        if (records.size() > 0) {
            return records.get(0);
        }
        return null;
    }

    /**
     * Get the highest priority session that can handle media buttons.
     *
     * @param userIdList The user lists to check.
     * @param includeNotPlaying Return a non-playing session if nothing else is
     *            available
     * @return The default media button session or null.
     */
    public MediaSessionRecord getDefaultMediaButtonSession(
            List<Integer> userIdList, boolean includeNotPlaying) {
        if (mGlobalPrioritySession != null && mGlobalPrioritySession.isActive()) {
            return mGlobalPrioritySession;
        }
        if (mCachedButtonReceiver != null) {
            return mCachedButtonReceiver;
        }
        ArrayList<MediaSessionRecord> records = getPriorityListLocked(true,
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS, userIdList);
        if (records.size() > 0) {
            MediaSessionRecord record = records.get(0);
            if (record.isPlaybackActive(false)) {
                // Since we're going to send a button event to this record make
                // it the last interesting one.
                mLastInterestingRecord = record;
                mCachedButtonReceiver = record;
            } else if (mLastInterestingRecord != null) {
                if (records.contains(mLastInterestingRecord)) {
                    mCachedButtonReceiver = mLastInterestingRecord;
                } else {
                    // That record is no longer used. Clear its reference.
                    mLastInterestingRecord = null;
                }
            }
            if (includeNotPlaying && mCachedButtonReceiver == null) {
                // If we really want a record and we didn't find one yet use the
                // highest priority session even if it's not playing.
                mCachedButtonReceiver = record;
            }
        }
        return mCachedButtonReceiver;
    }

    public MediaSessionRecord getDefaultVolumeSession(List<Integer> userIdList) {
        if (mGlobalPrioritySession != null && mGlobalPrioritySession.isActive()) {
            return mGlobalPrioritySession;
        }
        if (mCachedVolumeDefault != null) {
            return mCachedVolumeDefault;
        }
        ArrayList<MediaSessionRecord> records = getPriorityListLocked(true, 0, userIdList);
        int size = records.size();
        for (int i = 0; i < size; i++) {
            MediaSessionRecord record = records.get(i);
            if (record.isPlaybackActive(false)) {
                mCachedVolumeDefault = record;
                return record;
            }
        }
        return null;
    }

    public MediaSessionRecord getDefaultRemoteSession(int userId) {
        ArrayList<MediaSessionRecord> records = getPriorityListLocked(true, 0, userId);

        int size = records.size();
        for (int i = 0; i < size; i++) {
            MediaSessionRecord record = records.get(i);
            if (record.getPlaybackType() == PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
                return record;
            }
        }
        return null;
    }

    public boolean isGlobalPriorityActive() {
        return mGlobalPrioritySession == null ? false : mGlobalPrioritySession.isActive();
    }

    public void dump(PrintWriter pw, String prefix) {
        ArrayList<MediaSessionRecord> sortedSessions = getPriorityListLocked(false, 0,
                UserHandle.USER_ALL);
        int count = sortedSessions.size();
        pw.println(prefix + "Global priority session is " + mGlobalPrioritySession);
        pw.println(prefix + "Sessions Stack - have " + count + " sessions:");
        String indent = prefix + "  ";
        for (int i = 0; i < count; i++) {
            MediaSessionRecord record = sortedSessions.get(i);
            record.dump(pw, indent);
            pw.println();
        }
    }

    private ArrayList<MediaSessionRecord> getPriorityListLocked(boolean activeOnly, int withFlags,
            int userId) {
        List<Integer> userIdList = new ArrayList<>();
        userIdList.add(userId);
        return getPriorityListLocked(activeOnly, withFlags, userIdList);
    }

    /**
     * Get a priority sorted list of sessions. Can filter to only return active
     * sessions or sessions with specific flags.
     *
     * @param activeOnly True to only return active sessions, false to return
     *            all sessions.
     * @param withFlags Only return sessions with all the specified flags set. 0
     *            returns all sessions.
     * @param userIdList The user to get sessions for. {@link UserHandle#USER_ALL}
     *            will return sessions for all users.
     * @return The priority sorted list of sessions.
     */
    private ArrayList<MediaSessionRecord> getPriorityListLocked(boolean activeOnly, int withFlags,
            List<Integer> userIdList) {
        ArrayList<MediaSessionRecord> result = new ArrayList<MediaSessionRecord>();
        int lastLocalIndex = 0;
        int lastActiveIndex = 0;
        int lastPublishedIndex = 0;

        boolean filterUser = !userIdList.contains(UserHandle.USER_ALL);
        int size = mSessions.size();
        for (int i = 0; i < size; i++) {
            final MediaSessionRecord session = mSessions.get(i);

            if (filterUser && !userIdList.contains(session.getUserId())) {
                // Filter out sessions for the wrong user
                continue;
            }
            if ((session.getFlags() & withFlags) != withFlags) {
                // Filter out sessions with the wrong flags
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

            if (session.isSystemPriority()) {
                // System priority sessions are special and always go at the
                // front. We expect there to only be one of these at a time.
                result.add(0, session);
                lastLocalIndex++;
                lastActiveIndex++;
                lastPublishedIndex++;
            } else if (session.isPlaybackActive(true)) {
                // TODO this with real local route check
                if (true) {
                    // Active local sessions get top priority
                    result.add(lastLocalIndex, session);
                    lastLocalIndex++;
                    lastActiveIndex++;
                    lastPublishedIndex++;
                } else {
                    // Then active remote sessions
                    result.add(lastActiveIndex, session);
                    lastActiveIndex++;
                    lastPublishedIndex++;
                }
            } else {
                // inactive sessions go at the end in order of whoever last did
                // something.
                result.add(lastPublishedIndex, session);
                lastPublishedIndex++;
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

    private void clearCache() {
        mCachedDefault = null;
        mCachedVolumeDefault = null;
        mCachedButtonReceiver = null;
        mCachedActiveList = null;
        mCachedTransportControlList = null;
    }
}
