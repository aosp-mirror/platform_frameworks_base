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

import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.session.MediaSession;
import android.media.session.MediaSession.Callback;
import android.media.session.PlaybackState;
import android.media.update.ApiLoader;
import android.media.update.MediaSession2Provider;
import android.media.update.MediaSession2Provider.ControllerInfoProvider;
import android.os.Handler;
import android.os.Process;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/**
 * Allows a media app to expose its transport controls and playback information in a process to
 * other processes including the Android framework and other apps. Common use cases are as follows.
 * <ul>
 *     <li>Bluetooth/wired headset key events support</li>
 *     <li>Android Auto/Wearable support</li>
 *     <li>Separating UI process and playback process</li>
 * </ul>
 * <p>
 * A MediaSession2 should be created when an app wants to publish media playback information or
 * handle media keys. In general an app only needs one session for all playback, though multiple
 * sessions can be created to provide finer grain controls of media.
 * <p>
 * If you want to support background playback, {@link MediaSessionService2} is preferred
 * instead. With it, your playback can be revived even after you've finished playback. See
 * {@link MediaSessionService2} for details.
 * <p>
 * A session can be obtained by {@link #getInstance(Context, Handler)}. The owner of the session may
 * pass its session token to other processes to allow them to create a {@link MediaController2}
 * to interact with the session.
 * <p>
 * To receive transport control commands, an underlying media player must be set with
 * {@link #setPlayer(MediaPlayerBase)}. Commands will be sent to the underlying player directly
 * on the thread that had been specified by {@link #getInstance(Context, Handler)}.
 * <p>
 * When an app is finished performing playback it must call
 * {@link #setPlayer(MediaPlayerBase)} with {@code null} to clean up the session and notify any
 * controllers. It's developers responsibility of cleaning the session and releasing resources.
 * <p>
 * MediaSession2 objects should be used on the handler's thread that is initially given by
 * {@link #getInstance(Context, Handler)}.
 *
 * @see MediaSessionService2
 * @hide
 */
// TODO(jaewan): Unhide
// TODO(jaewan): Revisit comments. Currently it's borrowed from the MediaSession.
// TODO(jaewan): Add explicit release(), and make token @NonNull. Session will be active while the
//               session exists, and controllers will be invalidated when session becomes inactive.
// TODO(jaewan): Should we support thread safe? It may cause tricky issue such as b/63797089
// TODO(jaewan): Should we make APIs for MediaSessionService2 public? It's helpful for
//               developers that doesn't want to override from Browser, but user may not use this
//               correctly.
public final class MediaSession2 extends MediaPlayerBase {
    private final MediaSession2Provider mProvider;

    // These are intentionally public to allow apps to hook for every incoming command.
    // Type is long (64 bits) to have enough buffer to keep all commands from MediaControllers (29)
    // and future extensions.
    // Sync with the MediaSession2Impl.java
    // TODO(jaewan): Add a way to log every incoming calls outside of the app with the calling
    //               package.
    //               Keep these sync with IMediaSession2RecordCallback.
    // TODO(jaewan): Should we move this to updatable as well?
    public static final long COMMAND_FLAG_PLAYBACK_START = 1 << 0;
    public static final long COMMAND_FLAG_PLAYBACK_PAUSE = 1 << 1;
    public static final long COMMAND_FLAG_PLAYBACK_STOP = 1 << 2;
    public static final long COMMAND_FLAG_PLAYBACK_SKIP_NEXT_ITEM = 1 << 3;
    public static final long COMMAND_FLAG_PLAYBACK_SKIP_PREV_ITEM = 1 << 4;

    /**
     * Command flag for adding/removing playback listener to get playback state.
     */
    public static final long COMMAND_FLAG_GET_PLAYBACK_STATE = 1 << 5;

    @Retention(RetentionPolicy.SOURCE)
    @LongDef(flag = true, value = {COMMAND_FLAG_PLAYBACK_START, COMMAND_FLAG_PLAYBACK_PAUSE,
            COMMAND_FLAG_PLAYBACK_STOP, COMMAND_FLAG_PLAYBACK_SKIP_NEXT_ITEM,
            COMMAND_FLAG_PLAYBACK_SKIP_PREV_ITEM, COMMAND_FLAG_GET_PLAYBACK_STATE})
    public @interface CommandFlags {
    }

