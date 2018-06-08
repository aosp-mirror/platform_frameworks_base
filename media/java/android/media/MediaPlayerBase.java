/*
 * Copyright 2018 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * @hide
 * Base class for all media players that want media session.
 */
public abstract class MediaPlayerBase implements AutoCloseable {
    /**
     * @hide
     */
    @IntDef({
        PLAYER_STATE_IDLE,
        PLAYER_STATE_PAUSED,
        PLAYER_STATE_PLAYING,
        PLAYER_STATE_ERROR })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PlayerState {}

    /**
     * @hide
     */
    @IntDef({
        BUFFERING_STATE_UNKNOWN,
        BUFFERING_STATE_BUFFERING_AND_PLAYABLE,
        BUFFERING_STATE_BUFFERING_AND_STARVED,
        BUFFERING_STATE_BUFFERING_COMPLETE })
    @Retention(RetentionPolicy.SOURCE)
    public @interface BuffState {}

    /**
     * State when the player is idle, and needs configuration to start playback.
     */
    public static final int PLAYER_STATE_IDLE = 0;

    /**
     * State when the player's playback is paused
     */
    public static final int PLAYER_STATE_PAUSED = 1;

    /**
     * State when the player's playback is ongoing
     */
    public static final int PLAYER_STATE_PLAYING = 2;

    /**
     * State when the player is in error state and cannot be recovered self.
     */
    public static final int PLAYER_STATE_ERROR = 3;

    /**
     * Buffering state is unknown.
     */
    public static final int BUFFERING_STATE_UNKNOWN = 0;

    /**
     * Buffering state indicating the player is buffering but enough has been buffered
     * for this player to be able to play the content.
     * See {@link #getBufferedPosition()} for how far is buffered already.
     */
    public static final int BUFFERING_STATE_BUFFERING_AND_PLAYABLE = 1;

    /**
     * Buffering state indicating the player is buffering, but the player is currently starved
     * for data, and cannot play.
     */
    public static final int BUFFERING_STATE_BUFFERING_AND_STARVED = 2;

    /**
     * Buffering state indicating the player is done buffering, and the remainder of the content is
     * available for playback.
     */
    public static final int BUFFERING_STATE_BUFFERING_COMPLETE = 3;

    /**
     * Starts or resumes playback.
     */
    public abstract void play();

    /**
     * Prepares the player for playback.
     * See {@link PlayerEventCallback#onMediaPrepared(MediaPlayerBase, DataSourceDesc)} for being
     * notified when the preparation phase completed. During this time, the player may allocate
     * resources required to play, such as audio and video decoders.
     */
    public abstract void prepare();

    /**
     * Pauses playback.
     */
    public abstract void pause();

    /**
     * Resets the MediaPlayerBase to its uninitialized state.
     */
    public abstract void reset();

    /**
     *
     */
    public abstract void skipToNext();

    /**
     * Moves the playback head to the specified position
     * @param pos the new playback position expressed in ms.
     */
    public abstract void seekTo(long pos);

    public static final long UNKNOWN_TIME = -1;

    /**
     * Gets the current playback head position.
     * @return the current playback position in ms, or {@link #UNKNOWN_TIME} if unknown.
     */
    public long getCurrentPosition() { return UNKNOWN_TIME; }

    /**
     * Returns the duration of the current data source, or {@link #UNKNOWN_TIME} if unknown.
     * @return the duration in ms, or {@link #UNKNOWN_TIME}.
     */
    public long getDuration() { return UNKNOWN_TIME; }

    /**
     * Gets the buffered position of current playback, or {@link #UNKNOWN_TIME} if unknown.
     * @return the buffered position in ms, or {@link #UNKNOWN_TIME}.
     */
    public long getBufferedPosition() { return UNKNOWN_TIME; }

    /**
     * Returns the current player state.
     * See also {@link PlayerEventCallback#onPlayerStateChanged(MediaPlayerBase, int)} for
     * notification of changes.
     * @return the current player state
     */
    public abstract @PlayerState int getPlayerState();

    /**
     * Returns the current buffering state of the player.
     * During buffering, see {@link #getBufferedPosition()} for the quantifying the amount already
     * buffered.
     * @return the buffering state.
     */
    public abstract @BuffState int getBufferingState();

    /**
     * Sets the {@link AudioAttributes} to be used during the playback of the media.
     *
     * @param attributes non-null <code>AudioAttributes</code>.
     */
    public abstract void setAudioAttributes(@NonNull AudioAttributes attributes);

    /**
     * Returns AudioAttributes that media player has.
     */
    public abstract @Nullable AudioAttributes getAudioAttributes();

    /**
     * Sets the data source to be played.
     * @param dsd
     */
    public abstract void setDataSource(@NonNull DataSourceDesc dsd);

    /**
     * Sets the data source that will be played immediately after the current one is done playing.
     * @param dsd
     */
    public abstract void setNextDataSource(@NonNull DataSourceDesc dsd);

