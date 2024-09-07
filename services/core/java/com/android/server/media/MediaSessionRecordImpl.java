/*
 * Copyright 2019 The Android Open Source Project
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

import android.app.ForegroundServiceDelegationOptions;
import android.app.Notification;
import android.media.AudioManager;
import android.media.session.PlaybackState;
import android.os.ResultReceiver;
import android.view.KeyEvent;

import com.android.server.media.MediaSessionPolicyProvider.SessionPolicy;

import java.io.PrintWriter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Common interfaces between {@link MediaSessionRecord} and {@link MediaSession2Record}.
 */
public abstract class MediaSessionRecordImpl {

    private static final AtomicInteger sNextMediaSessionRecordId = new AtomicInteger(1);
    private final int mUniqueId;

    protected MediaSessionRecordImpl() {
        mUniqueId = sNextMediaSessionRecordId.getAndIncrement();
    }

    /**
     * Get the info for this session.
     *
     * @return Info that identifies this session.
     */
    public abstract String getPackageName();

    /**
     * Get the UID this session was created for.
     *
     * @return The UID for this session.
     */
    public abstract int getUid();

    /**
     * Get the user id this session was created for.
     *
     * @return The user id for this session.
     */
    public abstract int getUserId();

    /**
     * Get the {@link ForegroundServiceDelegationOptions} needed for notifying activity manager
     * service with changes in the {@link PlaybackState} for this session.
     *
     * @return the {@link ForegroundServiceDelegationOptions} needed for notifying the activity
     *     manager service with changes in the {@link PlaybackState} for this session.
     */
    public abstract ForegroundServiceDelegationOptions getForegroundServiceDelegationOptions();

    /**
     * Check if this session has system priority and should receive media buttons before any other
     * sessions.
     *
     * @return True if this is a system priority session, false otherwise
     */
    public abstract boolean isSystemPriority();

    /**
     * Send a volume adjustment to the session owner. Direction must be one of
     * {@link AudioManager#ADJUST_LOWER}, {@link AudioManager#ADJUST_RAISE},
     * {@link AudioManager#ADJUST_SAME}.
     *
     * @param packageName The package that made the original volume request.
     * @param opPackageName The op package that made the original volume request.
     * @param pid The pid that made the original volume request.
     * @param uid The uid that made the original volume request.
     * @param asSystemService {@code true} if the event sent to the session as if it was come from
     *          the system service instead of the app process. This helps sessions to distinguish
     *          between the key injection by the app and key events from the hardware devices.
     *          Should be used only when the volume key events aren't handled by foreground
     *          activity. {@code false} otherwise to tell session about the real caller.
     * @param direction The direction to adjust volume in.
     * @param flags Any of the flags from {@link AudioManager}.
     * @param useSuggested True to use adjustSuggestedStreamVolumeForUid instead of
     *          adjustStreamVolumeForUid
     */
    public abstract void adjustVolume(String packageName, String opPackageName, int pid, int uid,
            boolean asSystemService, int direction, int flags, boolean useSuggested);

    /**
     * Check if this session has been set to active by the app. (i.e. ready to receive command and
     * getters are available).
     *
     * @return True if the session is active, false otherwise.
     */
    // TODO(jaewan): Find better naming, or remove this from the MediaSessionRecordImpl.
    public abstract boolean isActive();

    /**
     * Check if the session's playback active state matches with the expectation. This always
     * returns {@code false} if the playback state is unknown (e.g. {@code null}), where we cannot
     * know the actual playback state associated with the session.
     *
     * @param expected True if playback is expected to be active. False otherwise.
     * @return True if the session's playback matches with the expectation. False otherwise.
     */
    public abstract boolean checkPlaybackActiveState(boolean expected);

    /**
     * Check whether the playback type is local or remote.
     * <p>
     * <ul>
     *   <li>Local: volume changes the stream volume because playback happens on this device.</li>
     *   <li>Remote: volume is sent to the app's callback because playback happens on a remote
     *     device and we cannot know how to control its volume.</li>
     * </ul>
     *
     * @return {@code true} if the playback is local. {@code false} if the playback is remote.
     */
    public abstract boolean isPlaybackTypeLocal();

    /**
     * Sends media button.
     *
     * @param packageName caller package name
     * @param pid caller pid
     * @param uid caller uid
     * @param asSystemService {@code true} if the event sent to the session as if it was come from
     *          the system service instead of the app process.
     * @param ke key events
     * @param sequenceId (optional) sequence id. Use this only when a wake lock is needed.
     * @param cb (optional) result receiver to receive callback. Use this only when a wake lock is
     *           needed.
     * @return {@code true} if the attempt to send media button was successfully.
     *         {@code false} otherwise.
     */
    public abstract boolean sendMediaButton(String packageName, int pid, int uid,
            boolean asSystemService, KeyEvent ke, int sequenceId, ResultReceiver cb);

    /**
     * Returns whether the media session can handle volume key events.
     *
     * @return True if this media session can handle volume key events, false otherwise.
     */
    public abstract boolean canHandleVolumeKey();

    /** Returns whether this session is linked to the passed notification. */
    abstract boolean isLinkedToNotification(Notification notification);

    /**
     * Get session policies from custom policy provider set when MediaSessionRecord is instantiated.
     * If custom policy does not exist, will return null.
     */
    @SessionPolicy
    public abstract int getSessionPolicies();

    /**
     * Overwrite session policies that have been set when MediaSessionRecord is instantiated.
     */
    public abstract void setSessionPolicies(@SessionPolicy int policies);

    /**
     * Dumps internal state
     *
     * @param pw print writer
     * @param prefix prefix
     */
    public abstract void dump(PrintWriter pw, String prefix);

    /**
     * Similar to {@link AutoCloseable#close} without throwing an exception.
     */
    public abstract void close();

    /**
     * Get the unique id of this session record.
     *
     * @return a unique id of this session record.
     */
    public int getUniqueId() {
        return mUniqueId;
    }

    /**
     * Returns whether {@link #close()} is called before.
     */
    public abstract boolean isClosed();

    /**
     * Note: This method is only used for testing purposes If the session is temporary engaged, the
     * timeout will expire and it will become disengaged.
     */
    public abstract void expireTempEngaged();

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || !(o instanceof MediaSessionRecordImpl)) return false;
        MediaSessionRecordImpl that = (MediaSessionRecordImpl) o;
        return mUniqueId == that.mUniqueId;
    }

    @Override
    public final int hashCode() {
        return Objects.hash(mUniqueId);
    }
}
