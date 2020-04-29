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

import static android.service.quickaccesswallet.QuickAccessWalletService.ACTION_VIEW_WALLET;
import static android.service.quickaccesswallet.QuickAccessWalletService.ACTION_VIEW_WALLET_SETTINGS;
import static android.service.quickaccesswallet.QuickAccessWalletService.SERVICE_INTERFACE;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.widget.LockPatternUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * Implements {@link QuickAccessWalletClient}. The client connects, performs requests, waits for
 * responses, and disconnects automatically one minute after the last call is performed.
 *
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
        return mServiceInfo != null;
    }

    @Override
    public boolean isWalletFeatureAvailable() {
        int currentUser = ActivityManager.getCurrentUser();
        return currentUser == UserHandle.USER_SYSTEM
                && checkUserSetupComplete()
                && checkSecureSetting(Settings.Secure.GLOBAL_ACTIONS_PANEL_ENABLED)
                && !new LockPatternUtils(mContext).isUserInLockdown(currentUser);
    }

    @Override
    public boolean isWalletFeatureAvailableWhenDeviceLocked() {
        return checkSecureSetting(Settings.Secure.POWER_MENU_LOCKED_SHOW_CONTENT);
    }

    @Override
    public void getWalletCards(
            @NonNull GetWalletCardsRequest request,
            @NonNull OnWalletCardsRetrievedCallback callback) {
        getWalletCards(mContext.getMainExecutor(), request, callback);
    }

    @Override
    public void getWalletCards(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull GetWalletCardsRequest request,
            @NonNull OnWalletCardsRetrievedCallback callback) {
        if (!isWalletServiceAvailable()) {
            executor.execute(
                    () -> callback.onWalletCardRetrievalError(new GetWalletCardsError(null, null)));
            return;
        }

        BaseCallbacks serviceCallback = new BaseCallbacks() {
            @Override
            public void onGetWalletCardsSuccess(GetWalletCardsResponse response) {
                executor.execute(() -> callback.onWalletCardsRetrieved(response));
            }

            @Override
            public void onGetWalletCardsFailure(GetWalletCardsError error) {
                executor.execute(() -> callback.onWalletCardRetrievalError(error));
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
        executeApiCall(new ApiCaller("onWalletDismissed") {
            @Override
            public void performApiCall(IQuickAccessWalletService service) throws RemoteException {
                service.onWalletDismissed();
            }
        });
    }

    @Override
    public void addWalletServiceEventListener(WalletServiceEventListener listener) {
        addWalletServiceEventListener(mContext.getMainExecutor(), listener);
    }

    @Override
    public void addWalletServiceEventListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull WalletServiceEventListener listener) {
        if (!isWalletServiceAvailable()) {
            return;
        }
        BaseCallbacks callback = new BaseCallbacks() {
            @Override
            public void onWalletServiceEvent(WalletServiceEvent event) {
                executor.execute(() -> listener.onWalletServiceEvent(event));
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
    public void close() throws IOException {
        disconnect();
    }

    @Override
    public void disconnect() {
        mHandler.post(() -> disconnectInternal(true));
    }

    @Override
    @Nullable
    public Intent createWalletIntent() {
        if (mServiceInfo == null) {
            return null;
        }
        String packageName = mServiceInfo.getComponentName().getPackageName();
        String walletActivity = mServiceInfo.getWalletActivity();
        return createIntent(walletActivity, packageName, ACTION_VIEW_WALLET);
    }

    @Override
    @Nullable
    public Intent createWalletSettingsIntent() {
        if (mServiceInfo == null) {
            return null;
        }
        String packageName = mServiceInfo.getComponentName().getPackageName();
        String settingsActivity = mServiceInfo.getSettingsActivity();
        return createIntent(settingsActivity, packageName, ACTION_VIEW_WALLET_SETTINGS);
    }

    @Nullable
    private Intent createIntent(@Nullable String activityName, String packageName, String action) {
        PackageManager pm = mContext.getPackageManager();
        if (TextUtils.isEmpty(activityName)) {
            activityName = queryActivityForAction(pm, packageName, action);
        }
        if (TextUtils.isEmpty(activityName)) {
            return null;
        }
        ComponentName component = new ComponentName(packageName, activityName);
        if (!isActivityEnabled(pm, component)) {
            return null;
        }
        return new Intent(action).setComponent(component);
    }

    @Nullable
    private static String queryActivityForAction(PackageManager pm, String packageName,
            String action) {
        Intent intent = new Intent(action).setPackage(packageName);
        ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);
        if (resolveInfo == null
                || resolveInfo.activityInfo == null
                || !resolveInfo.activityInfo.exported) {
            return null;
        }
        return resolveInfo.activityInfo.name;
    }

    private static boolean isActivityEnabled(PackageManager pm, ComponentName component) {
        int setting = pm.getComponentEnabledSetting(component);
        if (setting == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return true;
        }
        if (setting != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            return false;
        }
        try {
            return pm.getActivityInfo(component, 0).isEnabled();
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    @Override
    @Nullable
    public Drawable getLogo() {
        return mServiceInfo == null ? null : mServiceInfo.getWalletLogo(mContext);
    }

    @Override
    @Nullable
    public CharSequence getServiceLabel() {
        return mServiceInfo == null ? null : mServiceInfo.getServiceLabel(mContext);
    }

    @Override
    @Nullable
    public CharSequence getShortcutShortLabel() {
        return mServiceInfo == null ? null : mServiceInfo.getShortcutShortLabel(mContext);
    }

    @Override
    public CharSequence getShortcutLongLabel() {
        return mServiceInfo == null ? null : mServiceInfo.getShortcutLongLabel(mContext);
    }

    private void connect() {
        mHandler.post(this::connectInternal);
    }

    private void connectInternal() {
        if (mServiceInfo == null) {
            Log.w(TAG, "Wallet service unavailable");
            return;
        }
        if (mIsConnected) {
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
        if (!mIsConnected) {
            Log.w(TAG, "onConnectInternal but connection closed");
            mService = null;
            return;
        }
        mService = service;
        for (ApiCaller apiCaller : new ArrayList<>(mRequestQueue)) {
            performApiCallInternal(apiCaller, mService);
            mRequestQueue.remove(apiCaller);
        }
    }

    /**
     * Resets the idle timeout for this connection by removing any pending timeout messages and
     * posting a new delayed message.
     */
    private void resetServiceConnectionTimeout() {
        mHandler.removeMessages(MSG_TIMEOUT_SERVICE);
        mHandler.postDelayed(
                () -> disconnectInternal(true),
                MSG_TIMEOUT_SERVICE,
                SERVICE_CONNECTION_TIMEOUT_MS);
    }

    private void disconnectInternal(boolean clearEventListeners) {
        if (!mIsConnected) {
            Log.w(TAG, "already disconnected");
            return;
        }
        if (clearEventListeners && !mEventListeners.isEmpty()) {
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
        mHandler.post(() -> executeInternal(apiCaller));
    }

    private void executeInternal(ApiCaller apiCaller) {
        if (mIsConnected && mService != null) {
            performApiCallInternal(apiCaller, mService);
        } else {
            mRequestQueue.add(apiCaller);
            connect();
        }
    }

    private void performApiCallInternal(ApiCaller apiCaller, IQuickAccessWalletService service) {
        if (service == null) {
            apiCaller.onApiError();
            return;
        }
        try {
            apiCaller.performApiCall(service);
            resetServiceConnectionTimeout();
        } catch (RemoteException e) {
            Log.w(TAG, "executeInternal error: " + apiCaller.mDesc, e);
            apiCaller.onApiError();
            disconnect();
        }
    }

    private abstract static class ApiCaller {
        private final String mDesc;

        private ApiCaller(String desc) {
            this.mDesc = desc;
        }

        abstract void performApiCall(IQuickAccessWalletService service)
                throws RemoteException;

        void onApiError() {
            Log.w(TAG, "api error: " + mDesc);
        }
    }

    @Override // ServiceConnection
    public void onServiceConnected(ComponentName name, IBinder binder) {
        IQuickAccessWalletService service = IQuickAccessWalletService.Stub.asInterface(binder);
        mHandler.post(() -> onConnectedInternal(service));
    }

    @Override // ServiceConnection
    public void onServiceDisconnected(ComponentName name) {
        // Do not disconnect, as we may later be re-connected
    }

    @Override // ServiceConnection
    public void onBindingDied(ComponentName name) {
        // This is a recoverable error but the client will need to reconnect.
        disconnect();
    }

    @Override // ServiceConnection
    public void onNullBinding(ComponentName name) {
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
