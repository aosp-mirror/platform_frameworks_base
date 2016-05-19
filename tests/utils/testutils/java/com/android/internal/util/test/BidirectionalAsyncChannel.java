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
 * limitations under the License
 */

package com.android.internal.util.test;

import static org.junit.Assert.assertEquals;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.util.Log;

import com.android.internal.util.AsyncChannel;


/**
 * Provides an AsyncChannel interface that implements the connection initiating half of a
 * bidirectional channel as described in {@link com.android.internal.util.AsyncChannel}.
 */
public class BidirectionalAsyncChannel {
    private static final String TAG = "BidirectionalAsyncChannel";

    private AsyncChannel mChannel;
    public enum ChannelState { DISCONNECTED, HALF_CONNECTED, CONNECTED, FAILURE };
    private ChannelState mState = ChannelState.DISCONNECTED;

    public void assertConnected() {
        assertEquals("AsyncChannel was not fully connected", ChannelState.CONNECTED, mState);
    }

    public void connect(final Looper looper, final Messenger messenger,
            final Handler incomingMessageHandler) {
        assertEquals("AsyncChannel must be disconnected to connect",
                ChannelState.DISCONNECTED, mState);
        mChannel = new AsyncChannel();
        Handler rawMessageHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                    case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                        if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL) {
                            Log.d(TAG, "Successfully half connected " + this);
                            mChannel.sendMessage(AsyncChannel.CMD_CHANNEL_FULL_CONNECTION);
                            mState = ChannelState.HALF_CONNECTED;
                        } else {
                            Log.d(TAG, "Failed to connect channel " + this);
                            mState = ChannelState.FAILURE;
                            mChannel = null;
                        }
                        break;
                    case AsyncChannel.CMD_CHANNEL_FULLY_CONNECTED:
                        mState = ChannelState.CONNECTED;
                        Log.d(TAG, "Channel fully connected" + this);
                        break;
                    case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                        mState = ChannelState.DISCONNECTED;
                        mChannel = null;
                        Log.d(TAG, "Channel disconnected" + this);
                        break;
                    default:
                        incomingMessageHandler.handleMessage(msg);
                        break;
                    }
                }
            };
        mChannel.connect(null, rawMessageHandler, messenger);
    }

    public void disconnect() {
        assertEquals("AsyncChannel must be connected to disconnect",
                ChannelState.CONNECTED, mState);
        mChannel.sendMessage(AsyncChannel.CMD_CHANNEL_DISCONNECT);
        mState = ChannelState.DISCONNECTED;
        mChannel = null;
    }

    public void sendMessage(Message msg) {
        assertEquals("AsyncChannel must be connected to send messages",
                ChannelState.CONNECTED, mState);
        mChannel.sendMessage(msg);
    }
}
