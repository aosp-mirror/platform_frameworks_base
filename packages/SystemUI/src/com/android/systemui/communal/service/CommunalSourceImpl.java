/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.communal.service;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceView;

import androidx.concurrent.futures.CallbackToFutureAdapter;

import com.android.systemui.communal.CommunalSource;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.communal.ICommunalSource;
import com.android.systemui.shared.communal.ICommunalSurfaceCallback;

import com.google.android.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * {@link CommunalSourceImpl} provides a wrapper around {@link ICommunalSource} proxies as an
 * implementation of {@link CommunalSource}. Requests and responses for communal surfaces are
 * translated into the proper binder calls.
 */
public class CommunalSourceImpl implements CommunalSource {
    private static final String TAG = "CommunalSourceImpl";
    private static final boolean DEBUG = false;
    private final ICommunalSource mSourceProxy;
    private final Executor mMainExecutor;

    static class Factory {
        private final Executor mExecutor;

        @Inject
        Factory(@Main Executor executor) {
            mExecutor = executor;
        }

        public CommunalSource create(ICommunalSource source) {
            return new CommunalSourceImpl(mExecutor, source);
        }
    }

    // mConnected is initialized to true as it is presumed instances are constructed with valid
    // proxies. The source can never be reconnected once the proxy has died. Once this value
    // becomes false, the source will always report disconnected to registering callbacks.
    private boolean mConnected = true;

    // A list of {@link Callback} that have registered to receive updates.
    private final ArrayList<WeakReference<Callback>> mCallbacks = Lists.newArrayList();

    public CommunalSourceImpl(Executor mainExecutor, ICommunalSource sourceProxy) {
        mMainExecutor = mainExecutor;
        mSourceProxy = sourceProxy;

        try {
            // Track connection status based on proxy lifetime.
            mSourceProxy.asBinder().linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    if (DEBUG) {
                        Log.d(TAG, "Source lost. Clearing reporting disconnect.");
                    }

                    // Set connection state and inform callbacks.
                    onDisconnected();
                }
            }, 0);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not link to the source proxy death:" + e);
        }
    }

    private void onDisconnected() {
        mConnected = false;
        for (WeakReference<Callback> cbRef : mCallbacks) {
            final Callback cb = cbRef.get();
            if (cb != null) {
                cb.onDisconnected();
            }
        }

        mCallbacks.clear();
    }

    @Override
    public ListenableFuture<CommunalViewResult> requestCommunalView(Context context) {
        if (DEBUG) {
            Log.d(TAG, "Received request for communal view");
        }
        ListenableFuture<CommunalViewResult> packageFuture =
                CallbackToFutureAdapter.getFuture(completer -> {
                    final SurfaceView view = new SurfaceView(context);
                    completer.set(new CommunalViewResult(view,
                            new CommunalSurfaceViewController(view, mMainExecutor, this)));
                    return "CommunalSourceImpl::requestCommunalSurface::getCommunalSurface";
                });

        return packageFuture;
    }

    /**
     * Called internally to request a new {@link android.view.SurfaceControlViewHost.SurfacePackage}
     * for showing communal content.
     *
     * @param hostToken The HostToken necessary to generate a {@link SurfaceControlViewHost}.
     * @param displayId The id of the display the surface will be shown on.
     * @param width     The width of the surface.
     * @param height    The height of the surface.
     * @return A future that returns the resulting
     * {@link android.view.SurfaceControlViewHost.SurfacePackage}.
     */
    protected ListenableFuture<SurfaceControlViewHost.SurfacePackage> requestCommunalSurface(
            IBinder hostToken, int displayId, int width, int height) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            mSourceProxy.getCommunalSurface(hostToken, width, height, displayId,
                    new ICommunalSurfaceCallback.Stub() {
                        @Override
                        public void onSurface(
                                SurfaceControlViewHost.SurfacePackage surfacePackage) {
                            completer.set(surfacePackage);
                        }
                    });
            return "CommunalSourceImpl::requestCommunalSurface::getCommunalSurface";
        });

    }

    @Override
    public void addCallback(Callback callback) {
        mCallbacks.add(new WeakReference<>(callback));

        // If not connected anymore, immediately inform new callback of disconnection and remove.
        if (!mConnected) {
            onDisconnected();
        }
    }

    @Override
    public void removeCallback(Callback callback) {
        mCallbacks.removeIf(el -> el.get() == callback);
    }
}
