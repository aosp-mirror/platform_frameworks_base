/*
 * Copyright 2020 The Android Open Source Project
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

package android.service.quickaccesswallet;

import static android.service.quickaccesswallet.QuickAccessWalletService.SERVICE_INTERFACE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.widget.LockPatternUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

/**
 * Implements {@link QuickAccessWalletClient}. The client connects, performs requests, waits for
 * responses, and disconnects automatically after a short period of time. The client may
 * @hide
 */
public class QuickAccessWalletClientImpl implements QuickAccessWalletClient, ServiceConnection {

    private static final String TAG = "QAWalletSClient";
    private final Handler mHandler;
    private final Context mContext;
    private final Queue<ApiCaller> mRequestQueue;
    private final Map<WalletServiceEventListener, String> mEventListeners;
    private boolean mIsConnected;
    /**
     * Timeout for active service connections (1 minute)
     */
    private static final long SERVICE_CONNECTION_TIMEOUT_MS = 60 * 1000;
    @Nullable
    private IQuickAccessWalletService mService;

    @Nullable
    private final QuickAccessWalletServiceInfo mServiceInfo;

    private static final int MSG_TIMEOUT_SERVICE = 5;

    QuickAccessWalletClientImpl(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mServiceInfo = QuickAccessWalletServiceInfo.tryCreate(context);
        mHandler = new Handler(Looper.getMainLooper());
        mRequestQueue = new LinkedList<>();
        mEventListeners = new HashMap<>(1);
    }

    @Override
    public boolean isWalletServiceAvailable() {
        boolean available = mServiceInfo != null;
        Log.i(TAG, "isWalletServiceAvailable: " + available);
        return available;
    }

    @Override
    public boolean isWalletFeatureAvailable() {
        int currentUser = ActivityManager.getCurrentUser();
        return checkUserSetupComplete()
                && checkSecureSetting(Settings.Secure.GLOBAL_ACTIONS_PANEL_ENABLED)
                && !new LockPatternUtils(mContext).isUserInLockdown(currentUser);
    }

    @Override
    public boolean isWalletFeatureAvailableWhenDeviceLocked() {
        return checkSecureSetting(Settings.Secure.LOCK_SCREEN_SHOW_NOTIFICATIONS)
                && checkSecureSetting(Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS);
    }

    @Override
    public void getWalletCards(
            @NonNull GetWalletCardsRequest request,
            @NonNull OnWalletCardsRetrievedCallback callback) {

        Log.i(TAG, "getWalletCards");

        if (!isWalletServiceAvailable()) {
            callback.onWalletCardRetrievalError(new GetWalletCardsError(null, null));
            return;
        }

        BaseCallbacks serviceCallback = new BaseCallbacks() {
            @Override
            public void onGetWalletCardsSuccess(GetWalletCardsResponse response) {
                mHandler.post(() -> callback.onWalletCardsRetrieved(response));
            }

            @Override
            public void onGetWalletCardsFailure(GetWalletCardsError error) {
                mHandler.post(() -> callback.onWalletCardRetrievalError(error));
            }
        };

        executeApiCall(new ApiCaller("onWalletCardsRequested") {
            @Override
            public void performApiCall(IQuickAccessWalletService service) throws RemoteException {
                service.onWalletCardsRequested(request, serviceCallback);
            }

            @Override
            public void onApiError() {
                serviceCallback.onGetWalletCardsFailure(new GetWalletCardsError(null, null));
            }
        });
    }

    @Override
    public void selectWalletCard(@NonNull SelectWalletCardRequest request) {
        Log.i(TAG, "selectWalletCard");
        if (!isWalletServiceAvailable()) {
            return;
        }
        executeApiCall(new ApiCaller("onWalletCardSelected") {
            @Override
            public void performApiCall(IQuickAccessWalletService service) throws RemoteException {
                service.onWalletCardSelected(request);
            }
        });
    }

    @Override
    public void notifyWalletDismissed() {
        if (!isWalletServiceAvailable()) {
            return;
        }
        Log.i(TAG, "notifyWalletDismissed");
        executeApiCall(new ApiCaller("onWalletDismissed") {
            @Override
            public void performApiCall(IQuickAccessWalletService service) throws RemoteException {
                service.onWalletDismissed();
            }
        });
    }

