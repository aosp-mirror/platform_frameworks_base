/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.textclassifier;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.service.textclassifier.ITextClassifierService;
import android.service.textclassifier.ITextClassificationCallback;
import android.service.textclassifier.ITextLinksCallback;
import android.service.textclassifier.ITextSelectionCallback;
import android.service.textclassifier.TextClassifierService;
import android.view.textclassifier.SelectionEvent;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;
import android.view.textclassifier.TextSelection;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.Callable;

/**
 * A manager for TextClassifier services.
 * Apps bind to the TextClassificationManagerService for text classification. This service
 * reroutes calls to it to a {@link TextClassifierService} that it manages.
 */
public final class TextClassificationManagerService extends ITextClassifierService.Stub {

    private static final String LOG_TAG = "TextClassificationManagerService";

    public static final class Lifecycle extends SystemService {

        private final TextClassificationManagerService mManagerService;

        public Lifecycle(Context context) {
            super(context);
            mManagerService = new TextClassificationManagerService(context);
        }

        @Override
        public void onStart() {
            try {
                publishBinderService(Context.TEXT_CLASSIFICATION_SERVICE, mManagerService);
            } catch (Throwable t) {
                // Starting this service is not critical to the running of this device and should
                // therefore not crash the device. If it fails, log the error and continue.
                Slog.e(LOG_TAG, "Could not start the TextClassificationManagerService.", t);
            }
        }
    }

    private final Context mContext;
    private final Intent mServiceIntent;
    private final ServiceConnection mConnection;
    private final Object mLock;
    @GuardedBy("mLock")
    private final Queue<PendingRequest> mPendingRequests;

    @GuardedBy("mLock")
    private ITextClassifierService mService;
    @GuardedBy("mLock")
    private boolean mBinding;

