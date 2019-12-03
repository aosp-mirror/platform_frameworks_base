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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import java.util.Objects;

/**
 * @hide
 */
public abstract class MediaRoute2ProviderService extends Service {
    private static final String TAG = "MR2ProviderService";

    public static final String SERVICE_INTERFACE = "android.media.MediaRoute2ProviderService";

    private final Handler mHandler;
    private ProviderStub mStub;
    private IMediaRoute2ProviderClient mClient;
    private MediaRoute2ProviderInfo mProviderInfo;

    public MediaRoute2ProviderService() {
        mHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public IBinder onBind(@NonNull Intent intent) {
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
     * Once the route is ready to be used , call {@link #notifyRouteSelected(SelectToken, Bundle)}
     * to notify that.
     *
     * @param packageName the package name of the application that selected the route
     * @param routeId the id of the route being selected
     * @param token token that contains select info
     *
     * @see #notifyRouteSelected
     */
    public abstract void onSelectRoute(@NonNull String packageName, @NonNull String routeId,
            @NonNull SelectToken token);

    /**
     * Called when unselectRoute is called on a route of the provider.
     *
     * @param packageName the package name of the application that has selected the route.
     * @param routeId the id of the route being unselected
     */
    public abstract void onUnselectRoute(@NonNull String packageName, @NonNull String routeId);

    /**
     * Called when sendControlRequest is called on a route of the provider
     *
     * @param routeId the id of the target route
     * @param request the media control request intent
     */
    //TODO: Discuss what to use for request (e.g., Intent? Request class?)
    public abstract void onControlRequest(@NonNull String routeId, @NonNull Intent request);

    /**
     * Called when requestSetVolume is called on a route of the provider
     * @param routeId the id of the route
     * @param volume the target volume
     */
    public abstract void onSetVolume(@NonNull String routeId, int volume);

    /**
     * Called when requestUpdateVolume is called on a route of the provider
     * @param routeId id of the route
     * @param delta the delta to add to the current volume
     */
    public abstract void onUpdateVolume(@NonNull String routeId, int delta);

    /**
     * Updates provider info and publishes routes
     */
    public final void setProviderInfo(MediaRoute2ProviderInfo info) {
        mProviderInfo = info;
        publishState();
    }

    /**
     * Notifies the client of that the selected route is ready for use. If the selected route can be
     * controlled, pass a {@link Bundle} that contains how to control it.
     *
     * @param token token passed in {@link #onSelectRoute}
     * @param controlHints a {@link Bundle} that contains how to control the given route.
     * Pass {@code null} if the route is not available.
     */
    public final void notifyRouteSelected(@NonNull SelectToken token,
            @Nullable Bundle controlHints) {
        Objects.requireNonNull(token, "token must not be null");

        if (mClient == null) {
            return;
        }
        try {
            mClient.notifyRouteSelected(token.mPackageName, token.mRouteId,
                    controlHints, token.mSeq);
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed to notify route selected");
        }
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

    /**
     * Route selection information.
     *
     * @see #notifyRouteSelected
     */
    public final class SelectToken {
        final String mPackageName;
        final String mRouteId;
        final int mSeq;

        SelectToken(String packageName, String routeId, int seq) {
            mPackageName = packageName;
            mRouteId = routeId;
            mSeq = seq;
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
        public void requestSelectRoute(String packageName, String id, int seq) {
            mHandler.sendMessage(obtainMessage(MediaRoute2ProviderService::onSelectRoute,
                    MediaRoute2ProviderService.this, packageName, id,
                    new SelectToken(packageName, id, seq)));

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