    @Override
    public void addWalletServiceEventListener(WalletServiceEventListener listener) {
        if (!isWalletServiceAvailable()) {
            return;
        }
        Log.i(TAG, "registerWalletServiceEventListener");
        BaseCallbacks callback = new BaseCallbacks() {
            @Override
            public void onWalletServiceEvent(WalletServiceEvent event) {
                Log.i(TAG, "onWalletServiceEvent");
                mHandler.post(() -> listener.onWalletServiceEvent(event));
            }
        };

        executeApiCall(new ApiCaller("registerListener") {
            @Override
            public void performApiCall(IQuickAccessWalletService service) throws RemoteException {
                String listenerId = UUID.randomUUID().toString();
                WalletServiceEventListenerRequest request =
                        new WalletServiceEventListenerRequest(listenerId);
                mEventListeners.put(listener, listenerId);
                service.registerWalletServiceEventListener(request, callback);
            }
        });
    }

    @Override
    public void removeWalletServiceEventListener(WalletServiceEventListener listener) {
        if (!isWalletServiceAvailable()) {
            return;
        }
        Log.i(TAG, "unregisterWalletServiceEventListener");
        executeApiCall(new ApiCaller("unregisterListener") {
            @Override
            public void performApiCall(IQuickAccessWalletService service) throws RemoteException {
                String listenerId = mEventListeners.remove(listener);
                if (listenerId == null) {
                    return;
                }
                WalletServiceEventListenerRequest request =
                        new WalletServiceEventListenerRequest(listenerId);
                service.unregisterWalletServiceEventListener(request);
            }
        });
    }

    @Override
    public void disconnect() {
        Log.i(TAG, "disconnect");
        mHandler.post(() -> disconnectInternal(true));
    }

    @Override
    @Nullable
    public Intent createWalletIntent() {
        if (mServiceInfo == null || TextUtils.isEmpty(mServiceInfo.getWalletActivity())) {
            return null;
        }
        return new Intent(QuickAccessWalletService.ACTION_VIEW_WALLET)
                .setComponent(
                        new ComponentName(
                                mServiceInfo.getComponentName().getPackageName(),
                                mServiceInfo.getWalletActivity()));
    }

    @Override
    @Nullable
    public Intent createWalletSettingsIntent() {
        if (mServiceInfo == null || TextUtils.isEmpty(mServiceInfo.getSettingsActivity())) {
            return null;
        }
        return new Intent(QuickAccessWalletService.ACTION_VIEW_WALLET_SETTINGS)
                .setComponent(
                        new ComponentName(
                                mServiceInfo.getComponentName().getPackageName(),
                                mServiceInfo.getSettingsActivity()));
    }

    private void connect() {
        Log.i(TAG, "connect");
        mHandler.post(this::connectInternal);
    }

    private void connectInternal() {
        Log.i(TAG, "connectInternal");
        if (mServiceInfo == null) {
            Log.w(TAG, "Wallet service unavailable");
            return;
        }
        if (mIsConnected) {
            Log.w(TAG, "already connected");
            return;
        }
        mIsConnected = true;
        Intent intent = new Intent(SERVICE_INTERFACE);
        intent.setComponent(mServiceInfo.getComponentName());
        int flags = Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY;
        mContext.bindService(intent, this, flags);
        resetServiceConnectionTimeout();
    }

    private void onConnectedInternal(IQuickAccessWalletService service) {
        Log.i(TAG, "onConnectedInternal");
        if (!mIsConnected) {
            Log.w(TAG, "onConnectInternal but connection closed");
            mService = null;
            return;
        }
        mService = service;
        Log.i(TAG, "onConnectedInternal success: request queue size " + mRequestQueue.size());
        for (ApiCaller apiCaller : new ArrayList<>(mRequestQueue)) {
            try {
                apiCaller.performApiCall(mService);
            } catch (RemoteException e) {
                Log.e(TAG, "onConnectedInternal error", e);
                apiCaller.onApiError();
                disconnect();
                break;
            }
            mRequestQueue.remove(apiCaller);
        }
    }