    /**
     * Callback to be called for all incoming commands from {@link MediaController2}s.
     * <p>
     * If it's not set, the session will accept all controllers and all incoming commands by
     * default.
     */
    // TODO(jaewan): Add UID with multi-user support.
    // TODO(jaewan): Can we move this inside of the updatable for default implementation.
    // TODO(jaewan): Add onConnected() to return permitted action.
    // TODO(jaewan): Cache the result? Will it be persistent?
    public static class SessionCallback {
        /**
         * Called when a controller is created for this session. Return allowed commands for
         * controller. By default it allows system apps and self.
         * <p>
         * You can reject the connection at all by return {@code 0}.
         *
         * @param controller controller information.
         * @return
         */
        // TODO(jaewan): Change return type. Once we do, null is for reject.
        public @CommandFlags long onConnect(ControllerInfo controller) {
            // TODO(jaewan): Move this to updatable.
            if (controller.isTrusted() || controller.getUid() == Process.myUid()) {
                // TODO(jaewan): Change default.
                return (1 << 6) - 1;
            }
            // Reject others
            return 0;
        }

        /**
         * Called when a controller sent a command to the session. You can also reject the request
         * by return {@code false} for apps without system permission. You cannot reject commands
         * from apps with system permission.
         * <p>
         * This method will be called on the session handler.
         *
         * @param controller controller information.
         * @param command one of the {@link CommandFlags}. This method will be called for every
         *      single command.
         * @return {@code true} if you want to accept incoming command. {@code false} otherwise.
         *      It will be ignored for apps with the system permission.
         * @see {@link CommandFlags}
         */
        // TODO(jaewan): Get confirmation from devrel/auto that it's OK to return void here.
        public boolean onCommand(ControllerInfo controller, @CommandFlags long command) {
            return true;
        }
    };

    /**
     * Builder for {@link MediaSession2}.
     * <p>
     * Any incoming event from the {@link MediaController2} will be handled on the thread
     * that created session with the {@link Builder#build()}.
     */
    // TODO(jaewan): Move this to updatable
    // TODO(jaewan): Add setRatingType()
    // TODO(jaewan): Add setSessionActivity()
    public final static class Builder {
        private final Context mContext;
        private final MediaPlayerBase mPlayer;
        private String mId;
        private SessionCallback mCallback;

        /**
         * Constructor.
         *
         * @param context a context
         * @param player a player to handle incoming command from any controller.
         * @throws IllegalArgumentException if any parameter is null, or the player is a
         *      {@link MediaSession2} or {@link MediaController2}.
         */
        public Builder(@NonNull Context context, @NonNull MediaPlayerBase player) {
            if (context == null) {
                throw new IllegalArgumentException("context shouldn't be null");
            }
            if (player == null) {
                throw new IllegalArgumentException("player shouldn't be null");
            }
            if (player instanceof MediaSession2 || player instanceof MediaController2) {
                throw new IllegalArgumentException("player doesn't accept MediaSession2 nor"
                        + " MediaController2");
            }
            mContext = context;
            mPlayer = player;
            // Ensure non-null
            mId = "";
        }

        /**
         * Set ID of the session. If it's not set, an empty string with used to create a session.
         * <p>
         * Use this if and only if your app supports multiple playback at the same time and also
         * wants to provide external apps to have finer controls of them.
         *
         * @param id id of the session. Must be unique per package.
         * @throws IllegalArgumentException if id is {@code null}
         * @return
         */
        public Builder setId(@NonNull String id) {
            if (id == null) {
                throw new IllegalArgumentException("id shouldn't be null");
            }
            mId = id;
            return this;
        }

        /**
         * Set {@link SessionCallback}.
         *
         * @param callback session callback.
         * @return
         */
        public Builder setSessionCallback(@Nullable SessionCallback callback) {
            mCallback = callback;
            return this;
        }

        /**
         * Build {@link MediaSession2}.
         *
         * @return a new session
         * @throws IllegalStateException if the session with the same id is already exists for the
         *      package.
         */
        public MediaSession2 build() throws IllegalStateException {
            if (mCallback == null) {
                mCallback = new SessionCallback();
            }
            return new MediaSession2(mContext, mPlayer, mId, mCallback);
        }
    }

    /**
     * Information of a controller.
     */
    // TODO(jaewan): Move implementation to the updatable.
    public static final class ControllerInfo {
        private final ControllerInfoProvider mProvider;

        /**
         * @hide
         */
        // TODO(jaewan): SystemApi
        // TODO(jaewan): Also accept componentName to check notificaiton listener.
        public ControllerInfo(Context context, int uid, int pid, String packageName,
                IMediaSession2Callback callback) {
            mProvider = ApiLoader.getProvider(context)
                    .createMediaSession2ControllerInfoProvider(
                            this, context, uid, pid, packageName, callback);
        }

        /**
         * @return package name of the controller
         */
        public String getPackageName() {
            return mProvider.getPackageName_impl();
        }

        /**
         * @return uid of the controller
         */
        public int getUid() {
            return mProvider.getUid_impl();
        }

        /**
         * Return if the controller has granted {@code android.permission.MEDIA_CONTENT_CONTROL} or
         * has a enabled notification listener so can be trusted to accept connection and incoming
         * command request.
         *
         * @return {@code true} if the controller is trusted.
         */
        public boolean isTrusted() {
            return mProvider.isTrusted_impl();
        }

