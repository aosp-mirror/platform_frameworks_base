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
import android.content.Context;
import android.media.session.MediaSession;
import android.media.session.MediaSession.Callback;
import android.media.session.PlaybackState;
import android.media.update.ApiLoader;
import android.media.update.MediaSession2Provider;
import android.media.update.MediaSession2Provider.ControllerInfoProvider;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.os.Process;
import android.text.TextUtils;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
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

    // Note: Do not define IntDef because subclass can add more command code on top of these.
    public static final int COMMAND_CODE_CUSTOM = 0;
    public static final int COMMAND_CODE_PLAYBACK_START = 1;
    public static final int COMMAND_CODE_PLAYBACK_PAUSE = 2;
    public static final int COMMAND_CODE_PLAYBACK_STOP = 3;
    public static final int COMMAND_CODE_PLAYBACK_SKIP_NEXT_ITEM = 4;
    public static final int COMMAND_CODE_PLAYBACK_SKIP_PREV_ITEM = 5;

    /**
     * Define a command that a {@link MediaController2} can send to a {@link MediaSession2}.
     * <p>
     * If {@link #getCommandCode()} isn't {@link #COMMAND_CODE_CUSTOM}), it's predefined command.
     * If {@link #getCommandCode()} is {@link #COMMAND_CODE_CUSTOM}), it's custom command and
     * {@link #getCustomCommand()} shouldn't be {@code null}.
     */
    // TODO(jaewan): Move this into the updatable.
    public static final class Command {
        private static final String KEY_COMMAND_CODE
                = "android.media.mediasession2.command.command_command";
        private static final String KEY_COMMAND_CUSTOM_COMMAND
                = "android.media.mediasession2.command.custom_command";
        private static final String KEY_COMMAND_EXTRA
                = "android.media.mediasession2.command.extra";

        private final int mCommandCode;
        // Nonnull if it's custom command
        private final String mCustomCommand;
        private final Bundle mExtra;

        public Command(int commandCode) {
            mCommandCode = commandCode;
            mCustomCommand = null;
            mExtra = null;
        }

        public Command(@NonNull String action, @Nullable Bundle extra) {
            if (action == null) {
                throw new IllegalArgumentException("action shouldn't be null");
            }
            mCommandCode = COMMAND_CODE_CUSTOM;
            mCustomCommand = action;
            mExtra = extra;
        }

        public int getCommandCode() {
            return mCommandCode;
        }

        public @Nullable String getCustomCommand() {
            return mCustomCommand;
        }

        public @Nullable Bundle getExtra() {
            return mExtra;
        }

        /**
         * @return a new Bundle instance from the Command
         * @hide
         */
        public Bundle toBundle() {
            Bundle bundle = new Bundle();
            bundle.putInt(KEY_COMMAND_CODE, mCommandCode);
            bundle.putString(KEY_COMMAND_CUSTOM_COMMAND, mCustomCommand);
            bundle.putBundle(KEY_COMMAND_EXTRA, mExtra);
            return bundle;
        }

        /**
         * @return a new Command instance from the Bundle
         * @hide
         */
        public static Command fromBundle(Bundle command) {
            int code = command.getInt(KEY_COMMAND_CODE);
            if (code != COMMAND_CODE_CUSTOM) {
                return new Command(code);
            } else {
                String customCommand = command.getString(KEY_COMMAND_CUSTOM_COMMAND);
                if (customCommand == null) {
                    return null;
                }
                return new Command(customCommand, command.getBundle(KEY_COMMAND_EXTRA));
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Command)) {
                return false;
            }
            Command other = (Command) obj;
            // TODO(jaewan): Should we also compare contents in bundle?
            //               It may not be possible if the bundle contains private class.
            return mCommandCode == other.mCommandCode
                    && TextUtils.equals(mCustomCommand, other.mCustomCommand);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            return ((mCustomCommand != null) ? mCustomCommand.hashCode() : 0) * prime + mCommandCode;
        }
    }

    /**
     * Represent set of {@link Command}.
     */
    // TODO(jaewan): Move this to updatable
    public static class CommandGroup {
        private static final String KEY_COMMANDS =
                "android.media.mediasession2.commandgroup.commands";
        private ArraySet<Command> mCommands = new ArraySet<>();

        public CommandGroup() {
        }

        public CommandGroup(CommandGroup others) {
            mCommands.addAll(others.mCommands);
        }

        public void addCommand(Command command) {
            mCommands.add(command);
        }

        public void addAllPredefinedCommands() {
            // TODO(jaewan): Is there any better way than this?
            mCommands.add(new Command(COMMAND_CODE_PLAYBACK_START));
            mCommands.add(new Command(COMMAND_CODE_PLAYBACK_PAUSE));
            mCommands.add(new Command(COMMAND_CODE_PLAYBACK_STOP));
            mCommands.add(new Command(COMMAND_CODE_PLAYBACK_SKIP_NEXT_ITEM));
            mCommands.add(new Command(COMMAND_CODE_PLAYBACK_SKIP_PREV_ITEM));
        }

        public void removeCommand(Command command) {
            mCommands.remove(command);
        }

        public boolean hasCommand(Command command) {
            return mCommands.contains(command);
        }

        public boolean hasCommand(int code) {
            if (code == COMMAND_CODE_CUSTOM) {
                throw new IllegalArgumentException("Use hasCommand(Command) for custom command");
            }
            for (int i = 0; i < mCommands.size(); i++) {
                if (mCommands.valueAt(i).getCommandCode() == code) {
                    return true;
                }
            }
            return false;
        }

        /**
         * @return new bundle from the CommandGroup
         * @hide
         */
        public Bundle toBundle() {
            ArrayList<Bundle> list = new ArrayList<>();
            for (int i = 0; i < mCommands.size(); i++) {
                list.add(mCommands.valueAt(i).toBundle());
            }
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(KEY_COMMANDS, list);
            return bundle;
        }

        /**
         * @return new instance of CommandGroup from the bundle
         * @hide
         */
        public static @Nullable CommandGroup fromBundle(Bundle commands) {
            if (commands == null) {
                return null;
            }
            List<Parcelable> list = commands.getParcelableArrayList(KEY_COMMANDS);
            if (list == null) {
                return null;
            }
            CommandGroup commandGroup = new CommandGroup();
            for (int i = 0; i < list.size(); i++) {
                Parcelable parcelable = list.get(i);
                if (!(parcelable instanceof Bundle)) {
                    continue;
                }
                Bundle commandBundle = (Bundle) parcelable;
                Command command = Command.fromBundle(commandBundle);
                if (command != null) {
                    commandGroup.addCommand(command);
                }
            }
            return commandGroup;
        }
    }

    /**
     * Callback to be called for all incoming commands from {@link MediaController2}s.
     * <p>
     * If it's not set, the session will accept all controllers and all incoming commands by
     * default.
     */
    // TODO(jaewan): Can we move this inside of the updatable for default implementation.
    public static class SessionCallback {
        /**
         * Called when a controller is created for this session. Return allowed commands for
         * controller. By default it allows all connection requests and commands.
         * <p>
         * You can reject the connection by return {@code null}. In that case, controller receives
         * {@link MediaController2.ControllerCallback#onDisconnected()} and cannot be usable.
         *
         * @param controller controller information.
         * @return allowed commands. Can be {@code null} to reject coonnection.
         */
        // TODO(jaewan): Change return type. Once we do, null is for reject.
        public @Nullable CommandGroup onConnect(@NonNull ControllerInfo controller) {
            CommandGroup commands = new CommandGroup();
            commands.addAllPredefinedCommands();
            return commands;
        }

        /**
         * Called when a controller sent a command to the session. You can also reject the request
         * by return {@code false}.
         * <p>
         * This method will be called on the session handler.
         *
         * @param controller controller information.
         * @param command a command. This method will be called for every single command.
         * @return {@code true} if you want to accept incoming command. {@code false} otherwise.
         */
        public boolean onCommandRequest(@NonNull ControllerInfo controller,
                @NonNull Command command) {
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
