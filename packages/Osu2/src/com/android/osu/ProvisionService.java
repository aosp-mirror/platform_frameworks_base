/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.osu;

import android.content.Context;
import android.net.Network;
import android.net.wifi.hotspot2.OsuProvider;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.IOException;

/**
 * Service responsible for performing Passpoint subscription provisioning tasks.
 * This service will run on a separate thread to avoid blocking on the Main thread.
 */
public class ProvisionService implements OsuService {
    private static final String TAG = "OSU_ProvisionService";
    private static final int COMMAND_START = 1;
    private static final int COMMAND_STOP = 2;

    private final Context mContext;
    private final HandlerThread mHandlerThread;
    private final ServiceHandler mServiceHandler;
    private final OsuProvider mProvider;

    private boolean mStarted = false;
    private NetworkConnection mNetworkConnection = null;

    public ProvisionService(Context context, OsuProvider provider) {
        mContext = context;
        mProvider = provider;
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mServiceHandler = new ServiceHandler(mHandlerThread.getLooper());
    }

    @Override
    public void start() {
        mServiceHandler.sendMessage(mServiceHandler.obtainMessage(COMMAND_START));
    }

    @Override
    public void stop() {
        mServiceHandler.sendMessage(mServiceHandler.obtainMessage(COMMAND_STOP));
    }

    /**
     * Handler class for handling commands to the ProvisionService.
     */
    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case COMMAND_START:
                    if (mStarted) {
                        Log.e(TAG, "Service already started");
                        return;
                    }
                    try {
                        // Initiate network connection to the OSU AP.
                        mNetworkConnection = new NetworkConnection(
                                mContext, this, mProvider.getOsuSsid(),
                                mProvider.getNetworkAccessIdentifier(), new NetworkCallbacks());
                        mStarted = true;
                    } catch (IOException e) {
                        // TODO(zqiu): broadcast failure event via LocalBroadcastManager.
                    }
                    break;
                case COMMAND_STOP:
                    if (!mStarted) {
                        Log.e(TAG, "Service not started");
                        return;
                    }
                    Log.e(TAG, "Stop provision service");
                    break;
                default:
                    Log.e(TAG, "Unknown command: " + msg.what);
                    break;
            }
        }
    }

    private final class NetworkCallbacks implements NetworkConnection.Callbacks {
        @Override
        public void onConnected(Network network) {
            Log.d(TAG, "Connected to OSU AP");
        }

        @Override
        public void onDisconnected() {
        }

        @Override
        public void onTimeout() {
        }
    }
}
