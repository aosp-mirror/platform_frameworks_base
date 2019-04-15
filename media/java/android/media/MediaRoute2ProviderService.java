/*
 * Copyright 2019 The Android Open Source Project
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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

/**
 * @hide
 */
public abstract class MediaRoute2ProviderService extends Service {
    private static final String TAG = "MediaRouteProviderSrv";

    public static final String SERVICE_INTERFACE = "android.media.MediaRoute2ProviderService";

    private final Handler mHandler;
    private ProviderStub mStub;
    //TODO: Should allow multiple clients
    private IMediaRoute2ProviderClient mClient;
    private MediaRoute2ProviderInfo mProviderInfo;

    public MediaRoute2ProviderService() {
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            if (mStub == null) {
                mStub = new ProviderStub();
            }
            return mStub;
        }
        return null;
    }

    /**
     * Called when selectRoute is called on a route of the provider.
     *
     * @param uid The target application uid
     * @param routeId The id of the target route
     */
    public abstract void onSelect(int uid, String routeId);

    /**
     * Updates provider info from selected route and appliation.
     *
     * TODO: When provider descriptor is defined, this should update the descriptor correctly.
     *
     * @param uid
     * @param routeId
     */
    public void updateProvider(int uid, String routeId) {
        if (mClient != null) {
            try {
                //TODO: After publishState() is fully implemented, delete this.
                mClient.notifyRouteSelected(uid, routeId);
            } catch (RemoteException ex) {
                Log.d(TAG, "Failed to update provider");
            }
        }
        publishState();
    }

    /**
     * Updates provider info and publish routes
     */
    public final void setProviderInfo(MediaRoute2ProviderInfo info) {
        mProviderInfo = info;
        publishState();
    }

    void registerClient(IMediaRoute2ProviderClient client) {
        mClient = client;
        publishState();
    }

    void publishState() {
        if (mClient == null) {
            return;
        }
        try {
            mClient.notifyProviderInfoUpdated(mProviderInfo);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to send onProviderInfoUpdated");
        }
    }

    final class ProviderStub extends IMediaRoute2Provider.Stub {
        ProviderStub() { }

        @Override
        public void registerClient(IMediaRoute2ProviderClient client) {
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::registerClient,
                    MediaRoute2ProviderService.this, client));
        }

        @Override
        public void selectRoute(int uid, String id) {
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSelect,
                    MediaRoute2ProviderService.this, uid, id));
        }
    }
}