    /**
     * Sets the list of data sources that will be sequentially played after the current one. Each
     * data source is played immediately after the previous one is done playing.
     * @param dsds
     */
    public abstract void setNextDataSources(@NonNull List<DataSourceDesc> dsds);

    /**
     * Returns the current data source.
     * @return the current data source, or null if none is set, or none available to play.
     */
    public abstract @Nullable DataSourceDesc getCurrentDataSource();

    /**
     * Configures the player to loop on the current data source.
     * @param loop true if the current data source is meant to loop.
     */
    public abstract void loopCurrent(boolean loop);

    /**
     * Sets the playback speed.
     * A value of 1.0f is the default playback value.
     * A negative value indicates reverse playback, check {@link #isReversePlaybackSupported()}
     * before using negative values.<br>
     * After changing the playback speed, it is recommended to query the actual speed supported
     * by the player, see {@link #getPlaybackSpeed()}.
     * @param speed
     */
    public abstract void setPlaybackSpeed(float speed);

    /**
     * Returns the actual playback speed to be used by the player when playing.
     * Note that it may differ from the speed set in {@link #setPlaybackSpeed(float)}.
     * @return the actual playback speed
     */
    public float getPlaybackSpeed() { return 1.0f; }

    /**
     * Indicates whether reverse playback is supported.
     * Reverse playback is indicated by negative playback speeds, see
     * {@link #setPlaybackSpeed(float)}.
     * @return true if reverse playback is supported.
     */
    public boolean isReversePlaybackSupported() { return false; }

    /**
     * Sets the volume of the audio of the media to play, expressed as a linear multiplier
     * on the audio samples.
     * Note that this volume is specific to the player, and is separate from stream volume
     * used across the platform.<br>
     * A value of 0.0f indicates muting, a value of 1.0f is the nominal unattenuated and unamplified
     * gain. See {@link #getMaxPlayerVolume()} for the volume range supported by this player.
     * @param volume a value between 0.0f and {@link #getMaxPlayerVolume()}.
     */
    public abstract void setPlayerVolume(float volume);

    /**
     * Returns the current volume of this player to this player.
     * Note that it does not take into account the associated stream volume.
     * @return the player volume.
     */
    public abstract float getPlayerVolume();

    /**
     * @return the maximum volume that can be used in {@link #setPlayerVolume(float)}.
     */
    public float getMaxPlayerVolume() { return 1.0f; }

    /**
     * Adds a callback to be notified of events for this player.
     * @param e the {@link Executor} to be used for the events.
     * @param cb the callback to receive the events.
     */
    public abstract void registerPlayerEventCallback(@NonNull Executor e,
            @NonNull PlayerEventCallback cb);

    /**
     * Removes a previously registered callback for player events
     * @param cb the callback to remove
     */
    public abstract void unregisterPlayerEventCallback(@NonNull PlayerEventCallback cb);

    /**
     * A callback class to receive notifications for events on the media player.
     * See {@link MediaPlayerBase#registerPlayerEventCallback(Executor, PlayerEventCallback)} to
     * register this callback.
     */
    public static abstract class PlayerEventCallback {
        /**
         * Called when the player's current data source has changed.
         *
         * @param mpb the player whose data source changed.
         * @param dsd the new current data source. null, if no more data sources available.
         */
        public void onCurrentDataSourceChanged(@NonNull MediaPlayerBase mpb,
                @Nullable DataSourceDesc dsd) { }
        /**
         * Called when the player is <i>prepared</i>, i.e. it is ready to play the content
         * referenced by the given data source.
         * @param mpb the player that is prepared.
         * @param dsd the data source that the player is prepared to play.
         */
        public void onMediaPrepared(@NonNull MediaPlayerBase mpb, @NonNull DataSourceDesc dsd) { }

        /**
         * Called to indicate that the state of the player has changed.
         * See {@link MediaPlayerBase#getPlayerState()} for polling the player state.
         * @param mpb the player whose state has changed.
         * @param state the new state of the player.
         */
        public void onPlayerStateChanged(@NonNull MediaPlayerBase mpb, @PlayerState int state) { }

        /**
         * Called to report buffering events for a data source.
         * @param mpb the player that is buffering
         * @param dsd the data source for which buffering is happening.
         * @param state the new buffering state.
         */
        public void onBufferingStateChanged(@NonNull MediaPlayerBase mpb,
                @NonNull DataSourceDesc dsd, @BuffState int state) { }

        /**
         * Called to indicate that the playback speed has changed.
         * @param mpb the player that has changed the playback speed.
         * @param speed the new playback speed.
         */
        public void onPlaybackSpeedChanged(@NonNull MediaPlayerBase mpb, float speed) { }

        /**
         * Called to indicate that {@link #seekTo(long)} is completed.
         *
         * @param mpb the player that has completed seeking.
         * @param position the previous seeking request.
         * @see #seekTo(long)
         */
        public void onSeekCompleted(@NonNull MediaPlayerBase mpb, long position) { }
    }

}
