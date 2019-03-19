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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.VolumeProvider;
import android.media.session.MediaSessionManager.RemoteUserInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.service.media.MediaBrowserService;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Objects;

/**
 * Allows interaction with media controllers, volume keys, media buttons, and
 * transport controls.
 * <p>
 * A MediaSession should be created when an app wants to publish media playback
 * information or handle media keys. In general an app only needs one session
 * for all playback, though multiple sessions can be created to provide finer
 * grain controls of media.
 * <p>
 * Once a session is created the owner of the session may pass its
 * {@link #getSessionToken() session token} to other processes to allow them to
 * create a {@link MediaController} to interact with the session.
 * <p>
 * To receive commands, media keys, and other events a {@link Callback} must be
 * set with {@link #setCallback(Callback)} and {@link #setActive(boolean)
 * setActive(true)} must be called.
 * <p>
 * When an app is finished performing playback it must call {@link #release()}
 * to clean up the session and notify any controllers.
 * <p>
 * MediaSession objects are thread safe.
 */
public final class MediaSession {
    static final String TAG = "MediaSession";

    /**
     * Set this flag on the session to indicate that it can handle media button
     * events.
     * @deprecated This flag is no longer used. All media sessions are expected to handle media
     * button events now.
     */
    @Deprecated
    public static final int FLAG_HANDLES_MEDIA_BUTTONS = 1 << 0;

    /**
     * Set this flag on the session to indicate that it handles transport
     * control commands through its {@link Callback}.
     * @deprecated This flag is no longer used. All media sessions are expected to handle transport
     * controls now.
     */
    @Deprecated
    public static final int FLAG_HANDLES_TRANSPORT_CONTROLS = 1 << 1;

    /**
     * System only flag for a session that needs to have priority over all other
     * sessions. This flag ensures this session will receive media button events
     * regardless of the current ordering in the system.
     *
     * @hide
     */
    public static final int FLAG_EXCLUSIVE_GLOBAL_PRIORITY = 1 << 16;

    /**
     * @hide
     */
    public static final int INVALID_UID = -1;

