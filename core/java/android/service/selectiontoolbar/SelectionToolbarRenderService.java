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

package android.service.selectiontoolbar;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.selectiontoolbar.ISelectionToolbarCallback;
import android.view.selectiontoolbar.ShowInfo;
import android.view.selectiontoolbar.ToolbarMenuItem;
import android.view.selectiontoolbar.WidgetInfo;

/**
 * Service for rendering selection toolbar.
 *
 * @hide
 */
public abstract class SelectionToolbarRenderService extends Service {

    private static final String TAG = "SelectionToolbarRenderService";

    // TODO(b/215497659): read from DeviceConfig
    // The timeout to clean the cache if the client forgot to call dismiss()
    private static final int CACHE_CLEAN_AFTER_SHOW_TIMEOUT_IN_MS = 10 * 60 * 1000; // 10 minutes

    /**
     * The {@link Intent} that must be declared as handled by the service.
     *
     * <p>To be supported, the service must also require the
     * {@link android.Manifest.permission#BIND_SELECTION_TOOLBAR_RENDER_SERVICE} permission so
     * that other applications can not abuse it.
     */
    public static final String SERVICE_INTERFACE =
            "android.service.selectiontoolbar.SelectionToolbarRenderService";

    private Handler mHandler;
    private ISelectionToolbarRenderServiceCallback mServiceCallback;

    private final SparseArray<Pair<RemoteCallbackWrapper, CleanCacheRunnable>> mCache =
            new SparseArray<>();

    /**
     * Binder to receive calls from system server.
     */
    private final ISelectionToolbarRenderService mInterface =
            new ISelectionToolbarRenderService.Stub() {

        @Override
        public void onShow(int callingUid, ShowInfo showInfo, ISelectionToolbarCallback callback) {
            if (mCache.indexOfKey(callingUid) < 0) {
                mCache.put(callingUid, new Pair<>(new RemoteCallbackWrapper(callback),
                        new CleanCacheRunnable(callingUid)));
            }
            Pair<RemoteCallbackWrapper, CleanCacheRunnable> toolbarPair = mCache.get(callingUid);
            CleanCacheRunnable cleanRunnable = toolbarPair.second;
            mHandler.removeCallbacks(cleanRunnable);
            mHandler.sendMessage(obtainMessage(SelectionToolbarRenderService::onShow,
                    SelectionToolbarRenderService.this, callingUid, showInfo,
                    toolbarPair.first));
            mHandler.postDelayed(cleanRunnable, CACHE_CLEAN_AFTER_SHOW_TIMEOUT_IN_MS);
        }

        @Override
        public void onHide(long widgetToken) {
            mHandler.sendMessage(obtainMessage(SelectionToolbarRenderService::onHide,
                    SelectionToolbarRenderService.this, widgetToken));
        }

        @Override
        public void onDismiss(int callingUid, long widgetToken) {
            mHandler.sendMessage(obtainMessage(SelectionToolbarRenderService::onDismiss,
                    SelectionToolbarRenderService.this, widgetToken));
            Pair<RemoteCallbackWrapper, CleanCacheRunnable> toolbarPair = mCache.get(callingUid);
            if (toolbarPair != null) {
                mHandler.removeCallbacks(toolbarPair.second);
                mCache.remove(callingUid);
            }
        }

        @Override
        public void onConnected(IBinder callback) {
            mHandler.sendMessage(obtainMessage(SelectionToolbarRenderService::handleOnConnected,
                    SelectionToolbarRenderService.this, callback));
        }
    };

    @CallSuper
    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper(), null, true);
    }

    @Override
    @Nullable
    public final IBinder onBind(@NonNull Intent intent) {
        if (SERVICE_INTERFACE.equals(intent.getAction())) {
            return mInterface.asBinder();
        }
        Log.w(TAG, "Tried to bind to wrong intent (should be " + SERVICE_INTERFACE + ": " + intent);
        return null;
    }

    private void handleOnConnected(@NonNull IBinder callback) {
        mServiceCallback = ISelectionToolbarRenderServiceCallback.Stub.asInterface(callback);
    }

    protected void transferTouch(@NonNull IBinder source, @NonNull IBinder target) {
        final ISelectionToolbarRenderServiceCallback callback = mServiceCallback;
        if (callback == null) {
            Log.e(TAG, "transferTouch(): no server callback");
            return;
        }
        try {
            callback.transferTouch(source, target);
        } catch (RemoteException e) {
            // no-op
        }
    }

    /**
     * Called when showing the selection toolbar.
     */
    public abstract void onShow(int callingUid, ShowInfo showInfo,
            RemoteCallbackWrapper callbackWrapper);

    /**
     * Called when hiding the selection toolbar.
     */
    public abstract void onHide(long widgetToken);


    /**
     * Called when dismissing the selection toolbar.
     */
    public abstract void onDismiss(long widgetToken);

    /**
     * Called when showing the selection toolbar for a specific timeout. This avoids the client
     * forgot to call dismiss to clean the state.
     */
    public void onToolbarShowTimeout(int callingUid) {
        // no-op
    }

    /**
     * Callback to notify the client toolbar events.
     */
    public static final class RemoteCallbackWrapper implements SelectionToolbarRenderCallback {

        private final ISelectionToolbarCallback mRemoteCallback;

        RemoteCallbackWrapper(ISelectionToolbarCallback remoteCallback) {
            // TODO(b/215497659): handle if the binder dies.
            mRemoteCallback = remoteCallback;
        }

        @Override
        public void onShown(WidgetInfo widgetInfo) {
            try {
                mRemoteCallback.onShown(widgetInfo);
            } catch (RemoteException e) {
                // no-op
            }
        }

        @Override
        public void onToolbarShowTimeout() {
            try {
                mRemoteCallback.onToolbarShowTimeout();
            } catch (RemoteException e) {
                // no-op
            }
        }

        @Override
        public void onWidgetUpdated(WidgetInfo widgetInfo) {
            try {
                mRemoteCallback.onWidgetUpdated(widgetInfo);
            } catch (RemoteException e) {
                // no-op
            }
        }

        @Override
        public void onMenuItemClicked(ToolbarMenuItem item) {
            try {
                mRemoteCallback.onMenuItemClicked(item);
            } catch (RemoteException e) {
                // no-op
            }
        }

        @Override
        public void onError(int errorCode) {
            try {
                mRemoteCallback.onError(errorCode);
            } catch (RemoteException e) {
                // no-op
            }
        }
    }

    private class CleanCacheRunnable implements Runnable {

        int mCleanUid;

        CleanCacheRunnable(int cleanUid) {
            mCleanUid = cleanUid;
        }

        @Override
        public void run() {
            Pair<RemoteCallbackWrapper, CleanCacheRunnable> toolbarPair = mCache.get(mCleanUid);
            if (toolbarPair != null) {
                Log.w(TAG, "CleanCacheRunnable: remove " + mCleanUid + " from cache.");
                mCache.remove(mCleanUid);
                onToolbarShowTimeout(mCleanUid);
            }
        }
    }

    /**
     * A listener to notify the service to the transfer touch focus.
     */
    public interface TransferTouchListener {
        /**
         * Notify the service to transfer the touch focus.
         */
        void onTransferTouch(IBinder source, IBinder target);
    }
}
