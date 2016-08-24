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

package android.printservice.recommendation;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.util.List;

/**
 * Base class for the print service recommendation services.
 *
 * @hide
 */
@SystemApi
public abstract class RecommendationService extends Service {
    private static final String LOG_TAG = "PrintServiceRecS";

    /** Used to push onConnect and onDisconnect on the main thread */
    private Handler mHandler;

    /**
     * The {@link Intent} action that must be declared as handled by a service in its manifest for
     * the system to recognize it as a print service recommendation service.
     */
    public static final String SERVICE_INTERFACE =
            "android.printservice.recommendation.RecommendationService";

    /** Registered callbacks, only modified on main thread */
    private IRecommendationServiceCallbacks mCallbacks;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        mHandler = new MyHandler();
    }

    /**
     * Update the print service recommendations.
     *
     * @param recommendations The new set of recommendations
     */
    public final void updateRecommendations(@Nullable List<RecommendationInfo> recommendations) {
        mHandler.obtainMessage(MyHandler.MSG_UPDATE, recommendations).sendToTarget();
    }

    @Override
    public final IBinder onBind(Intent intent) {
        return new IRecommendationService.Stub() {
            @Override
            public void registerCallbacks(IRecommendationServiceCallbacks callbacks) {
                // The callbacks come in order of the caller on oneway calls. Hence while the caller
                // cannot know at what time the connection is made, he can know the ordering of
                // connection and disconnection.
                //
                // Similar he cannot know when the disconnection is processed, hence he has to
                // handle callbacks after calling disconnect.
                if (callbacks != null) {
                    mHandler.obtainMessage(MyHandler.MSG_CONNECT, callbacks).sendToTarget();
                } else {
                    mHandler.obtainMessage(MyHandler.MSG_DISCONNECT).sendToTarget();
                }
            }
        };
    }

    /**
     * Called when the client connects to the recommendation service.
     */
    public abstract void onConnected();

    /**
     * Called when the client disconnects from the recommendation service.
     */
    public abstract void onDisconnected();

    private class MyHandler extends Handler {
        static final int MSG_CONNECT = 1;
        static final int MSG_DISCONNECT = 2;
        static final int MSG_UPDATE = 3;

        MyHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_CONNECT:
                    mCallbacks = (IRecommendationServiceCallbacks) msg.obj;
                    onConnected();
                    break;
                case MSG_DISCONNECT:
                    onDisconnected();
                    mCallbacks = null;
                    break;
                case MSG_UPDATE:
                    // Note that there might be a connection change in progress. In this case the
                    // message is handled as before the change. This is acceptable as the caller of
                    // the connection change has not guarantee when the connection change binder
                    // transaction is actually processed.
                    try {
                        mCallbacks.onRecommendationsUpdated((List<RecommendationInfo>) msg.obj);
                    } catch (RemoteException | NullPointerException e) {
                        Log.e(LOG_TAG, "Could not update recommended services", e);
                    }
                    break;
            }
        }
    }
}
