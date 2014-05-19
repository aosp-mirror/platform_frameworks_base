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
package android.media.routeprovider;

import android.media.session.Route;
import android.media.session.MediaSession;
import android.media.session.RouteInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;

/**
 * Represents an interface that an application may use to send requests to a
 * connected media route.
 * <p>
 * A {@link RouteProviderService} may expose multiple interfaces on a
 * {@link RouteConnection} for a {@link MediaSession} to interact with. A
 * provider creates an interface with
 * {@link RouteConnection#addRouteInterface(String)} to allow messages to be
 * routed appropriately. Events are then sent through a specific interface and
 * all commands being sent on the interface will be sent to any registered
 * {@link CommandListener}s.
 * <p>
 * An interface instance can only be registered on one {@link RouteConnection}.
 * To use the same interface on multiple connections a new instance must be
 * created for each connection.
 * <p>
 * It is recommended you wrap this interface with a standard implementation to
 * avoid errors, but for simple interfaces this class may be used directly. TODO
 * add link to sample code.
 * @hide
 */
public final class RouteInterfaceHandler {
    private static final String TAG = "RouteInterfaceHandler";

    private final Object mLock = new Object();
    private final RouteConnection mConnection;
    private final String mName;

    private ArrayList<MessageHandler> mListeners = new ArrayList<MessageHandler>();

    /**
     * Create a new RouteInterface for a given connection. This can be used to
     * send events on the given interface and register listeners for commands
     * from the connected session.
     *
     * @param connection The connection this interface sends events on
     * @param ifaceName The name of this interface
     * @hide
     */
    public RouteInterfaceHandler(RouteConnection connection, String ifaceName) {
        if (connection == null) {
            throw new IllegalArgumentException("connection may not be null");
        }
        if (TextUtils.isEmpty(ifaceName)) {
            throw new IllegalArgumentException("ifaceName can not be empty");
        }
        mConnection = connection;
        mName = ifaceName;
    }

    /**
     * Send an event on this interface to the connected session.
     *
     * @param event The event to send
     * @param extras Any extras for the event
     */
    public void sendEvent(String event, Bundle extras) {
        mConnection.sendEvent(mName, event, extras);
    }

    /**
     * Send a result from a command to the specified callback. The result codes
     * in {@link RouteInterface} must be used. More information
     * about the result, whether successful or an error, should be included in
     * the extras.
     *
     * @param cb The callback to send the result to
     * @param resultCode The result code for the call
     * @param extras Any extras to include
     */
    public static void sendResult(ResultReceiver cb, int resultCode, Bundle extras) {
        if (cb != null) {
            cb.send(resultCode, extras);
        }
    }

    /**
     * Add a listener for this interface. If a handler is specified callbacks
     * will be performed on the handler's thread, otherwise the callers thread
     * will be used.
     *
     * @param listener The listener to receive calls on.
     * @param handler The handler whose thread to post calls on or null.
     */
    public void addListener(CommandListener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener may not be null");
        }
        Looper looper = handler != null ? handler.getLooper() : Looper.myLooper();
        synchronized (mLock) {
            if (findIndexOfListenerLocked(listener) != -1) {
                Log.d(TAG, "Listener is already added, ignoring");
                return;
            }
            mListeners.add(new MessageHandler(looper, listener));
        }
    }

    /**
     * Remove a listener from this interface.
     *
     * @param listener The listener to stop receiving commands on.
     */
    public void removeListener(CommandListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener may not be null");
        }
        synchronized (mLock) {
            int index = findIndexOfListenerLocked(listener);
            if (index != -1) {
                mListeners.remove(index);
            }
        }
    }

    /**
     * @hide
     */
    public void onCommand(String command, Bundle args, ResultReceiver cb) {
        synchronized (mLock) {
            Command cmd = new Command(command, args, cb);
            for (int i = mListeners.size() - 1; i >= 0; i--) {
                mListeners.get(i).post(MessageHandler.MSG_COMMAND, cmd);
            }
        }
    }

    /**
     * Get the interface name.
     *
     * @return The name of this interface
     */
    public String getName() {
        return mName;
    }

    private int findIndexOfListenerLocked(CommandListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        for (int i = mListeners.size() - 1; i >= 0; i--) {
            MessageHandler handler = mListeners.get(i);
            if (listener == handler.mListener) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Handles commands sent to the interface.
     * <p>
     * Register an InterfaceListener using {@link #addListener}.
     */
    public abstract static class CommandListener {
        /**
         * This is called when a command is received that matches this
         * interface. Commands are sent by a {@link MediaSession} that is
         * connected to the route this interface is registered with.
         *
         * @param iface The interface the command was received on.
         * @param command The command or method to invoke.
         * @param args Any args that were included with the command. May be
         *            null.
         * @param cb The callback provided to send a response on. May be null.
         * @return true if the command was handled, false otherwise. If the
         *         command was not handled an error will be sent automatically.
         *         true may be returned if the command will be handled
         *         asynchronously.
         * @see Route
         * @see MediaSession
         */
        public abstract boolean onCommand(RouteInterfaceHandler iface, String command, Bundle args,
                ResultReceiver cb);
    }

    private class MessageHandler extends Handler {
        private static final int MSG_COMMAND = 1;

        private final CommandListener mListener;

        public MessageHandler(Looper looper, CommandListener listener) {
            super(looper, null, true /* async */);
            mListener = listener;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_COMMAND:
                    Command cmd = (Command) msg.obj;
                    if (!mListener.onCommand(RouteInterfaceHandler.this, cmd.command, cmd.args, cmd.cb)) {
                        sendResult(cmd.cb, RouteInterface.RESULT_COMMAND_NOT_SUPPORTED,
                                null);
                    }
                    break;
            }
        }

        public void post(int what, Object obj) {
            obtainMessage(what, obj).sendToTarget();
        }
    }

    private final static class Command {
        public final String command;
        public final Bundle args;
        public final ResultReceiver cb;

        public Command(String command, Bundle args, ResultReceiver cb) {
            this.command = command;
            this.args = args;
            this.cb = cb;
        }
    }
}
