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
    private IMediaRoute2ProviderClient mClient;
    private MediaRoute2ProviderInfo mProviderInfo;

    public MediaRoute2ProviderService() {
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public IBinder onBind(Intent intent) {
        //TODO: Allow binding from media router service only?
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
     * @param packageName the package name of the application that selected the route
     * @param routeId the id of the route being selected
     */
    public abstract void onSelectRoute(String packageName, String routeId);

    /**
     * Called when unselectRoute is called on a route of the provider.
     *
     * @param packageName the package name of the application that has selected the route.
     * @param routeId the id of the route being unselected
     */
    public abstract void onUnselectRoute(String packageName, String routeId);

    /**
     * Called when sendControlRequest is called on a route of the provider
     *
     * @param routeId the id of the target route
     * @param request the media control request intent
     */
    //TODO: Discuss what to use for request (e.g., Intent? Request class?)
    public abstract void onControlRequest(String routeId, Intent request);

    /**
     * Called when requestSetVolume is called on a route of the provider
     * @param routeId the id of the route
     * @param volume the target volume
     */
    public abstract void onSetVolume(String routeId, int volume);

    /**
     * Called when requestUpdateVolume is called on a route of the provider
     * @param routeId id of the route
     * @param delta the delta to add to the current volume
     */
    public abstract void onUpdateVolume(String routeId, int delta);

    /**
     * Updates provider info and publishes routes
     */
    public final void setProviderInfo(MediaRoute2ProviderInfo info) {
        mProviderInfo = info;
        publishState();
    }

    void setClient(IMediaRoute2ProviderClient client) {
        mClient = client;
        publishState();
    }

    void publishState() {
        if (mClient == null) {
            return;
        }
        try {
            mClient.updateProviderInfo(mProviderInfo);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to send onProviderInfoUpdated");
        }
    }

    final class ProviderStub extends IMediaRoute2Provider.Stub {
        ProviderStub() { }

        @Override
        public void setClient(IMediaRoute2ProviderClient client) {
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::setClient,
                    MediaRoute2ProviderService.this, client));
        }

        @Override
        public void selectRoute(String packageName, String id) {
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSelectRoute,
                    MediaRoute2ProviderService.this, packageName, id));
        }

        @Override
        public void unselectRoute(String packageName, String id) {
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onUnselectRoute,
                    MediaRoute2ProviderService.this, packageName, id));
        }

        @Override
        public void notifyControlRequestSent(String id, Intent request) {
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onControlRequest,
                    MediaRoute2ProviderService.this, id, request));
        }

        @Override
        public void requestSetVolume(String id, int volume) {
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSetVolume,
                    MediaRoute2ProviderService.this, id, volume));
        }

        @Override
        public void requestUpdateVolume(String id, int delta) {
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onUpdateVolume,
                    MediaRoute2ProviderService.this, id, delta));
        }
    }
}
