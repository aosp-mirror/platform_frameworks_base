/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.media;

/**
 * The MediaSyncEvent class defines events that can be used to synchronize playback or capture
 * actions between different players and recorders.
 * <p>For instance, {@link AudioRecord#startRecording(MediaSyncEvent)} is used to start capture
 * only when the playback on a particular audio session is complete.
 * The audio session ID is retrieved from a player (e.g {@link MediaPlayer}, {@link AudioTrack} or
 * {@link ToneGenerator}) by use of the getAudioSessionId() method.
 */
public class MediaSyncEvent {

    /**
     * No sync event specified. When used with a synchronized playback or capture method, the
     * behavior is equivalent to calling the corresponding non synchronized method.
     */
    public static final int SYNC_EVENT_NONE = AudioSystem.SYNC_EVENT_NONE;

    /**
     * The corresponding action is triggered only when the presentation is completed
     * (meaning the media has been presented to the user) on the specified session.
     * A synchronization of this type requires a source audio session ID to be set via
     * {@link #setAudioSessionId(int)} method.
     */
    public static final int SYNC_EVENT_PRESENTATION_COMPLETE =
                                                    AudioSystem.SYNC_EVENT_PRESENTATION_COMPLETE;


    /**
     * Creates a synchronization event of the sepcified type.
     *
     * <p>The type specifies which kind of event is monitored.
     * For instance, event {@link #SYNC_EVENT_PRESENTATION_COMPLETE} corresponds to the audio being
     * presented to the user on a particular audio session.
     * @param eventType the synchronization event type.
     * @return the MediaSyncEvent created.
     * @throws java.lang.IllegalArgumentException
     */
    public static MediaSyncEvent createEvent(int eventType)
                            throws IllegalArgumentException {
        if (!isValidType(eventType)) {
            throw (new IllegalArgumentException(eventType
                    + "is not a valid MediaSyncEvent type."));
        } else {
            return new MediaSyncEvent(eventType);
        }
    }

    private final int mType;
    private int mAudioSession = 0;

    private MediaSyncEvent(int eventType) {
        mType = eventType;
    }

    /**
     * Sets the event source audio session ID.
     *
     * <p>The audio session ID specifies on which audio session the synchronization event should be
     * monitored.
     * It is mandatory for certain event types (e.g. {@link #SYNC_EVENT_PRESENTATION_COMPLETE}).
     * For instance, the audio session ID can be retrieved via
     * {@link MediaPlayer#getAudioSessionId()} when monitoring an event on a particular MediaPlayer.
     * @param audioSessionId the audio session ID of the event source being monitored.
     * @return the MediaSyncEvent the method is called on.
     * @throws java.lang.IllegalArgumentException
     */
    public MediaSyncEvent setAudioSessionId(int audioSessionId)
            throws IllegalArgumentException {
        if (audioSessionId > 0) {
            mAudioSession = audioSessionId;
        } else {
            throw (new IllegalArgumentException(audioSessionId + " is not a valid session ID."));
        }
        return this;
    }

    /**
     * Gets the synchronization event type.
     *
     * @return the synchronization event type.
     */
    public int getType() {
        return mType;
    }

    /**
     * Gets the synchronization event audio session ID.
     *
     * @return the synchronization audio session ID. The returned audio session ID is 0 if it has
     * not been set.
     */
    public int getAudioSessionId() {
        return mAudioSession;
    }

    private static boolean isValidType(int type) {
        switch (type) {
        case SYNC_EVENT_NONE:
        case SYNC_EVENT_PRESENTATION_COMPLETE:
            return true;
        default:
            return false;
        }
    }
}