    private TextClassificationManagerService(Context context) {
        mContext = Preconditions.checkNotNull(context);
        mServiceIntent = new Intent(TextClassifierService.SERVICE_INTERFACE)
                .setComponent(TextClassifierService.getServiceComponentName(mContext));
        mConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                synchronized (mLock) {
                    mService = ITextClassifierService.Stub.asInterface(service);
                    setBindingLocked(false);
                    handlePendingRequestsLocked();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                cleanupService();
            }

            @Override
            public void onBindingDied(ComponentName name) {
                cleanupService();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                cleanupService();
            }

            private void cleanupService() {
                synchronized (mLock) {
                    mService = null;
                    setBindingLocked(false);
                    handlePendingRequestsLocked();
                }
            }
        };
        mPendingRequests = new LinkedList<>();
        mLock = new Object();
    }

    @Override
    public void onSuggestSelection(
            CharSequence text, int selectionStartIndex, int selectionEndIndex,
            TextSelection.Options options, ITextSelectionCallback callback)
            throws RemoteException {
        // TODO(b/72481438): All remote calls need to take userId.
        validateInput(text, selectionStartIndex, selectionEndIndex, callback);

        if (!bind()) {
            callback.onFailure();
            return;
        }

        synchronized (mLock) {
            if (isBoundLocked()) {
                mService.onSuggestSelection(
                        text, selectionStartIndex, selectionEndIndex, options, callback);
            } else {
                final Callable<Void> request = () -> {
                    onSuggestSelection(
                            text, selectionStartIndex, selectionEndIndex,
                            options, callback);
                    return null;
                };
                final Callable<Void> onServiceFailure = () -> {
                    callback.onFailure();
                    return null;
                };
                enqueueRequestLocked(request, onServiceFailure, callback.asBinder());
            }
        }
    }

    @Override
    public void onClassifyText(
            CharSequence text, int startIndex, int endIndex,
            TextClassification.Options options, ITextClassificationCallback callback)
            throws RemoteException {
        validateInput(text, startIndex, endIndex, callback);

        if (!bind()) {
            callback.onFailure();
            return;
        }

        synchronized (mLock) {
            if (isBoundLocked()) {
                mService.onClassifyText(text, startIndex, endIndex, options, callback);
            } else {
                final Callable<Void> request = () -> {
                    onClassifyText(text, startIndex, endIndex, options, callback);
                    return null;
                };
                final Callable<Void> onServiceFailure = () -> {
                    callback.onFailure();
                    return null;
                };
                enqueueRequestLocked(request, onServiceFailure, callback.asBinder());
            }
        }
    }

    @Override
    public void onGenerateLinks(
            CharSequence text, TextLinks.Options options, ITextLinksCallback callback)
            throws RemoteException {
        validateInput(text, callback);

        if (!bind()) {
            callback.onFailure();
            return;
        }

        synchronized (mLock) {
            if (isBoundLocked()) {
                mService.onGenerateLinks(text, options, callback);
            } else {
                final Callable<Void> request = () -> {
                    onGenerateLinks(text, options, callback);
                    return null;
                };
                final Callable<Void> onServiceFailure = () -> {
                    callback.onFailure();
                    return null;
                };
                enqueueRequestLocked(request, onServiceFailure, callback.asBinder());
            }
        }
    }

    @Override
    public void onSelectionEvent(SelectionEvent event) throws RemoteException {
        validateInput(event, mContext);

        synchronized (mLock) {
            if (isBoundLocked()) {
                mService.onSelectionEvent(event);
            } else {
                final Callable<Void> request = () -> {
                    onSelectionEvent(event);
                    return null;
                };
                enqueueRequestLocked(request, null /* onServiceFailure */, null /* binder */);
            }
        }
    }

    /**
     * @return true if the service is bound or in the process of being bound.
     *      Returns false otherwise.
     */
    private boolean bind() {
        synchronized (mLock) {
            if (isBoundLocked() || isBindingLocked()) {
                return true;
            }

            // TODO: Handle bind timeout.
            final boolean willBind;
            final long identity = Binder.clearCallingIdentity();
            try {
                Slog.d(LOG_TAG, "Binding to " + mServiceIntent.getComponent());
                willBind = mContext.bindServiceAsUser(
                        mServiceIntent, mConnection,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE,
                        Binder.getCallingUserHandle());
                setBindingLocked(willBind);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return willBind;
        }
    }

    @GuardedBy("mLock")
    private boolean isBoundLocked() {
        return mService != null;
    }

    @GuardedBy("mLock")
    private boolean isBindingLocked() {
        return mBinding;
    }

    @GuardedBy("mLock")
    private void setBindingLocked(boolean binding) {
        mBinding = binding;
    }

    @GuardedBy("mLock")
    private void enqueueRequestLocked(
            Callable<Void> request, Callable<Void> onServiceFailure, IBinder binder) {
        mPendingRequests.add(new PendingRequest(request, onServiceFailure, binder));
    }

    @GuardedBy("mLock")
    private void handlePendingRequestsLocked() {
        // TODO(b/72481146): Implement PendingRequest similar to that in RemoteFillService.
        final PendingRequest[] pendingRequests =
                mPendingRequests.toArray(new PendingRequest[mPendingRequests.size()]);
        for (PendingRequest pendingRequest : pendingRequests) {
            if (isBoundLocked()) {
                pendingRequest.executeLocked();
            } else {
                pendingRequest.notifyServiceFailureLocked();
            }
        }
    }

    private final class PendingRequest implements IBinder.DeathRecipient {

        private final Callable<Void> mRequest;
        @Nullable private final Callable<Void> mOnServiceFailure;
        @Nullable private final IBinder mBinder;

        /**
         * Initializes a new pending request.
         *
         * @param request action to perform when the service is bound
         * @param onServiceFailure action to perform when the service dies or disconnects
         * @param binder binder to the process that made this pending request
         */
        PendingRequest(
                Callable<Void> request, @Nullable Callable<Void> onServiceFailure,
                @Nullable IBinder binder) {
            mRequest = Preconditions.checkNotNull(request);
            mOnServiceFailure = onServiceFailure;
            mBinder = binder;
            if (mBinder != null) {
                try {
                    mBinder.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }

        @GuardedBy("mLock")
        void executeLocked() {
            removeLocked();
            try {
                mRequest.call();
            } catch (Exception e) {
                Slog.d(LOG_TAG, "Error handling pending request: " + e.getMessage());
            }
        }

        @GuardedBy("mLock")
        void notifyServiceFailureLocked() {
            removeLocked();
            if (mOnServiceFailure != null) {
                try {
                    mOnServiceFailure.call();
                } catch (Exception e) {
                    Slog.d(LOG_TAG, "Error notifying callback of service failure: "
                            + e.getMessage());
                }
            }
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                // No need to handle this pending request anymore. Remove.
                removeLocked();
            }
        }

        @GuardedBy("mLock")
        private void removeLocked() {
            mPendingRequests.remove(this);
            if (mBinder != null) {
                mBinder.unlinkToDeath(this, 0);
            }
        }
    }

    private static void validateInput(
            CharSequence text, int startIndex, int endIndex, Object callback)
            throws RemoteException {
        try {
            TextClassifier.Utils.validate(text, startIndex, endIndex, true /* allowInMainThread */);
            Preconditions.checkNotNull(callback);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    private static void validateInput(CharSequence text, Object callback) throws RemoteException {
        try {
            TextClassifier.Utils.validate(text, true /* allowInMainThread */);
            Preconditions.checkNotNull(callback);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new RemoteException(e.getMessage());
        }
    }

    private static void validateInput(SelectionEvent event, Context context)
            throws RemoteException {
        try {
            final int uid = context.getPackageManager()
                    .getPackageUid(event.getPackageName(), 0);
            Preconditions.checkArgument(Binder.getCallingUid() == uid);
        } catch (IllegalArgumentException | NullPointerException |
                PackageManager.NameNotFoundException e) {
            throw new RemoteException(e.getMessage());
        }
    }
}
