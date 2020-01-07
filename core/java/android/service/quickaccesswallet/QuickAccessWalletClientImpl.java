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
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * @hide
 */
@SuppressWarnings("AndroidJdkLibsChecker")
class QuickAccessWalletClientImpl implements QuickAccessWalletClient, Handler.Callback,
        ServiceConnection {

    private static final String TAG = "QAWalletSClient";
    private final Handler mHandler;
    private final Context mContext;
    private final Queue<ApiCaller> mRequestQueue;
    private final Map<Consumer<WalletServiceEvent>, String> mEventListeners;
    private boolean mIsConnected;
    @Nullable
    private IQuickAccessWalletService mService;


    @Nullable
    private final QuickAccessWalletServiceInfo mServiceInfo;

    private static final int MSG_CONNECT = 1;
    private static final int MSG_CONNECTED = 2;
    private static final int MSG_EXECUTE = 3;
    private static final int MSG_DISCONNECT = 4;

    QuickAccessWalletClientImpl(@NonNull Context context) {
        mContext = context.getApplicationContext();
        mServiceInfo = QuickAccessWalletServiceInfo.tryCreate(context);
        mHandler = new Handler(Looper.getMainLooper(), this);
        mRequestQueue = new LinkedList<>();
        mEventListeners = new HashMap<>(1);
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_CONNECT:
                connectInternal();
                break;
            case MSG_CONNECTED:
                onConnectedInternal((IQuickAccessWalletService) msg.obj);
                break;
            case MSG_EXECUTE:
                executeInternal((ApiCaller) msg.obj);
                break;
            case MSG_DISCONNECT:
                disconnectInternal();
                break;
            default:
                Log.w(TAG, "Unknown what: " + msg.what);
                return false;
        }
        return true;
    }

    private void connect() {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_CONNECT));
    }

    private void connectInternal() {
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
    }

    private void onConnectedInternal(IQuickAccessWalletService service) {
        if (!mIsConnected) {
            Log.w(TAG, "onConnectInternal but connection closed");
            mService = null;
            return;
        }
        mService = service;
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

    private void disconnect() {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_DISCONNECT));
    }

    private void disconnectInternal() {
        if (!mIsConnected) {
            Log.w(TAG, "already disconnected");
            return;
        }
        mIsConnected = false;
        mContext.unbindService(/*conn=*/this);
        mService = null;
        mEventListeners.clear();
        mRequestQueue.clear();
    }

    private void execute(ApiCaller apiCaller) {
        mHandler.sendMessage(mHandler.obtainMessage(MSG_EXECUTE, apiCaller));
    }

    private void executeInternal(ApiCaller apiCall) {
        if (mIsConnected && mService != null) {
            try {
                apiCall.performApiCall(mService);
            } catch (RemoteException e) {
                Log.w(TAG, "executeInternal error", e);
                apiCall.onApiError();
                disconnect();
            }
        } else {
            mRequestQueue.add(apiCall);
            connect();
        }
    }

    public boolean isWalletServiceAvailable() {
        return mServiceInfo != null;
    }

    private abstract static class ApiCaller {
        abstract void performApiCall(IQuickAccessWalletService service) throws RemoteException;

        void onApiError() {
            Log.w(TAG, "api error");
        }
    }

    public void getWalletCards(
            @NonNull GetWalletCardsRequest request,
            @NonNull Consumer<GetWalletCardsResponse> onSuccessListener,
            @NonNull Consumer<GetWalletCardsError> onFailureListener) {

        BaseCallbacks callback = new BaseCallbacks() {
            @Override
            public void onGetWalletCardsSuccess(GetWalletCardsResponse response) {
                mHandler.post(() -> onSuccessListener.accept(response));
            }

            @Override
            public void onGetWalletCardsFailure(GetWalletCardsError error) {
                mHandler.post(() -> onFailureListener.accept(error));
            }
        };

        execute(new ApiCaller() {
            @Override
            public void performApiCall(IQuickAccessWalletService service) throws RemoteException {
                service.onWalletCardsRequested(request, callback);
            }

            @Override
            public void onApiError() {
                callback.onGetWalletCardsFailure(new GetWalletCardsError(null, null));
            }
        });
    }

    public void selectWalletCard(@NonNull SelectWalletCardRequest request) {
        execute(new ApiCaller() {
            @Override
            public void performApiCall(IQuickAccessWalletService service) throws RemoteException {
                service.onWalletCardSelected(request);
            }
        });
    }

    public void notifyWalletDismissed() {
        execute(new ApiCaller() {
            @Override
            public void performApiCall(IQuickAccessWalletService service) throws RemoteException {
                service.onWalletDismissed();
                mHandler.sendMessage(mHandler.obtainMessage(MSG_DISCONNECT));
            }
        });
    }

    @Override
    public void registerWalletServiceEventListener(Consumer<WalletServiceEvent> listener) {

        BaseCallbacks callback = new BaseCallbacks() {
            @Override
            public void onWalletServiceEvent(WalletServiceEvent event) {
                Log.i(TAG, "onWalletServiceEvent");
                mHandler.post(() -> listener.accept(event));
            }
        };

        execute(new ApiCaller() {
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
    public void unregisterWalletServiceEventListener(Consumer<WalletServiceEvent> listener) {
        execute(new ApiCaller() {
            @Override
            public void performApiCall(IQuickAccessWalletService service) throws RemoteException {
                String listenerId = mEventListeners.get(listener);
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
    @Nullable
    public Intent getWalletActivity() {
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
    public Intent getSettingsActivity() {
        if (mServiceInfo == null || TextUtils.isEmpty(mServiceInfo.getSettingsActivity())) {
            return null;
        }
        return new Intent(QuickAccessWalletService.ACTION_VIEW_WALLET_SETTINGS)
                .setComponent(
                        new ComponentName(
                                mServiceInfo.getComponentName().getPackageName(),
                                mServiceInfo.getSettingsActivity()));
    }

    /**
     * Connection to the {@link QuickAccessWalletService}
     */


    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        IQuickAccessWalletService service = IQuickAccessWalletService.Stub.asInterface(binder);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_CONNECTED, service));
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        // Do not disconnect, as we may later be re-connected
        Log.w(TAG, "onServiceDisconnected");
    }

    @Override
    public void onBindingDied(ComponentName name) {
        // This is a recoverable error but the client will need to reconnect.
        Log.w(TAG, "onBindingDied");
        disconnect();
    }

    @Override
    public void onNullBinding(ComponentName name) {
        Log.w(TAG, "onNullBinding");
        disconnect();
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
