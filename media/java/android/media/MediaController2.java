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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.MediaPlayerBase.PlaybackListener;
import android.media.MediaSession2.CommandButton;
import android.media.MediaSession2.CommandGroup;
import android.media.MediaSession2.ControllerInfo;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.update.ApiLoader;
import android.media.update.MediaController2Provider;
import android.os.Handler;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Allows an app to interact with an active {@link MediaSession2} or a
 * {@link MediaSessionService2} in any status. Media buttons and other commands can be sent to
 * the session.
 * <p>
 * When you're done, use {@link #release()} to clean up resources. This also helps session service
 * to be destroyed when there's no controller associated with it.
 * <p>
 * When controlling {@link MediaSession2}, the controller will be available immediately after
 * the creation.
 * <p>
 * When controlling {@link MediaSessionService2}, the {@link MediaController2} would be
 * available only if the session service allows this controller by
 * {@link MediaSession2.SessionCallback#onConnect(ControllerInfo)} for the service. Wait
 * {@link ControllerCallback#onConnected(CommandGroup)} or
 * {@link ControllerCallback#onDisconnected()} for the result.
 * <p>
 * A controller can be created through token from {@link MediaSessionManager} if you hold the
 * signature|privileged permission "android.permission.MEDIA_CONTENT_CONTROL" permission or are
 * an enabled notification listener or by getting a {@link SessionToken} directly the
 * the session owner.
 * <p>
 * MediaController2 objects are thread-safe.
 * <p>
 * @see MediaSession2
 * @see MediaSessionService2
 * @hide
 */
// TODO(jaewan): Unhide
// TODO(jaewan): Revisit comments. Currently MediaBrowser case is missing.
public class MediaController2 implements AutoCloseable {
    /**
     * Interface for listening to change in activeness of the {@link MediaSession2}.  It's
     * active if and only if it has set a player.
     */
    public abstract static class ControllerCallback {
        /**
         * Called when the controller is successfully connected to the session. The controller
         * becomes available afterwards.
         *
         * @param allowedCommands commands that's allowed by the session.
         */
        public void onConnected(CommandGroup allowedCommands) { }

        /**
         * Called when the session refuses the controller or the controller is disconnected from
         * the session. The controller becomes unavailable afterwards and the callback wouldn't
         * be called.
         * <p>
         * It will be also called after the {@link #close()}, so you can put clean up code here.
         * You don't need to call {@link #close()} after this.
         */
        public void onDisconnected() { }

        /**
         * Called when the session sets the custom layout through the
         * {@link MediaSession2#setCustomLayout(ControllerInfo, List)}.
         * <p>
         * Can be called before {@link #onConnected(CommandGroup)} is called.
         *
         * @param layout
         */
        public void onCustomLayoutChanged(List<CommandButton> layout) { }
    }

    private final MediaController2Provider mProvider;

    /**
     * Create a {@link MediaController2} from the {@link SessionToken}. This connects to the session
     * and may wake up the service if it's not available.
     *
     * @param context Context
     * @param token token to connect to
     * @param callback controller callback to receive changes in
     * @param executor executor to run callbacks on.
     */
    // TODO(jaewan): Put @CallbackExecutor to the constructor.
    public MediaController2(@NonNull Context context, @NonNull SessionToken token,
            @NonNull ControllerCallback callback, @NonNull Executor executor) {
        super();

        // This also connects to the token.
        // Explicit connect() isn't added on purpose because retrying connect() is impossible with
        // session whose session binder is only valid while it's active.
        // prevent a controller from reusable after the
        // session is released and recreated.
        mProvider = createProvider(context, token, callback, executor);
    }

    MediaController2Provider createProvider(@NonNull Context context,
            @NonNull SessionToken token, @NonNull ControllerCallback callback,
            @NonNull Executor executor) {
        return ApiLoader.getProvider(context)
                .createMediaController2(this, context, token, callback, executor);
    }

    /**
     * Release this object, and disconnect from the session. After this, callbacks wouldn't be
     * received.
     */
    @Override
    public void close() {
        mProvider.close_impl();
    }

    /**
     * @hide
     */
    public MediaController2Provider getProvider() {
        return mProvider;
    }

    /**
     * @return token
     */
    public @NonNull
    SessionToken getSessionToken() {
        return mProvider.getSessionToken_impl();
    }

    /**
     * Returns whether this class is connected to active {@link MediaSession2} or not.
     */
    public boolean isConnected() {
        return mProvider.isConnected_impl();
    }

    public void play() {
        mProvider.play_impl();
    }

    public void pause() {
        mProvider.pause_impl();
    }

    public void stop() {
        mProvider.stop_impl();
    }

    public void skipToPrevious() {
        mProvider.skipToPrevious_impl();
    }

    public void skipToNext() {
        mProvider.skipToNext_impl();
    }


    public @Nullable PlaybackState getPlaybackState() {
        return mProvider.getPlaybackState_impl();
    }

    /**
     * Add a {@link PlaybackListener} to listen changes in the
     * {@link MediaSession2}.
     *
     * @param listener the listener that will be run
     * @param handler the Handler that will receive the listener
     * @throws IllegalArgumentException Called when either the listener or handler is {@code null}.
     */
    // TODO(jaewan): Match with the addSessionAvailabilityListener() that tells the current state
    //               through the listener.
    // TODO(jaewan): Can handler be null? Follow the API guideline after it's finalized.
    public void addPlaybackListener(@NonNull PlaybackListener listener, @NonNull Handler handler) {
        mProvider.addPlaybackListener_impl(listener, handler);
    }

    /**
     * Remove previously added {@link PlaybackListener}.
     *
     * @param listener the listener to be removed
     * @throws IllegalArgumentException if the listener is {@code null}.
     */
    public void removePlaybackListener(@NonNull PlaybackListener listener) {
        mProvider.removePlaybackListener_impl(listener);
    }
}