    /**
     * Resets the idle timeout for this connection by removing any pending timeout messages and
     * posting a new delayed message.
     */
    private void resetServiceConnectionTimeout() {
        Log.i(TAG, "resetServiceConnectionTimeout");
        mHandler.removeMessages(MSG_TIMEOUT_SERVICE);
        mHandler.postDelayed(
                () -> disconnectInternal(true),
                MSG_TIMEOUT_SERVICE,
                SERVICE_CONNECTION_TIMEOUT_MS);
    }

    private void disconnectInternal(boolean clearEventListeners) {
        Log.i(TAG, "disconnectInternal: " + clearEventListeners);
        if (!mIsConnected) {
            Log.w(TAG, "already disconnected");
            return;
        }
        if (clearEventListeners && !mEventListeners.isEmpty()) {
            Log.i(TAG, "disconnectInternal: clear event listeners");
            for (WalletServiceEventListener listener : mEventListeners.keySet()) {
                removeWalletServiceEventListener(listener);
            }
            mHandler.post(() -> disconnectInternal(false));
            return;
        }
        mIsConnected = false;
        mContext.unbindService(/*conn=*/this);
        mService = null;
        mEventListeners.clear();
        mRequestQueue.clear();
    }

    private void executeApiCall(ApiCaller apiCaller) {
        Log.i(TAG, "execute: " + apiCaller.mDesc);
        mHandler.post(() -> executeInternal(apiCaller));
    }

    private void executeInternal(ApiCaller apiCall) {
        Log.i(TAG, "executeInternal: " + apiCall.mDesc);
        if (mIsConnected && mService != null) {
            try {
                apiCall.performApiCall(mService);
                Log.i(TAG, "executeInternal success: " + apiCall.mDesc);
                resetServiceConnectionTimeout();
            } catch (RemoteException e) {
                Log.w(TAG, "executeInternal error: " + apiCall.mDesc, e);
                apiCall.onApiError();
                disconnect();
            }
        } else {
            Log.i(TAG, "executeInternal: queued" + apiCall.mDesc);
            mRequestQueue.add(apiCall);
            connect();
        }
    }

    private abstract static class ApiCaller {
        private final String mDesc;

        private ApiCaller(String desc) {
            this.mDesc = desc;
        }

        abstract void performApiCall(IQuickAccessWalletService service) throws RemoteException;

        void onApiError() {
            Log.w(TAG, "api error: " + mDesc);
        }
    }

    @Override // ServiceConnection
    public void onServiceConnected(ComponentName name, IBinder binder) {
        Log.i(TAG, "onServiceConnected: " + name);
        IQuickAccessWalletService service = IQuickAccessWalletService.Stub.asInterface(binder);
        mHandler.post(() -> onConnectedInternal(service));
    }

    @Override // ServiceConnection
    public void onServiceDisconnected(ComponentName name) {
        // Do not disconnect, as we may later be re-connected
        Log.w(TAG, "onServiceDisconnected");
    }

    @Override // ServiceConnection
    public void onBindingDied(ComponentName name) {
        // This is a recoverable error but the client will need to reconnect.
        Log.w(TAG, "onBindingDied");
        disconnect();
    }

    @Override // ServiceConnection
    public void onNullBinding(ComponentName name) {
        Log.w(TAG, "onNullBinding");
        disconnect();
    }

    private boolean checkSecureSetting(String name) {
        return Settings.Secure.getInt(mContext.getContentResolver(), name, 0) == 1;
    }

    private boolean checkUserSetupComplete() {
        return Settings.Secure.getIntForUser(
                mContext.getContentResolver(),
                Settings.Secure.USER_SETUP_COMPLETE, 0,
                UserHandle.USER_CURRENT) == 1;
    }

    private static class BaseCallbacks extends IQuickAccessWalletServiceCallbacks.Stub {
        public void onGetWalletCardsSuccess(GetWalletCardsResponse response) {
            throw new IllegalStateException();
        }

        public void onGetWalletCardsFailure(GetWalletCardsError error) {
            throw new IllegalStateException();
        }

        public void onWalletServiceEvent(WalletServiceEvent event) {
            throw new IllegalStateException();
        }
    }
}