    /**
     * @hide
     */
    public static final int INVALID_PID = -1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            FLAG_HANDLES_MEDIA_BUTTONS,
            FLAG_HANDLES_TRANSPORT_CONTROLS,
            FLAG_EXCLUSIVE_GLOBAL_PRIORITY })
    public @interface SessionFlags { }

    private final MediaSessionEngine mImpl;
    private final int mMaxBitmapSize;

    // Do not change the name of mCallback. Support lib accesses this by using reflection.
    @UnsupportedAppUsage
    private Object mCallback;

    /**
     * Creates a new session. The session will automatically be registered with
     * the system but will not be published until {@link #setActive(boolean)
     * setActive(true)} is called. You must call {@link #release()} when
     * finished with the session.
     *
     * @param context The context to use to create the session.
     * @param tag A short name for debugging purposes.
     */
    public MediaSession(@NonNull Context context, @NonNull String tag) {
        this(context, tag, null);
    }

    /**
     * Creates a new session. The session will automatically be registered with
     * the system but will not be published until {@link #setActive(boolean)
     * setActive(true)} is called. You must call {@link #release()} when
     * finished with the session.
     * <p>
     * The {@code sessionInfo} can include additional unchanging information about this session.
     * For example, it can include the version of the application, or the list of the custom
     * commands that this session supports.
     *
     * @param context The context to use to create the session.
     * @param tag A short name for debugging purposes.
     * @param sessionInfo A bundle for additional information about this session.
     *                    Controllers can get this information by calling
     *                    {@link MediaController#getSessionInfo()}.
     */
    public MediaSession(@NonNull Context context, @NonNull String tag,
            @Nullable Bundle sessionInfo) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null.");
        }
        if (TextUtils.isEmpty(tag)) {
            throw new IllegalArgumentException("tag cannot be null or empty");
        }
        MediaSessionManager manager = (MediaSessionManager) context
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            SessionCallbackLink cbLink = new SessionCallbackLink(context);
            ISession binder = manager.createSession(cbLink, tag, sessionInfo);
            mImpl = new MediaSessionEngine(context, binder, cbLink);
            mMaxBitmapSize = context.getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.config_mediaMetadataBitmapMaxSize);
        } catch (RemoteException e) {
            throw new RuntimeException("Remote error creating session.", e);
        }
    }

    /**
     * Set the callback to receive updates for the MediaSession. This includes
     * media button events and transport controls. The caller's thread will be
     * used to post updates.
     * <p>
     * Set the callback to null to stop receiving updates.
     *
     * @param callback The callback object
     */
    public void setCallback(@Nullable Callback callback) {
        mCallback = callback == null ? null : new Object();
        mImpl.setCallback(callback);
    }

    /**
     * Set the callback to receive updates for the MediaSession. This includes
     * media button events and transport controls.
     * <p>
     * Set the callback to null to stop receiving updates.
     *
     * @param callback The callback to receive updates on.
     * @param handler The handler that events should be posted on.
     */
    public void setCallback(@Nullable Callback callback, @Nullable Handler handler) {
        mCallback = callback == null ? null : new Object();
        mImpl.setCallback(callback, handler);
    }

    /**
     * Set an intent for launching UI for this Session. This can be used as a
     * quick link to an ongoing media screen. The intent should be for an
     * activity that may be started using {@link Activity#startActivity(Intent)}.
     *
     * @param pi The intent to launch to show UI for this Session.
     */
    public void setSessionActivity(@Nullable PendingIntent pi) {
        mImpl.setSessionActivity(pi);
    }

    /**
     * Set a pending intent for your media button receiver to allow restarting
     * playback after the session has been stopped. If your app is started in
     * this way an {@link Intent#ACTION_MEDIA_BUTTON} intent will be sent via
     * the pending intent.
     *
     * @param mbr The {@link PendingIntent} to send the media button event to.
     */
    public void setMediaButtonReceiver(@Nullable PendingIntent mbr) {
        mImpl.setMediaButtonReceiver(mbr);
    }

    /**
     * Set any flags for the session.
     *
     * @param flags The flags to set for this session.
     */
    public void setFlags(@SessionFlags int flags) {
        mImpl.setFlags(flags);
    }

    /**
     * Set the attributes for this session's audio. This will affect the
     * system's volume handling for this session. If
     * {@link #setPlaybackToRemote} was previously called it will stop receiving
     * volume commands and the system will begin sending volume changes to the
     * appropriate stream.
     * <p>
     * By default sessions use attributes for media.
     *
     * @param attributes The {@link AudioAttributes} for this session's audio.
     */
    public void setPlaybackToLocal(AudioAttributes attributes) {
        mImpl.setPlaybackToLocal(attributes);
    }

    /**
     * Configure this session to use remote volume handling. This must be called
     * to receive volume button events, otherwise the system will adjust the
     * appropriate stream volume for this session. If
     * {@link #setPlaybackToLocal} was previously called the system will stop
     * handling volume changes for this session and pass them to the volume
     * provider instead.
     *
     * @param volumeProvider The provider that will handle volume changes. May
     *            not be null.
     */
    public void setPlaybackToRemote(@NonNull VolumeProvider volumeProvider) {
        mImpl.setPlaybackToRemote(volumeProvider);
    }

    /**
     * Set if this session is currently active and ready to receive commands. If
     * set to false your session's controller may not be discoverable. You must
     * set the session to active before it can start receiving media button
     * events or transport commands.
     *
     * @param active Whether this session is active or not.
     */
    public void setActive(boolean active) {
        mImpl.setActive(active);
    }

    /**
     * Get the current active state of this session.
     *
     * @return True if the session is active, false otherwise.
     */
    public boolean isActive() {
        return mImpl.isActive();
    }

    /**
     * Send a proprietary event to all MediaControllers listening to this
     * Session. It's up to the Controller/Session owner to determine the meaning
     * of any events.
     *
     * @param event The name of the event to send
     * @param extras Any extras included with the event
     */
    public void sendSessionEvent(@NonNull String event, @Nullable Bundle extras) {
        mImpl.sendSessionEvent(event, extras);
    }

    /**
     * This must be called when an app has finished performing playback. If
     * playback is expected to start again shortly the session can be left open,
     * but it must be released if your activity or service is being destroyed.
     */
    public void release() {
        mImpl.close();
    }

    /**
     * Retrieve a token object that can be used by apps to create a
     * {@link MediaController} for interacting with this session. The owner of
     * the session is responsible for deciding how to distribute these tokens.
     *
     * @return A token that can be used to create a MediaController for this
     *         session
     */
    public @NonNull Token getSessionToken() {
        return mImpl.getSessionToken();
    }

    /**
     * Get a controller for this session. This is a convenience method to avoid
     * having to cache your own controller in process.
     *
     * @return A controller for this session.
     */
    public @NonNull MediaController getController() {
        return mImpl.getController();
    }

    /**
     * Update the current playback state.
     *
     * @param state The current state of playback
     */
    public void setPlaybackState(@Nullable PlaybackState state) {
        mImpl.setPlaybackState(state);
    }

    /**
     * Update the current metadata. New metadata can be created using
     * {@link android.media.MediaMetadata.Builder}. This operation may take time proportional to
     * the size of the bitmap to replace large bitmaps with a scaled down copy.
     *
     * @param metadata The new metadata
     * @see android.media.MediaMetadata.Builder#putBitmap
     */
    public void setMetadata(@Nullable MediaMetadata metadata) {
        if (metadata != null) {
            metadata = new MediaMetadata.Builder(metadata, mMaxBitmapSize).build();
        }
        mImpl.setMetadata(metadata);
    }

    /**
     * Update the list of items in the play queue. It is an ordered list and
     * should contain the current item, and previous or upcoming items if they
     * exist. Specify null if there is no current play queue.
     * <p>
     * The queue should be of reasonable size. If the play queue is unbounded
     * within your app, it is better to send a reasonable amount in a sliding
     * window instead.
     *
     * @param queue A list of items in the play queue.
     */
    public void setQueue(@Nullable List<QueueItem> queue) {
        mImpl.setQueue(queue);
    }

    /**
     * Set the title of the play queue. The UI should display this title along
     * with the play queue itself.
     * e.g. "Play Queue", "Now Playing", or an album name.
     *
     * @param title The title of the play queue.
     */
    public void setQueueTitle(@Nullable CharSequence title) {
        mImpl.setQueueTitle(title);
    }

    /**
     * Set the style of rating used by this session. Apps trying to set the
     * rating should use this style. Must be one of the following:
     * <ul>
     * <li>{@link Rating#RATING_NONE}</li>
     * <li>{@link Rating#RATING_3_STARS}</li>
     * <li>{@link Rating#RATING_4_STARS}</li>
     * <li>{@link Rating#RATING_5_STARS}</li>
     * <li>{@link Rating#RATING_HEART}</li>
     * <li>{@link Rating#RATING_PERCENTAGE}</li>
     * <li>{@link Rating#RATING_THUMB_UP_DOWN}</li>
     * </ul>
     */
    public void setRatingType(@Rating.Style int type) {
        mImpl.setRatingType(type);
    }

    /**
     * Set some extras that can be associated with the {@link MediaSession}. No assumptions should
     * be made as to how a {@link MediaController} will handle these extras.
     * Keys should be fully qualified (e.g. com.example.MY_EXTRA) to avoid conflicts.
     *
     * @param extras The extras associated with the {@link MediaSession}.
     */
    public void setExtras(@Nullable Bundle extras) {
        mImpl.setExtras(extras);
    }

    /**
     * Gets the controller information who sent the current request.
     * <p>
     * Note: This is only valid while in a request callback, such as {@link Callback#onPlay}.
     *
     * @throws IllegalStateException If this method is called outside of {@link Callback} methods.
     * @see MediaSessionManager#isTrustedForMediaControl(RemoteUserInfo)
     */
    public final @NonNull RemoteUserInfo getCurrentControllerInfo() {
        return mImpl.getCurrentControllerInfo();
    }

    /**
     * Returns the name of the package that sent the last media button, transport control, or
     * command from controllers and the system. This is only valid while in a request callback, such
     * as {@link Callback#onPlay}.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public String getCallingPackage() {
        return mImpl.getCallingPackage();
    }

    /**
     * Return true if this is considered an active playback state.
     *
     * @hide
     */
    public static boolean isActiveState(int state) {
        return MediaSessionEngine.isActiveState(state);
    }

    /**
     * Represents an ongoing session. This may be passed to apps by the session
     * owner to allow them to create a {@link MediaController} to communicate with
     * the session.
     */
    public static final class Token implements Parcelable {

        private final int mUid;
        private final ISessionController mBinder;

        /**
         * @hide
         */
        public Token(ISessionController binder) {
            mUid = Process.myUid();
            mBinder = binder;
        }

        Token(Parcel in) {
            mUid = in.readInt();
            mBinder = ISessionController.Stub.asInterface(in.readStrongBinder());
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mUid);
            dest.writeStrongBinder(mBinder.asBinder());
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = mUid;
            result = prime * result + (mBinder == null ? 0 : mBinder.asBinder().hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Token other = (Token) obj;
            if (mUid != other.mUid) {
                return false;
            }
            if (mBinder == null || other.mBinder == null) {
                return mBinder == other.mBinder;
            }
            return Objects.equals(mBinder.asBinder(), other.mBinder.asBinder());
        }

        /**
         * Gets the UID of this token.
         * @hide
         */
        public int getUid() {
            return mUid;
        }

        /**
         * Gets the controller binder in this token.
         * @hide
         */
        public ISessionController getBinder() {
            return mBinder;
        }

        public static final @android.annotation.NonNull Parcelable.Creator<Token> CREATOR =
                new Parcelable.Creator<Token>() {
            @Override
            public Token createFromParcel(Parcel in) {
                return new Token(in);
            }

            @Override
            public Token[] newArray(int size) {
                return new Token[size];
            }
        };
    }

    /**
     * Receives media buttons, transport controls, and commands from controllers
     * and the system. A callback may be set using {@link #setCallback}.
     */
    public abstract static class Callback {

        MediaSessionEngine.MediaButtonEventDelegate mMediaButtonEventDelegate;

        public Callback() {
        }

        /**
         * Called when a controller has sent a command to this session.
         * The owner of the session may handle custom commands but is not
         * required to.
         *
         * @param command The command name.
         * @param args Optional parameters for the command, may be null.
         * @param cb A result receiver to which a result may be sent by the command, may be null.
         */
        public void onCommand(@NonNull String command, @Nullable Bundle args,
                @Nullable ResultReceiver cb) {
        }

        /**
         * Called when a media button is pressed and this session has the
         * highest priority or a controller sends a media button event to the
         * session. The default behavior will call the relevant method if the
         * action for it was set.
         * <p>
         * The intent will be of type {@link Intent#ACTION_MEDIA_BUTTON} with a
         * KeyEvent in {@link Intent#EXTRA_KEY_EVENT}
         *
         * @param mediaButtonIntent an intent containing the KeyEvent as an
         *            extra
         * @return True if the event was handled, false otherwise.
         */
        public boolean onMediaButtonEvent(@NonNull Intent mediaButtonIntent) {
            if (mMediaButtonEventDelegate != null) {
                return mMediaButtonEventDelegate.onMediaButtonIntent(mediaButtonIntent);
            }
            return false;
        }

        /**
         * Override to handle requests to prepare playback. During the preparation, a session should
         * not hold audio focus in order to allow other sessions play seamlessly. The state of
         * playback should be updated to {@link PlaybackState#STATE_PAUSED} after the preparation is
         * done.
         */
        public void onPrepare() {
        }

        /**
         * Override to handle requests to prepare for playing a specific mediaId that was provided
         * by your app's {@link MediaBrowserService}. During the preparation, a session should not
         * hold audio focus in order to allow other sessions play seamlessly. The state of playback
         * should be updated to {@link PlaybackState#STATE_PAUSED} after the preparation is done.
         * The playback of the prepared content should start in the implementation of
         * {@link #onPlay}. Override {@link #onPlayFromMediaId} to handle requests for starting
         * playback without preparation.
         */
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
        }

        /**
         * Override to handle requests to prepare playback from a search query. An empty query
         * indicates that the app may prepare any music. The implementation should attempt to make a
         * smart choice about what to play. During the preparation, a session should not hold audio
         * focus in order to allow other sessions play seamlessly. The state of playback should be
         * updated to {@link PlaybackState#STATE_PAUSED} after the preparation is done. The playback
         * of the prepared content should start in the implementation of {@link #onPlay}. Override
         * {@link #onPlayFromSearch} to handle requests for starting playback without preparation.
         */
        public void onPrepareFromSearch(String query, Bundle extras) {
        }

        /**
         * Override to handle requests to prepare a specific media item represented by a URI.
         * During the preparation, a session should not hold audio focus in order to allow
         * other sessions play seamlessly. The state of playback should be updated to
         * {@link PlaybackState#STATE_PAUSED} after the preparation is done.
         * The playback of the prepared content should start in the implementation of
         * {@link #onPlay}. Override {@link #onPlayFromUri} to handle requests
         * for starting playback without preparation.
         */
        public void onPrepareFromUri(Uri uri, Bundle extras) {
        }

        /**
         * Override to handle requests to begin playback.
         */
        public void onPlay() {
        }

        /**
         * Override to handle requests to begin playback from a search query. An
         * empty query indicates that the app may play any music. The
         * implementation should attempt to make a smart choice about what to
         * play.
         */
        public void onPlayFromSearch(String query, Bundle extras) {
        }

        /**
         * Override to handle requests to play a specific mediaId that was
         * provided by your app's {@link MediaBrowserService}.
         */
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
        }

        /**
         * Override to handle requests to play a specific media item represented by a URI.
         */
        public void onPlayFromUri(Uri uri, Bundle extras) {
        }

        /**
         * Override to handle requests to play an item with a given id from the
         * play queue.
         */
        public void onSkipToQueueItem(long id) {
        }

        /**
         * Override to handle requests to pause playback.
         */
        public void onPause() {
        }

        /**
         * Override to handle requests to skip to the next media item.
         */
        public void onSkipToNext() {
        }

        /**
         * Override to handle requests to skip to the previous media item.
         */
        public void onSkipToPrevious() {
        }

        /**
         * Override to handle requests to fast forward.
         */
        public void onFastForward() {
        }

        /**
         * Override to handle requests to rewind.
         */
        public void onRewind() {
        }

        /**
         * Override to handle requests to stop playback.
         */
        public void onStop() {
        }

        /**
         * Override to handle requests to seek to a specific position in ms.
         *
         * @param pos New position to move to, in milliseconds.
         */
        public void onSeekTo(long pos) {
        }

        /**
         * Override to handle the item being rated.
         *
         * @param rating
         */
        public void onSetRating(@NonNull Rating rating) {
        }

        /**
         * Override to handle the playback speed change.
         * To update the new playback speed, create a new {@link PlaybackState} by using {@link
         * PlaybackState.Builder#setState(int, long, float)}, and set it with
         * {@link #setPlaybackState(PlaybackState)}.
         *
         * @param speed the playback speed
         * @see #setPlaybackState(PlaybackState)
         * @see PlaybackState.Builder#setState(int, long, float)
         */
        public void onSetPlaybackSpeed(float speed) {
        }

        /**
         * Called when a {@link MediaController} wants a {@link PlaybackState.CustomAction} to be
         * performed.
         *
         * @param action The action that was originally sent in the
         *               {@link PlaybackState.CustomAction}.
         * @param extras Optional extras specified by the {@link MediaController}.
         */
        public void onCustomAction(@NonNull String action, @Nullable Bundle extras) {
        }

        /**
         * @hide
         */
        public void onSetMediaButtonEventDelegate(
                @NonNull MediaSessionEngine.MediaButtonEventDelegate delegate) {
            mMediaButtonEventDelegate = delegate;
        }
    }

    /**
     * A single item that is part of the play queue. It contains a description
     * of the item and its id in the queue.
     */
    public static final class QueueItem implements Parcelable {
        /**
         * This id is reserved. No items can be explicitly assigned this id.
         */
        public static final int UNKNOWN_ID = -1;

        private final MediaSessionEngine.QueueItem mImpl;
        @UnsupportedAppUsage
        private final long mId;

        /**
         * Create a new {@link MediaSession.QueueItem}.
         *
         * @param description The {@link MediaDescription} for this item.
         * @param id An identifier for this item. It must be unique within the
         *            play queue and cannot be {@link #UNKNOWN_ID}.
         */
        public QueueItem(MediaDescription description, long id) {
            mImpl = new MediaSessionEngine.QueueItem(description, id);
            mId = id;
        }

        private QueueItem(Parcel in) {
            mImpl = new MediaSessionEngine.QueueItem(in);
            mId = mImpl.getQueueId();
        }

        /**
         * Get the description for this item.
         */
        public MediaDescription getDescription() {
            return mImpl.getDescription();
        }

        /**
         * Get the queue id for this item.
         */
        public long getQueueId() {
            return mImpl.getQueueId();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            mImpl.writeToParcel(dest, flags);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final @android.annotation.NonNull Creator<MediaSession.QueueItem> CREATOR =
                new Creator<MediaSession.QueueItem>() {

            @Override
            public MediaSession.QueueItem createFromParcel(Parcel p) {
                return new MediaSession.QueueItem(p);
            }

            @Override
            public MediaSession.QueueItem[] newArray(int size) {
                return new MediaSession.QueueItem[size];
            }
        };

        @Override
        public String toString() {
            return mImpl.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (o == null) {
                return false;
            }

            if (!(o instanceof QueueItem)) {
                return false;
            }

            return mImpl.equals(((QueueItem) o).mImpl);
        }
    }
}
