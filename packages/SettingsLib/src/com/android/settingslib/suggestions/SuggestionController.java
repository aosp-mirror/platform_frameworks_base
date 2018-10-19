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

package com.android.settingslib.suggestions;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.settings.suggestions.ISuggestionService;
import android.service.settings.suggestions.Suggestion;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import android.util.Log;

import java.util.List;

/**
 * A controller class to access suggestion data.
 */
public class SuggestionController {

    /**
     * Callback interface when service is connected/disconnected.
     */
    public interface ServiceConnectionListener {
        /**
         * Called when service is connected.
         */
        void onServiceConnected();

        /**
         * Called when service is disconnected.
         */
        void onServiceDisconnected();
    }

    private static final String TAG = "SuggestionController";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final Intent mServiceIntent;

    private ServiceConnection mServiceConnection;
    private ISuggestionService mRemoteService;
    private ServiceConnectionListener mConnectionListener;

    /**
     * Create a new controller instance.
     *
     * @param context  caller context
     * @param service  The component name for service.
     * @param listener listener to receive service connected/disconnected event.
     */
    public SuggestionController(Context context, ComponentName service,
            ServiceConnectionListener listener) {
        mContext = context.getApplicationContext();
        mConnectionListener = listener;
        mServiceIntent = new Intent().setComponent(service);
        mServiceConnection = createServiceConnection();
    }

    /**
     * Start the controller.
     */
    public void start() {
        mContext.bindServiceAsUser(mServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE,
                android.os.Process.myUserHandle());
    }

    /**
     * Stop the controller.
     */
    public void stop() {
        if (mRemoteService != null) {
            mRemoteService = null;
            mContext.unbindService(mServiceConnection);
        }
    }

    /**
     * Get setting suggestions.
     */
    @Nullable
    @WorkerThread
    public List<Suggestion> getSuggestions() {
        if (!isReady()) {
            return null;
        }
        try {
            return mRemoteService.getSuggestions();
        } catch (NullPointerException e) {
            Log.w(TAG, "mRemote service detached before able to query", e);
            return null;
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Error when calling getSuggestion()", e);
            return null;
        }
    }

    public void dismissSuggestions(Suggestion suggestion) {
        if (!isReady()) {
            Log.w(TAG, "SuggestionController not ready, cannot dismiss " + suggestion.getId());
            return;
        }
        try {
            mRemoteService.dismissSuggestion(suggestion);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Error when calling dismissSuggestion()", e);
        }
    }

    public void launchSuggestion(Suggestion suggestion) {
        if (!isReady()) {
            Log.w(TAG, "SuggestionController not ready, cannot launch " + suggestion.getId());
            return;
        }

        try {
            mRemoteService.launchSuggestion(suggestion);
        } catch (RemoteException | RuntimeException e) {
            Log.w(TAG, "Error when calling launchSuggestion()", e);
        }
    }

    /**
     * Whether or not the manager is ready
     */
    private boolean isReady() {
        return mRemoteService != null;
    }

    /**
     * Create a new {@link ServiceConnection} object to handle service connect/disconnect event.
     */
    private ServiceConnection createServiceConnection() {
        return new ServiceConnection() {

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                if (DEBUG) {
                    Log.d(TAG, "Service is connected");
                }
                mRemoteService = ISuggestionService.Stub.asInterface(service);
                if (mConnectionListener != null) {
                    mConnectionListener.onServiceConnected();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (mConnectionListener != null) {
                    mRemoteService = null;
                    mConnectionListener.onServiceDisconnected();
                }
            }
        };
    }
}