        /**
         * @hide
         * @return
         */
        // TODO(jaewan): SystemApi
        public ControllerInfoProvider getProvider() {
            return mProvider;
        }

        @Override
        public int hashCode() {
            return mProvider.hashCode_impl();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ControllerInfo)) {
                return false;
            }
            ControllerInfo other = (ControllerInfo) obj;
            return mProvider.equals_impl(other.mProvider);
        }

        @Override
        public String toString() {
            return "ControllerInfo {pkg=" + getPackageName() + ", uid=" + getUid() + ", trusted="
                    + isTrusted() + "}";
        }
    }

    /**
     * Constructor is hidden and apps can only instantiate indirectly through {@link Builder}.
     * <p>
     * This intended behavior and here's the reasons.
     *    1. Prevent multiple sessions with the same tag in a media app.
     *       Whenever it happens only one session was properly setup and others were all dummies.
     *       Android framework couldn't find the right session to dispatch media key event.
     *    2. Simplify session's lifecycle.
     *       {@link MediaSession} can be available after all of {@link MediaSession#setFlags(int)},
     *       {@link MediaSession#setCallback(Callback)}, and
     *       {@link MediaSession#setActive(boolean)}. It was common for an app to omit one, so
     *       framework had to add heuristics to figure out if an app is
     * @hide
     */
    private MediaSession2(Context context, MediaPlayerBase player, String id,
            SessionCallback callback) {
        super();
        mProvider = ApiLoader.getProvider(context)
                .createMediaSession2(this, context, player, id, callback);
    }

    /**
     * @hide
     */
    // TODO(jaewan): SystemApi
    public MediaSession2Provider getProvider() {
        return mProvider;
    }

    /**
     * Set the underlying {@link MediaPlayerBase} for this session to dispatch incoming event to.
     * Events from the {@link MediaController2} will be sent directly to the underlying
     * player on the {@link Handler} where the session is created on.
     * <p>
     * If the new player is successfully set, {@link PlaybackListener}
     * will be called to tell the current playback state of the new player.
     * <p>
     * Calling this method with {@code null} will disconnect binding connection between the
     * controllers and also release this object.
     *
     * @param player a {@link MediaPlayerBase} that handles actual media playback in your app.
     *      It shouldn't be {@link MediaSession2} nor {@link MediaController2}.
     * @throws IllegalArgumentException if the player is either {@link MediaSession2}
     *      or {@link MediaController2}.
     */
    // TODO(jaewan): Add release instead of setPlayer(null).
    public void setPlayer(MediaPlayerBase player) throws IllegalArgumentException {
        mProvider.setPlayer_impl(player);
    }

    /**
     * @return player
     */
    public @Nullable MediaPlayerBase getPlayer() {
        return mProvider.getPlayer_impl();
    }

    /**
     * Returns the {@link SessionToken} for creating {@link MediaController2}.
     */
    public @NonNull
    SessionToken getToken() {
        return mProvider.getToken_impl();
    }

    public @NonNull List<ControllerInfo> getConnectedControllers() {
        return mProvider.getConnectedControllers_impl();
    }

    @Override
    public void play() {
        mProvider.play_impl();
    }

    @Override
    public void pause() {
        mProvider.pause_impl();
    }

    @Override
    public void stop() {
        mProvider.stop_impl();
    }

    @Override
    public void skipToPrevious() {
        mProvider.skipToPrevious_impl();
    }

    @Override
    public void skipToNext() {
        mProvider.skipToNext_impl();
    }

    @Override
    public @NonNull PlaybackState getPlaybackState() {
        return mProvider.getPlaybackState_impl();
    }

    /**
     * Add a {@link PlaybackListener} to listen changes in the
     * underlying {@link MediaPlayerBase} which is previously set by
     * {@link #setPlayer(MediaPlayerBase)}.
     * <p>
     * Added listeners will be also called when the underlying player is changed.
     *
     * @param listener the listener that will be run
     * @param handler the Handler that will receive the listener
     * @throws IllegalArgumentException when either the listener or handler is {@code null}.
     */
    // TODO(jaewan): Can handler be null? Follow API guideline after it's finalized.
    @Override
    public void addPlaybackListener(@NonNull PlaybackListener listener, @NonNull Handler handler) {
        mProvider.addPlaybackListener_impl(listener, handler);
    }

    /**
     * Remove previously added {@link PlaybackListener}.
     *
     * @param listener the listener to be removed
     * @throws IllegalArgumentException if the listener is {@code null}.
     */
    @Override
    public void removePlaybackListener(PlaybackListener listener) {
        mProvider.removePlaybackListener_impl(listener);
    }
}
