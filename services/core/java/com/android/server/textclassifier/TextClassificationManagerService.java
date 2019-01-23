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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.textclassifier.ITextClassifierCallback;
import android.service.textclassifier.ITextClassifierService;
import android.service.textclassifier.TextClassifierService;
import android.util.Slog;
import android.util.SparseArray;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.SelectionEvent;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassificationSessionId;
import android.view.textclassifier.TextClassifierEvent;
import android.view.textclassifier.TextLanguage;
import android.view.textclassifier.TextLinks;
import android.view.textclassifier.TextSelection;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.FunctionalUtils.ThrowingRunnable;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.Queue;

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

        @Override
        public void onStartUser(int userId) {
            processAnyPendingWork(userId);
        }

        @Override
        public void onUnlockUser(int userId) {
            // Rebind if we failed earlier due to locked encrypted user
            processAnyPendingWork(userId);
        }

        private void processAnyPendingWork(int userId) {
            synchronized (mManagerService.mLock) {
                mManagerService.getUserStateLocked(userId).bindIfHasPendingRequestsLocked();
            }
        }

        @Override
        public void onStopUser(int userId) {
            synchronized (mManagerService.mLock) {
                UserState userState = mManagerService.peekUserStateLocked(userId);
                if (userState != null) {
                    userState.mConnection.cleanupService();
                    mManagerService.mUserStates.remove(userId);
                }
            }
        }

    }

    private final Context mContext;
    private final Object mLock;
    @GuardedBy("mLock")
    final SparseArray<UserState> mUserStates = new SparseArray<>();

    private TextClassificationManagerService(Context context) {
        mContext = Preconditions.checkNotNull(context);
        mLock = new Object();
    }

    @Override
    public void onSuggestSelection(
            TextClassificationSessionId sessionId,
            TextSelection.Request request, ITextClassifierCallback callback)
            throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        validateInput(mContext, request.getCallingPackageName());

        synchronized (mLock) {
            UserState userState = getCallingUserStateLocked();
            if (!userState.bindLocked()) {
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                userState.mService.onSuggestSelection(sessionId, request, callback);
            } else {
                userState.mPendingRequests.add(new PendingRequest(
                        () -> onSuggestSelection(sessionId, request, callback),
                        callback::onFailure, callback.asBinder(), this, userState));
            }
        }
    }

    @Override
    public void onClassifyText(
            TextClassificationSessionId sessionId,
            TextClassification.Request request, ITextClassifierCallback callback)
            throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        validateInput(mContext, request.getCallingPackageName());

        synchronized (mLock) {
            UserState userState = getCallingUserStateLocked();
            if (!userState.bindLocked()) {
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                userState.mService.onClassifyText(sessionId, request, callback);
            } else {
                userState.mPendingRequests.add(new PendingRequest(
                        () -> onClassifyText(sessionId, request, callback),
                        callback::onFailure, callback.asBinder(), this, userState));
            }
        }
    }

    @Override
    public void onGenerateLinks(
            TextClassificationSessionId sessionId,
            TextLinks.Request request, ITextClassifierCallback callback)
            throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        validateInput(mContext, request.getCallingPackageName());

        synchronized (mLock) {
            UserState userState = getCallingUserStateLocked();
            if (!userState.bindLocked()) {
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                userState.mService.onGenerateLinks(sessionId, request, callback);
            } else {
                userState.mPendingRequests.add(new PendingRequest(
                        () -> onGenerateLinks(sessionId, request, callback),
                        callback::onFailure, callback.asBinder(), this, userState));
            }
        }
    }

    @Override
    public void onSelectionEvent(
            TextClassificationSessionId sessionId, SelectionEvent event) throws RemoteException {
        Preconditions.checkNotNull(event);
        validateInput(mContext, event.getPackageName());

        synchronized (mLock) {
            UserState userState = getCallingUserStateLocked();
            if (userState.isBoundLocked()) {
                userState.mService.onSelectionEvent(sessionId, event);
            } else {
                userState.mPendingRequests.add(new PendingRequest(
                        () -> onSelectionEvent(sessionId, event),
                        null /* onServiceFailure */, null /* binder */, this, userState));
            }
        }
    }
    @Override
    public void onTextClassifierEvent(
            TextClassificationSessionId sessionId,
            TextClassifierEvent event) throws RemoteException {
        Preconditions.checkNotNull(event);
        final String packageName = event.getEventContext() == null
                ? null
                : event.getEventContext().getPackageName();
        validateInput(mContext, packageName);

        synchronized (mLock) {
            UserState userState = getCallingUserStateLocked();
            if (userState.isBoundLocked()) {
                userState.mService.onTextClassifierEvent(sessionId, event);
            } else {
                userState.mPendingRequests.add(new PendingRequest(
                        () -> onTextClassifierEvent(sessionId, event),
                        null /* onServiceFailure */, null /* binder */, this, userState));
            }
        }
    }

    @Override
    public void onDetectLanguage(
            TextClassificationSessionId sessionId,
            TextLanguage.Request request,
            ITextClassifierCallback callback) throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        validateInput(mContext, request.getCallingPackageName());

        synchronized (mLock) {
            UserState userState = getCallingUserStateLocked();
            if (!userState.bindLocked()) {
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                userState.mService.onDetectLanguage(sessionId, request, callback);
            } else {
                userState.mPendingRequests.add(new PendingRequest(
                        () -> onDetectLanguage(sessionId, request, callback),
                        callback::onFailure, callback.asBinder(), this, userState));
            }
        }
    }

    @Override
    public void onSuggestConversationActions(
            TextClassificationSessionId sessionId,
            ConversationActions.Request request,
            ITextClassifierCallback callback) throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        validateInput(mContext, request.getCallingPackageName());

        synchronized (mLock) {
            UserState userState = getCallingUserStateLocked();
            if (!userState.bindLocked()) {
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                userState.mService.onSuggestConversationActions(sessionId, request, callback);
            } else {
                userState.mPendingRequests.add(new PendingRequest(
                        () -> onSuggestConversationActions(sessionId, request, callback),
                        callback::onFailure, callback.asBinder(), this, userState));
            }
        }
    }

    @Override
    public void onCreateTextClassificationSession(
            TextClassificationContext classificationContext, TextClassificationSessionId sessionId)
            throws RemoteException {
        Preconditions.checkNotNull(sessionId);
        Preconditions.checkNotNull(classificationContext);
        validateInput(mContext, classificationContext.getPackageName());

        synchronized (mLock) {
            UserState userState = getCallingUserStateLocked();
            if (userState.isBoundLocked()) {
                userState.mService.onCreateTextClassificationSession(
                        classificationContext, sessionId);
            } else {
                userState.mPendingRequests.add(new PendingRequest(
                        () -> onCreateTextClassificationSession(classificationContext, sessionId),
                        null /* onServiceFailure */, null /* binder */, this, userState));
            }
        }
    }

    @Override
    public void onDestroyTextClassificationSession(TextClassificationSessionId sessionId)
            throws RemoteException {
        Preconditions.checkNotNull(sessionId);

        synchronized (mLock) {
            UserState userState = getCallingUserStateLocked();
            if (userState.isBoundLocked()) {
                userState.mService.onDestroyTextClassificationSession(sessionId);
            } else {
                userState.mPendingRequests.add(new PendingRequest(
                        () -> onDestroyTextClassificationSession(sessionId),
                        null /* onServiceFailure */, null /* binder */, this, userState));
            }
        }
    }

    @GuardedBy("mLock")
    private UserState getCallingUserStateLocked() {
        return getUserStateLocked(UserHandle.getCallingUserId());
    }

    @GuardedBy("mLock")
    private UserState getUserStateLocked(int userId) {
        UserState result = mUserStates.get(userId);
        if (result == null) {
            result = new UserState(userId, mContext, mLock);
            mUserStates.put(userId, result);
        }
        return result;
    }

    @GuardedBy("mLock")
    UserState peekUserStateLocked(int userId) {
        return mUserStates.get(userId);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter fout, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, LOG_TAG, fout)) return;
        IndentingPrintWriter pw = new IndentingPrintWriter(fout, "  ");
        TextClassificationManager tcm = mContext.getSystemService(TextClassificationManager.class);
        tcm.dump(pw);

        pw.printPair("context", mContext); pw.println();
        synchronized (mLock) {
            int size = mUserStates.size();
            pw.print("Number user states: "); pw.println(size);
            if (size > 0) {
                for (int i = 0; i < size; i++) {
                    pw.increaseIndent();
                    UserState userState = mUserStates.valueAt(i);
                    pw.print(i); pw.print(":"); userState.dump(pw); pw.println();
                    pw.decreaseIndent();
                }
            }
        }
    }

    private static final class PendingRequest implements IBinder.DeathRecipient {

        @Nullable private final IBinder mBinder;
        @NonNull private final Runnable mRequest;
        @Nullable private final Runnable mOnServiceFailure;
        @GuardedBy("mLock")
        @NonNull private final UserState mOwningUser;
        @NonNull private final TextClassificationManagerService mService;

        /**
         * Initializes a new pending request.
         * @param request action to perform when the service is bound
         * @param onServiceFailure action to perform when the service dies or disconnects
         * @param binder binder to the process that made this pending request
         * @param service
         * @param owningUser
         */
        PendingRequest(
                @NonNull ThrowingRunnable request, @Nullable ThrowingRunnable onServiceFailure,
                @Nullable IBinder binder,
                TextClassificationManagerService service,
                UserState owningUser) {
            mRequest =
                    logOnFailure(Preconditions.checkNotNull(request), "handling pending request");
            mOnServiceFailure =
                    logOnFailure(onServiceFailure, "notifying callback of service failure");
            mBinder = binder;
            mService = service;
            mOwningUser = owningUser;
            if (mBinder != null) {
                try {
                    mBinder.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }

        @Override
        public void binderDied() {
            synchronized (mService.mLock) {
                // No need to handle this pending request anymore. Remove.
                removeLocked();
            }
        }

        @GuardedBy("mLock")
        private void removeLocked() {
            mOwningUser.mPendingRequests.remove(this);
            if (mBinder != null) {
                mBinder.unlinkToDeath(this, 0);
            }
        }
    }

    private static Runnable logOnFailure(@Nullable ThrowingRunnable r, String opDesc) {
        if (r == null) return null;
        return FunctionalUtils.handleExceptions(r,
                e -> Slog.d(LOG_TAG, "Error " + opDesc + ": " + e.getMessage()));
    }

    private static void validateInput(Context context, @Nullable String packageName)
            throws RemoteException {
        if (packageName == null) return;

        try {
            final int uid = context.getPackageManager()
                    .getPackageUidAsUser(packageName, UserHandle.getCallingUserId());
            Preconditions.checkArgument(Binder.getCallingUid() == uid);
        } catch (Exception e) {
            throw new RemoteException(
                    String.format("Invalid package: name=%s, error=%s", packageName, e));
        }
    }

    private static final class UserState {
        @UserIdInt final int mUserId;
        final TextClassifierServiceConnection mConnection = new TextClassifierServiceConnection();
        @GuardedBy("mLock")
        final Queue<PendingRequest> mPendingRequests = new ArrayDeque<>();
        @GuardedBy("mLock")
        ITextClassifierService mService;
        @GuardedBy("mLock")
        boolean mBinding;

        private final Context mContext;
        private final Object mLock;

        private UserState(int userId, Context context, Object lock) {
            mUserId = userId;
            mContext = Preconditions.checkNotNull(context);
            mLock = Preconditions.checkNotNull(lock);
        }

        @GuardedBy("mLock")
        boolean isBoundLocked() {
            return mService != null;
        }

        @GuardedBy("mLock")
        private void handlePendingRequestsLocked() {
            PendingRequest request;
            while ((request = mPendingRequests.poll()) != null) {
                if (isBoundLocked()) {
                    request.mRequest.run();
                } else {
                    if (request.mOnServiceFailure != null) {
                        request.mOnServiceFailure.run();
                    }
                }

                if (request.mBinder != null) {
                    request.mBinder.unlinkToDeath(request, 0);
                }
            }
        }

        @GuardedBy("mLock")
        private boolean bindIfHasPendingRequestsLocked() {
            return !mPendingRequests.isEmpty() && bindLocked();
        }

        /**
         * @return true if the service is bound or in the process of being bound.
         *      Returns false otherwise.
         */
        @GuardedBy("mLock")
        private boolean bindLocked() {
            if (isBoundLocked() || mBinding) {
                return true;
            }

            // TODO: Handle bind timeout.
            final boolean willBind;
            final long identity = Binder.clearCallingIdentity();
            try {
                ComponentName componentName =
                        TextClassifierService.getServiceComponentName(mContext);
                if (componentName == null) {
                    // Might happen if the storage is encrypted and the user is not unlocked
                    return false;
                }
                Intent serviceIntent = new Intent(TextClassifierService.SERVICE_INTERFACE)
                        .setComponent(componentName);
                Slog.d(LOG_TAG, "Binding to " + serviceIntent.getComponent());
                willBind = mContext.bindServiceAsUser(
                        serviceIntent, mConnection,
                        Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE
                                | Context.BIND_RESTRICT_ASSOCIATIONS,
                        UserHandle.of(mUserId));
                mBinding = willBind;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return willBind;
        }

        private void dump(IndentingPrintWriter pw) {
            pw.printPair("context", mContext);
            pw.printPair("userId", mUserId);
            synchronized (mLock) {
                pw.printPair("binding", mBinding);
                pw.printPair("numberRequests", mPendingRequests.size());
            }
        }

        private final class TextClassifierServiceConnection implements ServiceConnection {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                init(ITextClassifierService.Stub.asInterface(service));
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

            void cleanupService() {
                init(null);
            }

            private void init(@Nullable ITextClassifierService service) {
                synchronized (mLock) {
                    mService = service;
                    mBinding = false;
                    handlePendingRequestsLocked();
                }
            }
        }
    }
}
