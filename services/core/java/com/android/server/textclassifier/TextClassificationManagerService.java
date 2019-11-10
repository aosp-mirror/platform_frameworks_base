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
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.service.textclassifier.ITextClassifierCallback;
import android.service.textclassifier.ITextClassifierService;
import android.service.textclassifier.TextClassifierService;
import android.service.textclassifier.TextClassifierService.ConnectionState;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.view.textclassifier.ConfigParser;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.SelectionEvent;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationConstants;
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
import java.util.Map;
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
                mManagerService.startListenSettings();
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

    private final TextClassifierSettingsListener mSettingsListener;
    private final Context mContext;
    private final Object mLock;
    @GuardedBy("mLock")
    final SparseArray<UserState> mUserStates = new SparseArray<>();
    @GuardedBy("mLock")
    private final Map<TextClassificationSessionId, Integer> mSessionUserIds = new ArrayMap<>();
    @GuardedBy("mLock")
    private TextClassificationConstants mSettings;

    private TextClassificationManagerService(Context context) {
        mContext = Preconditions.checkNotNull(context);
        mLock = new Object();
        mSettingsListener = new TextClassifierSettingsListener(mContext, this);
    }

    private void startListenSettings() {
        mSettingsListener.registerObserver();
    }


    @Override
    public void onConnectedStateChanged(@ConnectionState int connected) {
    }

    @Override
    public void onSuggestSelection(
            @Nullable TextClassificationSessionId sessionId,
            TextSelection.Request request, ITextClassifierCallback callback)
            throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        final int userId = request.getUserId();
        validateInput(mContext, request.getCallingPackageName(), userId);

        synchronized (mLock) {
            UserState userState = getUserStateLocked(userId);
            if (!userState.bindLocked()) {
                Slog.d(LOG_TAG, "Unable to bind TextClassifierService at suggestSelection.");
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                if (!userState.isRequestAcceptedLocked(Binder.getCallingUid())) {
                    Slog.d(LOG_TAG,
                            "Only allow to see own content for non-default service at "
                                    + "suggestSelection.");
                    return;
                }
                userState.mService.onSuggestSelection(sessionId, request, callback);
            } else {
                userState.mPendingRequests.add(new PendingRequest("suggestSelection",
                        () -> userState.mService.onSuggestSelection(sessionId, request, callback),
                        callback::onFailure, callback.asBinder(), this, userState,
                        Binder.getCallingUid()));
            }
        }
    }

    @Override
    public void onClassifyText(
            @Nullable TextClassificationSessionId sessionId,
            TextClassification.Request request, ITextClassifierCallback callback)
            throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        final int userId = request.getUserId();
        validateInput(mContext, request.getCallingPackageName(), userId);

        synchronized (mLock) {
            UserState userState = getUserStateLocked(userId);
            if (!userState.bindLocked()) {
                Slog.d(LOG_TAG, "Unable to bind TextClassifierService at classifyText.");
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                if (!userState.isRequestAcceptedLocked(Binder.getCallingUid())) {
                    Slog.d(LOG_TAG,
                            "Only allow to see own content for non-default service at "
                                    + "classifyText.");
                    return;
                }
                userState.mService.onClassifyText(sessionId, request, callback);
            } else {
                userState.mPendingRequests.add(new PendingRequest("classifyText",
                        () -> userState.mService.onClassifyText(sessionId, request, callback),
                        callback::onFailure, callback.asBinder(), this, userState,
                        Binder.getCallingUid()));
            }
        }
    }

    @Override
    public void onGenerateLinks(
            @Nullable TextClassificationSessionId sessionId,
            TextLinks.Request request, ITextClassifierCallback callback)
            throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        final int userId = request.getUserId();
        validateInput(mContext, request.getCallingPackageName(), userId);

        synchronized (mLock) {
            UserState userState = getUserStateLocked(userId);
            if (!userState.bindLocked()) {
                Slog.d(LOG_TAG, "Unable to bind TextClassifierService at generateLinks.");
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                if (!userState.isRequestAcceptedLocked(Binder.getCallingUid())) {
                    Slog.d(LOG_TAG,
                            "Only allow to see own content for non-default service at "
                                    + "generateLinks.");
                    return;
                }
                userState.mService.onGenerateLinks(sessionId, request, callback);
            } else {
                userState.mPendingRequests.add(new PendingRequest("generateLinks",
                        () -> userState.mService.onGenerateLinks(sessionId, request, callback),
                        callback::onFailure, callback.asBinder(), this, userState,
                        Binder.getCallingUid()));
            }
        }
    }

    @Override
    public void onSelectionEvent(
            @Nullable TextClassificationSessionId sessionId, SelectionEvent event)
            throws RemoteException {
        Preconditions.checkNotNull(event);
        final int userId = event.getUserId();
        validateInput(mContext, event.getPackageName(), userId);

        synchronized (mLock) {
            UserState userState = getUserStateLocked(userId);
            if (userState.isBoundLocked()) {
                if (!userState.isRequestAcceptedLocked(Binder.getCallingUid())) {
                    Slog.d(LOG_TAG,
                            "Only allow to see own content for non-default service at "
                                    + "selectionEvent.");
                    return;
                }
                userState.mService.onSelectionEvent(sessionId, event);
            } else {
                userState.mPendingRequests.add(new PendingRequest("selectionEvent",
                        () -> userState.mService.onSelectionEvent(sessionId, event),
                        null /* onServiceFailure */, null /* binder */, this, userState,
                        Binder.getCallingUid()));
            }
        }
    }
    @Override
    public void onTextClassifierEvent(
            @Nullable TextClassificationSessionId sessionId,
            TextClassifierEvent event) throws RemoteException {
        Preconditions.checkNotNull(event);
        final String packageName = event.getEventContext() == null
                ? null
                : event.getEventContext().getPackageName();
        final int userId = event.getEventContext() == null
                ? UserHandle.getCallingUserId()
                : event.getEventContext().getUserId();
        validateInput(mContext, packageName, userId);

        synchronized (mLock) {
            UserState userState = getUserStateLocked(userId);
            if (userState.isBoundLocked()) {
                if (!userState.isRequestAcceptedLocked(Binder.getCallingUid())) {
                    Slog.d(LOG_TAG,
                            "Only allow to see own content for non-default service at "
                                    + "textClassifierEvent.");
                    return;
                }
                userState.mService.onTextClassifierEvent(sessionId, event);
            } else {
                userState.mPendingRequests.add(new PendingRequest("textClassifierEvent",
                        () -> userState.mService.onTextClassifierEvent(sessionId, event),
                        null /* onServiceFailure */, null /* binder */, this, userState,
                        Binder.getCallingUid()));
            }
        }
    }

    @Override
    public void onDetectLanguage(
            @Nullable TextClassificationSessionId sessionId,
            TextLanguage.Request request,
            ITextClassifierCallback callback) throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        final int userId = request.getUserId();
        validateInput(mContext, request.getCallingPackageName(), userId);

        synchronized (mLock) {
            UserState userState = getUserStateLocked(userId);
            if (!userState.bindLocked()) {
                Slog.d(LOG_TAG, "Unable to bind TextClassifierService at detectLanguage.");
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                if (!userState.isRequestAcceptedLocked(Binder.getCallingUid())) {
                    Slog.d(LOG_TAG,
                            "Only allow to see own content for non-default service at "
                                    + "detectLanguage.");
                    return;
                }
                userState.mService.onDetectLanguage(sessionId, request, callback);
            } else {
                userState.mPendingRequests.add(new PendingRequest("detectLanguage",
                        () -> userState.mService.onDetectLanguage(sessionId, request, callback),
                        callback::onFailure, callback.asBinder(), this, userState,
                        Binder.getCallingUid()));
            }
        }
    }

    @Override
    public void onSuggestConversationActions(
            @Nullable TextClassificationSessionId sessionId,
            ConversationActions.Request request,
            ITextClassifierCallback callback) throws RemoteException {
        Preconditions.checkNotNull(request);
        Preconditions.checkNotNull(callback);
        final int userId = request.getUserId();
        validateInput(mContext, request.getCallingPackageName(), userId);

        synchronized (mLock) {
            UserState userState = getUserStateLocked(userId);
            if (!userState.bindLocked()) {
                Slog.d(LOG_TAG,
                        "Unable to bind TextClassifierService at suggestConversationActions.");
                callback.onFailure();
            } else if (userState.isBoundLocked()) {
                if (!userState.isRequestAcceptedLocked(Binder.getCallingUid())) {
                    Slog.d(LOG_TAG,
                            "Only allow to see own content for non-default service at "
                                    + "suggestConversationActions.");
                    return;
                }
                userState.mService.onSuggestConversationActions(sessionId, request, callback);
            } else {
                userState.mPendingRequests.add(new PendingRequest("suggestConversationActions",
                        () -> userState.mService.onSuggestConversationActions(sessionId, request,
                                callback),
                        callback::onFailure, callback.asBinder(), this, userState,
                        Binder.getCallingUid()));
            }
        }
    }

    @Override
    public void onCreateTextClassificationSession(
            TextClassificationContext classificationContext, TextClassificationSessionId sessionId)
            throws RemoteException {
        Preconditions.checkNotNull(sessionId);
        Preconditions.checkNotNull(classificationContext);
        final int userId = classificationContext.getUserId();
        validateInput(mContext, classificationContext.getPackageName(), userId);

        synchronized (mLock) {
            UserState userState = getUserStateLocked(userId);
            if (userState.isBoundLocked()) {
                if (!userState.isRequestAcceptedLocked(Binder.getCallingUid())) {
                    Slog.d(LOG_TAG,
                            "Only allow to see own content for non-default service at "
                                    + "createTextClassificationSession.");
                    return;
                }
                userState.mService.onCreateTextClassificationSession(
                        classificationContext, sessionId);
                mSessionUserIds.put(sessionId, userId);
            } else {
                userState.mPendingRequests.add(new PendingRequest("createTextClassificationSession",
                        () -> {
                            userState.mService.onCreateTextClassificationSession(
                                    classificationContext, sessionId);
                            mSessionUserIds.put(sessionId, userId);
                        },
                        null /* onServiceFailure */, null /* binder */, this, userState,
                        Binder.getCallingUid()));
            }
        }
    }

    @Override
    public void onDestroyTextClassificationSession(TextClassificationSessionId sessionId)
            throws RemoteException {
        Preconditions.checkNotNull(sessionId);

        synchronized (mLock) {
            final int userId = mSessionUserIds.containsKey(sessionId)
                    ? mSessionUserIds.get(sessionId)
                    : UserHandle.getCallingUserId();
            validateInput(mContext, null /* packageName */, userId);

            UserState userState = getUserStateLocked(userId);
            if (userState.isBoundLocked()) {
                if (!userState.isRequestAcceptedLocked(Binder.getCallingUid())) {
                    Slog.d(LOG_TAG,
                            "Only allow to see own content for non-default service at "
                                    + "destroyTextClassificationSession.");
                    return;
                }
                userState.mService.onDestroyTextClassificationSession(sessionId);
                mSessionUserIds.remove(sessionId);
            } else {
                userState.mPendingRequests.add(
                        new PendingRequest("destroyTextClassificationSession",
                                () -> {
                                    userState.mService.onDestroyTextClassificationSession(
                                            sessionId);
                                    mSessionUserIds.remove(sessionId);
                                },
                                null /* onServiceFailure */, null /* binder */, this, userState,
                                Binder.getCallingUid()));
            }
        }
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

        // Create a TCM instance with the system server identity. TCM creates a ContentObserver
        // to listen for settings changes. It does not pass the checkContentProviderAccess check
        // if we are using the shell identity, because AMS does not track of processes spawn from
        // shell.
        Binder.withCleanCallingIdentity(
                () -> mContext.getSystemService(TextClassificationManager.class).dump(pw));

        pw.printPair("context", mContext);
        pw.println();
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
            pw.println("Number of active sessions: " + mSessionUserIds.size());
        }
    }

    private static final class PendingRequest implements IBinder.DeathRecipient {

        private final int mUid;
        @Nullable private final String mName;
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
        PendingRequest(@Nullable String name,
                @NonNull ThrowingRunnable request, @Nullable ThrowingRunnable onServiceFailure,
                @Nullable IBinder binder,
                TextClassificationManagerService service,
                UserState owningUser, int uid) {
            mName = name;
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
            mUid = uid;
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

    private static void validateInput(
            Context context, @Nullable String packageName, @UserIdInt int userId)
            throws RemoteException {

        try {
            if (packageName != null) {
                final int packageUid = context.getPackageManager()
                        .getPackageUidAsUser(packageName, UserHandle.getCallingUserId());
                final int callingUid = Binder.getCallingUid();
                Preconditions.checkArgument(callingUid == packageUid
                        // Trust the system process:
                        || callingUid == android.os.Process.SYSTEM_UID,
                        "Invalid package name. Package=" + packageName
                                + ", CallingUid=" + callingUid);
            }

            Preconditions.checkArgument(userId != UserHandle.USER_NULL, "Null userId");
            final int callingUserId = UserHandle.getCallingUserId();
            if (callingUserId != userId) {
                context.enforceCallingOrSelfPermission(
                        android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                        "Invalid userId. UserId=" + userId + ", CallingUserId=" + callingUserId);
            }
        } catch (Exception e) {
            throw new RemoteException("Invalid request: " + e.getMessage(), e,
                    /* enableSuppression */ true, /* writableStackTrace */ true);
        }
    }

    private TextClassificationConstants getTextClassifierSettings(Context context) {
        synchronized (mLock) {
            if (mSettings == null) {
                mSettings = new TextClassificationConstants(
                        () ->  Settings.Global.getString(
                                context.getContentResolver(),
                                Settings.Global.TEXT_CLASSIFIER_CONSTANTS));
            }
            return mSettings;
        }
    }

    private void invalidateSettings() {
        synchronized (mLock) {
            mSettings = null;
        }
    }

    private void unbindServiceIfNeeded() {
        final ComponentName serviceComponentName =
                TextClassifierService.getServiceComponentName(mContext,
                        getTextClassifierSettings(mContext));
        if (serviceComponentName == null) {
            // It should not occur if we had defined default service name in config
            Slog.w(LOG_TAG, "No default configured system TextClassifierService.");
            return;
        }
        synchronized (mLock) {
            final int size = mUserStates.size();
            for (int i = 0; i < size; i++) {
                UserState userState = mUserStates.valueAt(i);
                // Only unbind for a new service
                if (userState.isServiceCurrentBoundLocked(serviceComponentName)) {
                    return;
                }
                if (userState.isBoundLocked()) {
                    userState.unbindLocked();
                }
            }
        }
    }

    private final class UserState {
        @UserIdInt final int mUserId;
        @GuardedBy("mLock")
        TextClassifierServiceConnection mConnection = null;
        @GuardedBy("mLock")
        final Queue<PendingRequest> mPendingRequests = new ArrayDeque<>();
        @GuardedBy("mLock")
        ITextClassifierService mService;
        @GuardedBy("mLock")
        boolean mBinding;
        @GuardedBy("mLock")
        ComponentName mBoundServiceComponent = null;
        @GuardedBy("mLock")
        boolean mIsBoundToDefaultService;
        @GuardedBy("mLock")
        int mBoundServiceUid;

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
                    if (!isRequestAcceptedLocked(request.mUid)) {
                        Slog.d(LOG_TAG,
                                "Only allow to see own content for non-default service at "
                                        + request.mName);
                        return;
                    }
                    request.mRequest.run();
                } else {
                    if (request.mOnServiceFailure != null) {
                        Slog.d(LOG_TAG, "Unable to bind TextClassifierService for PendingRequest "
                                + request.mName);
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

        @GuardedBy("mLock")
        private boolean isServiceCurrentBoundLocked(@NonNull ComponentName componentName) {
            return (mBoundServiceComponent != null
                    && mBoundServiceComponent.getPackageName().equals(
                    componentName.getPackageName()));
        }

        @GuardedBy("mLock")
        private void unbindLocked() {
            Slog.d(LOG_TAG, "unbinding to " + mBoundServiceComponent + " for " + mUserId);
            mContext.unbindService(mConnection);
            mConnection.cleanupService();
            mConnection = null;
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
                final ComponentName componentName =
                        TextClassifierService.getServiceComponentName(mContext,
                                getTextClassifierSettings(mContext));
                if (componentName == null) {
                    // Might happen if the storage is encrypted and the user is not unlocked
                    return false;
                }
                Intent serviceIntent = new Intent(TextClassifierService.SERVICE_INTERFACE)
                        .setComponent(componentName);
                Slog.d(LOG_TAG, "Binding to " + serviceIntent.getComponent());
                mConnection = new TextClassifierServiceConnection(mUserId);
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
                pw.printPair("BoundServiceComponent", mBoundServiceComponent);
                pw.printPair("isBoundToDefaultService", mIsBoundToDefaultService);
                pw.printPair("boundServiceUid", mBoundServiceUid);
                pw.printPair("binding", mBinding);
                pw.printPair("numberRequests", mPendingRequests.size());
            }
        }

        @GuardedBy("mLock")
        private boolean isRequestAcceptedLocked(int requestUid) {
            if (mIsBoundToDefaultService) {
                return true;
            }
            return (requestUid == mBoundServiceUid);
        }

        private boolean isDefaultService(@NonNull ComponentName currentService) {
            final String[] defaultServiceNames =
                    mContext.getPackageManager().getSystemTextClassifierPackages();
            final String servicePackageName = currentService.getPackageName();

            for (int i = 0; i < defaultServiceNames.length; i++) {
                if (defaultServiceNames[i].equals(servicePackageName)) {
                    return true;
                }
            }
            return false;
        }

        private int getServiceUid(@Nullable ComponentName service, int userId) {
            if (service == null) {
                return Process.INVALID_UID;
            }
            final String servicePackageName = service.getPackageName();
            final PackageManager pm = mContext.getPackageManager();
            final int serviceUid;

            try {
                serviceUid = pm.getPackageUidAsUser(servicePackageName, userId);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.e(LOG_TAG, "Could not verify UID for " + service);
                return Process.INVALID_UID;
            }
            return serviceUid;
        }

        @GuardedBy("mLock")
        private void updateServiceInfoLocked(@Nullable ComponentName componentName, int userId) {
            mBoundServiceComponent = componentName;
            mIsBoundToDefaultService = (mBoundServiceComponent != null && isDefaultService(
                    mBoundServiceComponent));
            mBoundServiceUid = getServiceUid(mBoundServiceComponent, userId);
        }

        private final class TextClassifierServiceConnection implements ServiceConnection {

            @UserIdInt final int mUserId;

            TextClassifierServiceConnection(int userId) {
                mUserId = userId;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                final ITextClassifierService tcService = ITextClassifierService.Stub.asInterface(
                        service);
                try {
                    tcService.onConnectedStateChanged(TextClassifierService.CONNECTED);
                } catch (RemoteException e) {
                    Slog.e(LOG_TAG, "error in onConnectedStateChanged");
                }
                init(tcService, name);
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
                init(/* service */ null, /* name */ null);
            }

            private void init(@Nullable ITextClassifierService service,
                    @Nullable ComponentName name) {
                synchronized (mLock) {
                    mService = service;
                    mBinding = false;
                    updateServiceInfoLocked(name, mUserId);
                    handlePendingRequestsLocked();
                }
            }
        }
    }

    private final class TextClassifierSettingsListener extends ContentObserver
            implements DeviceConfig.OnPropertiesChangedListener {

        @NonNull private final Context mContext;
        @Nullable private String mServicePackageName;

        TextClassifierSettingsListener(Context context, TextClassificationManagerService service) {
            super(null);
            mContext = context;
            mServicePackageName =
                    getTextClassifierSettings(mContext).getTextClassifierServiceName();
        }

        public void registerObserver() {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.TEXT_CLASSIFIER_CONSTANTS),
                    false /* notifyForDescendants */,
                    this);
            if (ConfigParser.ENABLE_DEVICE_CONFIG) {
                DeviceConfig.addOnPropertiesChangedListener(
                        DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                        mContext.getMainExecutor(),
                        this);
            }
        }

        private void updateChange() {
            final String overrideServiceName = getTextClassifierSettings(
                    mContext).getTextClassifierServiceName();
            if (TextUtils.equals(overrideServiceName, mServicePackageName)) {
                return;
            }
            mServicePackageName = overrideServiceName;
            unbindServiceIfNeeded();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            super.onChange(selfChange, uri);
            invalidateSettings();
            updateChange();
        }

        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            invalidateSettings();
            updateChange();
        }
    }
}
