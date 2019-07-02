/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.util.test;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import com.android.internal.util.AsyncChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Provides an interface for the server side implementation of a bidirectional channel as described
 * in {@link com.android.internal.util.AsyncChannel}.
 */
public class BidirectionalAsyncChannelServer {

    private static final String TAG = "BidirectionalAsyncChannelServer";

    // Keeps track of incoming clients, which are identifiable by their messengers.
    private final Map<Messenger, AsyncChannel> mClients = new HashMap<>();

    private Messenger mMessenger;

    public BidirectionalAsyncChannelServer(final Context context, final Looper looper,
            final Handler messageHandler) {
        Handler handler = new Handler(looper) {
            @Override
            public void handleMessage(Message msg) {
                AsyncChannel channel = mClients.get(msg.replyTo);
                switch (msg.what) {
                    case AsyncChannel.CMD_CHANNEL_FULL_CONNECTION:
                        if (channel != null) {
                            Log.d(TAG, "duplicate client connection: " + msg.sendingUid);
                            channel.replyToMessage(msg,
                                    AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                    AsyncChannel.STATUS_FULL_CONNECTION_REFUSED_ALREADY_CONNECTED);
                        } else {
                            channel = new AsyncChannel();
                            mClients.put(msg.replyTo, channel);
                            channel.connected(context, this, msg.replyTo);
                            channel.replyToMessage(msg, AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED,
                                    AsyncChannel.STATUS_SUCCESSFUL);
                        }
                        break;
                    case AsyncChannel.CMD_CHANNEL_DISCONNECT:
                        channel.disconnect();
                        break;

                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        mClients.remove(msg.replyTo);
                        break;

                    default:
                        messageHandler.handleMessage(msg);
                        break;
                }
            }
        };
        mMessenger = new Messenger(handler);
    }

    public Messenger getMessenger() {
        return mMessenger;
    }

    public Set<Messenger> getClientMessengers() {
        return mClients.keySet();
    }

}
