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

    /**
     * Binder to receive calls from system server.
     */
    private final ISelectionToolbarRenderService mInterface =
            new ISelectionToolbarRenderService.Stub() {

        @Override
        public void onShow(ShowInfo showInfo, ISelectionToolbarCallback callback) {
            mHandler.sendMessage(obtainMessage(SelectionToolbarRenderService::onShow,
                    SelectionToolbarRenderService.this, showInfo,
                    new RemoteCallbackWrapper(callback)));
        }

        @Override
        public void onHide(long widgetToken) {
            mHandler.sendMessage(obtainMessage(SelectionToolbarRenderService::onHide,
                    SelectionToolbarRenderService.this, widgetToken));
        }

        @Override
        public void onDismiss(long widgetToken) {
            mHandler.sendMessage(obtainMessage(SelectionToolbarRenderService::onDismiss,
                    SelectionToolbarRenderService.this, widgetToken));
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


    /**
     * Called when showing the selection toolbar.
     */
    public abstract void onShow(ShowInfo showInfo, RemoteCallbackWrapper callbackWrapper);

    /**
     * Called when hiding the selection toolbar.
     */
    public abstract void onHide(long widgetToken);


    /**
     * Called when dismissing the selection toolbar.
     */
    public abstract void onDismiss(long widgetToken);

    /**
     * Add avadoc.
     */
    public static final class RemoteCallbackWrapper implements SelectionToolbarRenderCallback {

        private final ISelectionToolbarCallback mRemoteCallback;

        RemoteCallbackWrapper(ISelectionToolbarCallback remoteCallback) {
            mRemoteCallback = remoteCallback;
        }

        @Override
        public void onShown(WidgetInfo widgetInfo) {
            try {
                mRemoteCallback.onShown(widgetInfo);
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            }
        }

        @Override
        public void onHidden(long widgetToken) {
            try {
                mRemoteCallback.onHidden(widgetToken);
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            }
        }

        @Override
        public void onDismissed(long widgetToken) {
            try {
                mRemoteCallback.onDismissed(widgetToken);
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            }
        }

        @Override
        public void onWidgetUpdated(WidgetInfo widgetInfo) {
            try {
                mRemoteCallback.onWidgetUpdated(widgetInfo);
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            }
        }

        @Override
        public void onMenuItemClicked(ToolbarMenuItem item) {
            try {
                mRemoteCallback.onMenuItemClicked(item);
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            }
        }

        @Override
        public void onError(int errorCode) {
            try {
                mRemoteCallback.onError(errorCode);
            } catch (RemoteException e) {
                e.rethrowAsRuntimeException();
            }
        }
    }
}
