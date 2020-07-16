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
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.service.textclassifier.ITextClassifierCallback;
import android.service.textclassifier.ITextClassifierService;
import android.service.textclassifier.TextClassifierService;
import android.service.textclassifier.TextClassifierService.ConnectionState;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.LruCache;
import android.util.Slog;
import android.util.SparseArray;
import android.view.textclassifier.ConversationAction;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.SelectionEvent;
import android.view.textclassifier.SystemTextClassifierMetadata;
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
import com.android.internal.util.FunctionalUtils.ThrowingConsumer;
import com.android.internal.util.FunctionalUtils.ThrowingRunnable;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A manager for TextClassifier services.
 * Apps bind to the TextClassificationManagerService for text classification. This service
 * reroutes calls to it to a {@link TextClassifierService} that it manages.
 */
public final class TextClassificationManagerService extends ITextClassifierService.Stub {

    private static final String LOG_TAG = "TextClassificationManagerService";

    private static final ITextClassifierCallback NO_OP_CALLBACK = new ITextClassifierCallback() {
        @Override
        public void onSuccess(Bundle result) {}

        @Override
        public void onFailure() {}

        @Override
        public IBinder asBinder() {
            return null;
        }
    };

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
                    userState.cleanupServiceLocked();
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
    private final SessionCache mSessionCache;
    private final TextClassificationConstants mSettings;
    @Nullable
    private final String mDefaultTextClassifierPackage;
    @Nullable
    private final String mSystemTextClassifierPackage;

    private TextClassificationManagerService(Context context) {
        mContext = Objects.requireNonNull(context);
        mLock = new Object();
        mSettings = new TextClassificationConstants();
        mSettingsListener = new TextClassifierSettingsListener(mContext);
        PackageManager packageManager = mContext.getPackageManager();
        mDefaultTextClassifierPackage = packageManager.getDefaultTextClassifierPackageName();
        mSystemTextClassifierPackage = packageManager.getSystemTextClassifierPackageName();
        mSessionCache = new SessionCache(mLock);
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
        Objects.requireNonNull(request);
        Objects.requireNonNull(request.getSystemTextClassifierMetadata());

        handleRequest(
                request.getSystemTextClassifierMetadata(),
                /* verifyCallingPackage= */ true,
                /* attemptToBind= */ true,
                service -> service.onSuggestSelection(sessionId, request, callback),
                "onSuggestSelection",
                callback);
    }

    @Override
    public void onClassifyText(
            @Nullable TextClassificationSessionId sessionId,
            TextClassification.Request request, ITextClassifierCallback callback)
            throws RemoteException {
        Objects.requireNonNull(request);
        Objects.requireNonNull(request.getSystemTextClassifierMetadata());

        handleRequest(
                request.getSystemTextClassifierMetadata(),
                /* verifyCallingPackage= */ true,
                /* attemptToBind= */ true,
                service -> service.onClassifyText(sessionId, request, wrap(callback)),
                "onClassifyText",
                callback);
    }

    @Override
    public void onGenerateLinks(
            @Nullable TextClassificationSessionId sessionId,
            TextLinks.Request request, ITextClassifierCallback callback)
            throws RemoteException {
        Objects.requireNonNull(request);
        Objects.requireNonNull(request.getSystemTextClassifierMetadata());

        handleRequest(
                request.getSystemTextClassifierMetadata(),
                /* verifyCallingPackage= */ true,
                /* attemptToBind= */ true,
                service -> service.onGenerateLinks(sessionId, request, callback),
                "onGenerateLinks",
                callback);
    }

    @Override
    public void onSelectionEvent(
            @Nullable TextClassificationSessionId sessionId, SelectionEvent event)
            throws RemoteException {
        Objects.requireNonNull(event);
        Objects.requireNonNull(event.getSystemTextClassifierMetadata());

        handleRequest(
                event.getSystemTextClassifierMetadata(),
                /* verifyCallingPackage= */ true,
                /* attemptToBind= */ true,
                service -> service.onSelectionEvent(sessionId, event),
                "onSelectionEvent",
                NO_OP_CALLBACK);
    }

    @Override
    public void onTextClassifierEvent(
            @Nullable TextClassificationSessionId sessionId,
            TextClassifierEvent event) throws RemoteException {
        Objects.requireNonNull(event);

        final TextClassificationContext eventContext = event.getEventContext();
        final SystemTextClassifierMetadata systemTcMetadata =
                eventContext != null ? eventContext.getSystemTextClassifierMetadata() : null;

        handleRequest(
                systemTcMetadata,
                /* verifyCallingPackage= */ true,
                /* attemptToBind= */ true,
                service -> service.onTextClassifierEvent(sessionId, event),
                "onTextClassifierEvent",
                NO_OP_CALLBACK);
    }

    @Override
    public void onDetectLanguage(
            @Nullable TextClassificationSessionId sessionId,
            TextLanguage.Request request,
            ITextClassifierCallback callback) throws RemoteException {
        Objects.requireNonNull(request);
        Objects.requireNonNull(request.getSystemTextClassifierMetadata());

        handleRequest(
                request.getSystemTextClassifierMetadata(),
                /* verifyCallingPackage= */ true,
                /* attemptToBind= */ true,
                service -> service.onDetectLanguage(sessionId, request, callback),
                "onDetectLanguage",
                callback);
    }

    @Override
    public void onSuggestConversationActions(
            @Nullable TextClassificationSessionId sessionId,
            ConversationActions.Request request,
            ITextClassifierCallback callback) throws RemoteException {
        Objects.requireNonNull(request);
        Objects.requireNonNull(request.getSystemTextClassifierMetadata());

        handleRequest(
                request.getSystemTextClassifierMetadata(),
                /* verifyCallingPackage= */ true,
                /* attemptToBind= */ true,
                service -> service.onSuggestConversationActions(
                        sessionId, request, wrap(callback)),
                "onSuggestConversationActions",
                callback);
    }

    @Override
    public void onCreateTextClassificationSession(
            TextClassificationContext classificationContext, TextClassificationSessionId sessionId)
            throws RemoteException {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(classificationContext);
        Objects.requireNonNull(classificationContext.getSystemTextClassifierMetadata());

        synchronized (mLock) {
            mSessionCache.put(sessionId, classificationContext);
        }
        handleRequest(
                classificationContext.getSystemTextClassifierMetadata(),
                /* verifyCallingPackage= */ true,
                /* attemptToBind= */ false,
                service -> {
                    service.onCreateTextClassificationSession(classificationContext, sessionId);
                },
                "onCreateTextClassificationSession",
                NO_OP_CALLBACK);
    }

    @Override
    public void onDestroyTextClassificationSession(TextClassificationSessionId sessionId)
            throws RemoteException {
        Objects.requireNonNull(sessionId);

        synchronized (mLock) {
            final StrippedTextClassificationContext textClassificationContext =
                    mSessionCache.get(sessionId);
            final int userId = textClassificationContext != null
                    ? textClassificationContext.userId
                    : UserHandle.getCallingUserId();
            final boolean useDefaultTextClassifier =
                    textClassificationContext != null
                            ? textClassificationContext.useDefaultTextClassifier
                            : true;
            final SystemTextClassifierMetadata sysTcMetadata = new SystemTextClassifierMetadata(
                    "", userId, useDefaultTextClassifier);

            handleRequest(
                    sysTcMetadata,
                    /* verifyCallingPackage= */ false,
                    /* attemptToBind= */ false,
                    service -> {
                        service.onDestroyTextClassificationSession(sessionId);
                        mSessionCache.remove(sessionId);
                    },
                    "onDestroyTextClassificationSession",
                    NO_OP_CALLBACK);
        }
    }

    @GuardedBy("mLock")
    private UserState getUserStateLocked(int userId) {
        UserState result = mUserStates.get(userId);
        if (result == null) {
            result = new UserState(userId);
            mUserStates.put(userId, result);
        }
        return result;
    }

    @GuardedBy("mLock")
    UserState peekUserStateLocked(int userId) {
        return mUserStates.get(userId);
    }

    private int resolvePackageToUid(@Nullable String packageName, @UserIdInt int userId) {
        if (packageName == null) {
            return Process.INVALID_UID;
        }
        final PackageManager pm = mContext.getPackageManager();
        try {
            return pm.getPackageUidAsUser(packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(LOG_TAG, "Could not get the UID for " + packageName);
        }
        return Process.INVALID_UID;
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
        pw.printPair("defaultTextClassifierPackage", mDefaultTextClassifierPackage);
        pw.println();
        pw.printPair("systemTextClassifierPackage", mSystemTextClassifierPackage);
        pw.println();
        synchronized (mLock) {
            int size = mUserStates.size();
            pw.print("Number user states: ");
            pw.println(size);
            if (size > 0) {
                for (int i = 0; i < size; i++) {
                    pw.increaseIndent();
                    UserState userState = mUserStates.valueAt(i);
                    pw.printPair("User", mUserStates.keyAt(i));
                    pw.println();
                    userState.dump(pw);
                    pw.decreaseIndent();
                }
            }
            pw.println("Number of active sessions: " + mSessionCache.size());
        }
    }

    private void handleRequest(
            @Nullable SystemTextClassifierMetadata sysTcMetadata,
            boolean verifyCallingPackage,
            boolean attemptToBind,
            @NonNull ThrowingConsumer<ITextClassifierService> textClassifierServiceConsumer,
            @NonNull String methodName,
            @NonNull ITextClassifierCallback callback) throws RemoteException {
        Objects.requireNonNull(textClassifierServiceConsumer);
        Objects.requireNonNull(methodName);
        Objects.requireNonNull(callback);

        final int userId =
                sysTcMetadata == null ? UserHandle.getCallingUserId() : sysTcMetadata.getUserId();
        final String callingPackageName =
                sysTcMetadata == null ? null : sysTcMetadata.getCallingPackageName();
        final boolean useDefaultTextClassifier =
                sysTcMetadata == null ? true : sysTcMetadata.useDefaultTextClassifier();

        try {
            if (verifyCallingPackage) {
                validateCallingPackage(callingPackageName);
            }
            validateUser(userId);
        } catch (Exception e) {
            throw new RemoteException("Invalid request: " + e.getMessage(), e,
                    /* enableSuppression */ true, /* writableStackTrace */ true);
        }
        synchronized (mLock) {
            UserState userState = getUserStateLocked(userId);
            ServiceState serviceState =
                    userState.getServiceStateLocked(useDefaultTextClassifier);
            if (serviceState == null) {
                Slog.d(LOG_TAG, "No configured system TextClassifierService");
                callback.onFailure();
            } else if (attemptToBind && !serviceState.bindLocked()) {
                Slog.d(LOG_TAG, "Unable to bind TextClassifierService at " + methodName);
                callback.onFailure();
            } else if (serviceState.isBoundLocked()) {
                if (!serviceState.checkRequestAcceptedLocked(Binder.getCallingUid(), methodName)) {
                    return;
                }
                textClassifierServiceConsumer.accept(serviceState.mService);
            } else {
                serviceState.mPendingRequests.add(
                        new PendingRequest(
                                methodName,
                                () -> textClassifierServiceConsumer.accept(serviceState.mService),
                                callback::onFailure, callback.asBinder(),
                                this,
                                serviceState,
                                Binder.getCallingUid()));
            }
        }
    }

    private static ITextClassifierCallback wrap(ITextClassifierCallback orig) {
        return new CallbackWrapper(orig);
    }

    private void onTextClassifierServicePackageOverrideChanged(String overriddenPackage) {
        synchronized (mLock) {
            final int size = mUserStates.size();
            for (int i = 0; i < size; i++) {
                UserState userState = mUserStates.valueAt(i);
                userState.onTextClassifierServicePackageOverrideChangedLocked(overriddenPackage);
            }
        }
    }

    private static final class PendingRequest implements IBinder.DeathRecipient {

        private final int mUid;
        @Nullable
        private final String mName;
        @Nullable
        private final IBinder mBinder;
        @NonNull
        private final Runnable mRequest;
        @Nullable
        private final Runnable mOnServiceFailure;
        @GuardedBy("mLock")
        @NonNull
        private final ServiceState mServiceState;
        @NonNull
        private final TextClassificationManagerService mService;

        /**
         * Initializes a new pending request.
         *
         * @param request          action to perform when the service is bound
         * @param onServiceFailure action to perform when the service dies or disconnects
         * @param binder           binder to the process that made this pending request
         * @parm service           the TCMS instance.
         * @param serviceState     the service state of the service that will execute the request.
         * @param uid              the calling uid of the request.
         */
        PendingRequest(@Nullable String name,
                @NonNull ThrowingRunnable request, @Nullable ThrowingRunnable onServiceFailure,
                @Nullable IBinder binder,
                @NonNull TextClassificationManagerService service,
                @NonNull ServiceState serviceState, int uid) {
            mName = name;
            mRequest =
                    logOnFailure(Objects.requireNonNull(request), "handling pending request");
            mOnServiceFailure =
                    logOnFailure(onServiceFailure, "notifying callback of service failure");
            mBinder = binder;
            mService = service;
            mServiceState = Objects.requireNonNull(serviceState);
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
            mServiceState.mPendingRequests.remove(this);
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

    private void validateCallingPackage(@Nullable String callingPackage)
            throws PackageManager.NameNotFoundException {
        if (callingPackage != null) {
            final int packageUid = mContext.getPackageManager()
                    .getPackageUidAsUser(callingPackage, UserHandle.getCallingUserId());
            final int callingUid = Binder.getCallingUid();
            Preconditions.checkArgument(
                    callingUid == packageUid
                            // Trust the system process:
                            || callingUid == android.os.Process.SYSTEM_UID,
                    "Invalid package name. callingPackage=" + callingPackage
                            + ", callingUid=" + callingUid);
        }
    }

    private void validateUser(@UserIdInt int userId) {
        Preconditions.checkArgument(userId != UserHandle.USER_NULL, "Null userId");
        final int callingUserId = UserHandle.getCallingUserId();
        if (callingUserId != userId) {
            mContext.enforceCallingOrSelfPermission(
                    android.Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                    "Invalid userId. UserId=" + userId + ", CallingUserId=" + callingUserId);
        }
    }

    /**
     * Stores the stripped down version of {@link TextClassificationContext}s, i.e. {@link
     * StrippedTextClassificationContext},  keyed by {@link TextClassificationSessionId}. Sessions
     * are cleaned up automatically when the client process is dead.
     */
    static final class SessionCache {
        private static final int MAX_CACHE_SIZE = 100;

        @NonNull
        private final Object mLock;
        @NonNull
        @GuardedBy("mLock")
        private final LruCache<TextClassificationSessionId, StrippedTextClassificationContext>
                mCache = new LruCache<>(MAX_CACHE_SIZE);
        @NonNull
        @GuardedBy("mLock")
        private final Map<TextClassificationSessionId, DeathRecipient> mDeathRecipients =
                new ArrayMap<>();

        SessionCache(@NonNull Object lock) {
            mLock = Objects.requireNonNull(lock);
        }

        void put(@NonNull TextClassificationSessionId sessionId,
                @NonNull TextClassificationContext textClassificationContext) {
            synchronized (mLock) {
                mCache.put(sessionId,
                        new StrippedTextClassificationContext(textClassificationContext));
                try {
                    DeathRecipient deathRecipient = () -> remove(sessionId);
                    sessionId.getToken().linkToDeath(deathRecipient, /* flags= */ 0);
                    mDeathRecipients.put(sessionId, deathRecipient);
                } catch (RemoteException e) {
                    Slog.w(LOG_TAG, "SessionCache: Failed to link to death", e);
                }
            }
        }

        @Nullable
        StrippedTextClassificationContext get(@NonNull TextClassificationSessionId sessionId) {
            Objects.requireNonNull(sessionId);
            synchronized (mLock) {
                return mCache.get(sessionId);
            }
        }

        void remove(@NonNull TextClassificationSessionId sessionId) {
            Objects.requireNonNull(sessionId);
            synchronized (mLock) {
                DeathRecipient deathRecipient = mDeathRecipients.get(sessionId);
                if (deathRecipient != null) {
                    sessionId.getToken().unlinkToDeath(deathRecipient, /* flags= */ 0);
                }
                mDeathRecipients.remove(sessionId);
                mCache.remove(sessionId);
            }
        }

        int size() {
            synchronized (mLock) {
                return mCache.size();
            }
        }
    }

    /** A stripped down version of {@link TextClassificationContext}. */
    static class StrippedTextClassificationContext {
        @UserIdInt
        public final int userId;
        public final boolean useDefaultTextClassifier;

        StrippedTextClassificationContext(TextClassificationContext textClassificationContext) {
            SystemTextClassifierMetadata sysTcMetadata =
                    textClassificationContext.getSystemTextClassifierMetadata();
            userId = sysTcMetadata.getUserId();
            useDefaultTextClassifier = sysTcMetadata.useDefaultTextClassifier();
        }
    }

    private final class UserState {
        @UserIdInt
        final int mUserId;
        @Nullable
        private final ServiceState mDefaultServiceState;
        @Nullable
        private final ServiceState mSystemServiceState;
        @GuardedBy("mLock")
        @Nullable
        private ServiceState mUntrustedServiceState;

        private UserState(int userId) {
            mUserId = userId;
            mDefaultServiceState = TextUtils.isEmpty(mDefaultTextClassifierPackage)
                    ? null
                    : new ServiceState(userId, mDefaultTextClassifierPackage, /* isTrusted= */true);
            mSystemServiceState = TextUtils.isEmpty(mSystemTextClassifierPackage)
                    ? null
                    : new ServiceState(userId, mSystemTextClassifierPackage, /* isTrusted= */ true);
        }

        @GuardedBy("mLock")
        @Nullable
        ServiceState getServiceStateLocked(boolean useDefaultTextClassifier) {
            if (useDefaultTextClassifier) {
                return mDefaultServiceState;
            }
            String textClassifierServicePackageOverride =
                    Binder.withCleanCallingIdentity(
                            mSettings::getTextClassifierServicePackageOverride);
            if (!TextUtils.isEmpty(textClassifierServicePackageOverride)) {
                if (textClassifierServicePackageOverride.equals(mDefaultTextClassifierPackage)) {
                    return mDefaultServiceState;
                }
                if (textClassifierServicePackageOverride.equals(mSystemTextClassifierPackage)
                        && mSystemServiceState != null) {
                    return mSystemServiceState;
                }
                if (mUntrustedServiceState == null) {
                    mUntrustedServiceState =
                            new ServiceState(
                                    mUserId,
                                    textClassifierServicePackageOverride,
                                    /* isTrusted= */false);
                }
                return mUntrustedServiceState;
            }
            return mSystemServiceState != null ? mSystemServiceState : mDefaultServiceState;
        }

        @GuardedBy("mLock")
        void onTextClassifierServicePackageOverrideChangedLocked(String overriddenPackageName) {
            // The override config is just used for testing, and the flag value is not expected
            // to change often. So, let's keep it simple and just unbind all the services here. The
            // right service will be bound when the next request comes.
            for (ServiceState serviceState : getAllServiceStatesLocked()) {
                serviceState.unbindIfBoundLocked();
            }
            mUntrustedServiceState = null;
        }

        @GuardedBy("mLock")
        void bindIfHasPendingRequestsLocked() {
            for (ServiceState serviceState : getAllServiceStatesLocked()) {
                serviceState.bindIfHasPendingRequestsLocked();
            }
        }

        @GuardedBy("mLock")
        void cleanupServiceLocked() {
            for (ServiceState serviceState : getAllServiceStatesLocked()) {
                if (serviceState.mConnection != null) {
                    serviceState.mConnection.cleanupService();
                }
            }
        }

        @GuardedBy("mLock")
        @NonNull
        private List<ServiceState> getAllServiceStatesLocked() {
            List<ServiceState> serviceStates = new ArrayList<>();
            if (mDefaultServiceState != null) {
                serviceStates.add(mDefaultServiceState);
            }
            if (mSystemServiceState != null) {
                serviceStates.add(mSystemServiceState);
            }
            if (mUntrustedServiceState != null) {
                serviceStates.add(mUntrustedServiceState);
            }
            return serviceStates;
        }

        void dump(IndentingPrintWriter pw) {
            synchronized (mLock) {
                pw.increaseIndent();
                dump(pw, mDefaultServiceState, "Default");
                dump(pw, mSystemServiceState, "System");
                dump(pw, mUntrustedServiceState, "Untrusted");
                pw.decreaseIndent();
            }
        }

        private void dump(
                IndentingPrintWriter pw, @Nullable ServiceState serviceState, String name) {
            synchronized (mLock) {
                if (serviceState != null) {
                    pw.print(name + ": ");
                    serviceState.dump(pw);
                    pw.println();
                }
            }
        }
    }

    private final class ServiceState {
        private static final int MAX_PENDING_REQUESTS = 20;

        @UserIdInt
        final int mUserId;
        @NonNull
        final String mPackageName;
        @NonNull
        final TextClassifierServiceConnection mConnection;
        final boolean mIsTrusted;
        @Context.BindServiceFlags
        final int mBindServiceFlags;
        @NonNull
        @GuardedBy("mLock")
        final FixedSizeQueue<PendingRequest> mPendingRequests =
                new FixedSizeQueue<>(MAX_PENDING_REQUESTS,
                        request -> {
                            Slog.w(LOG_TAG,
                                    String.format("Pending request[%s] is dropped", request.mName));
                            if (request.mOnServiceFailure != null) {
                                request.mOnServiceFailure.run();
                            }
                        });
        @Nullable
        @GuardedBy("mLock")
        ITextClassifierService mService;
        @GuardedBy("mLock")
        boolean mBinding;
        @Nullable
        @GuardedBy("mLock")
        ComponentName mBoundComponentName = null;
        @GuardedBy("mLock")
        int mBoundServiceUid = Process.INVALID_UID;

        private ServiceState(
                @UserIdInt int userId, @NonNull String packageName, boolean isTrusted) {
            mUserId = userId;
            mPackageName = packageName;
            mConnection = new TextClassifierServiceConnection(mUserId);
            mIsTrusted = isTrusted;
            mBindServiceFlags = createBindServiceFlags(packageName);
        }

        @Context.BindServiceFlags
        private int createBindServiceFlags(@NonNull String packageName) {
            int flags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE;
            if (!packageName.equals(mDefaultTextClassifierPackage)) {
                flags |= Context.BIND_RESTRICT_ASSOCIATIONS;
            }
            return flags;
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
                    if (!checkRequestAcceptedLocked(request.mUid, request.mName)) {
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
        void unbindIfBoundLocked() {
            if (isBoundLocked()) {
                Slog.v(LOG_TAG, "Unbinding " + mBoundComponentName + " for " + mUserId);
                mContext.unbindService(mConnection);
                mConnection.cleanupService();
            }
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
                final ComponentName componentName = getTextClassifierServiceComponent();
                if (componentName == null) {
                    // Might happen if the storage is encrypted and the user is not unlocked
                    return false;
                }
                Intent serviceIntent = new Intent(TextClassifierService.SERVICE_INTERFACE)
                        .setComponent(componentName);
                Slog.d(LOG_TAG, "Binding to " + serviceIntent.getComponent());
                willBind = mContext.bindServiceAsUser(
                        serviceIntent, mConnection, mBindServiceFlags, UserHandle.of(mUserId));
                if (!willBind) {
                    Slog.e(LOG_TAG, "Could not bind to " + componentName);
                }
                mBinding = willBind;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
            return willBind;
        }

        @Nullable
        private ComponentName getTextClassifierServiceComponent() {
            return TextClassifierService.getServiceComponentName(
                    mContext,
                    mPackageName,
                    mIsTrusted ? PackageManager.MATCH_SYSTEM_ONLY : 0);
        }

        private void dump(IndentingPrintWriter pw) {
            pw.printPair("context", mContext);
            pw.printPair("userId", mUserId);
            synchronized (mLock) {
                pw.printPair("packageName", mPackageName);
                pw.printPair("boundComponentName", mBoundComponentName);
                pw.printPair("isTrusted", mIsTrusted);
                pw.printPair("bindServiceFlags", mBindServiceFlags);
                pw.printPair("boundServiceUid", mBoundServiceUid);
                pw.printPair("binding", mBinding);
                pw.printPair("numOfPendingRequests", mPendingRequests.size());
            }
        }

        @GuardedBy("mLock")
        private boolean checkRequestAcceptedLocked(int requestUid, @NonNull String methodName) {
            if (mIsTrusted || (requestUid == mBoundServiceUid)) {
                return true;
            }
            Slog.w(LOG_TAG, String.format(
                    "[%s] Non-default TextClassifierServices may only see text from the same uid.",
                    methodName));
            return false;
        }

        @GuardedBy("mLock")
        private void updateServiceInfoLocked(int userId, @Nullable ComponentName componentName) {
            mBoundComponentName = componentName;
            mBoundServiceUid =
                    mBoundComponentName == null
                            ? Process.INVALID_UID
                            : resolvePackageToUid(mBoundComponentName.getPackageName(), userId);
        }

        private final class TextClassifierServiceConnection implements ServiceConnection {

            @UserIdInt
            private final int mUserId;

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
                Slog.i(LOG_TAG, "onServiceDisconnected called with " + name);
                cleanupService();
            }

            @Override
            public void onBindingDied(ComponentName name) {
                Slog.i(LOG_TAG, "onBindingDied called with " + name);
                cleanupService();
            }

            @Override
            public void onNullBinding(ComponentName name) {
                Slog.i(LOG_TAG, "onNullBinding called with " + name);
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
                    updateServiceInfoLocked(mUserId, name);
                    handlePendingRequestsLocked();
                }
            }
        }
    }

    private final class TextClassifierSettingsListener implements
            DeviceConfig.OnPropertiesChangedListener {
        @NonNull
        private final Context mContext;
        @Nullable
        private String mServicePackageOverride;


        TextClassifierSettingsListener(Context context) {
            mContext = context;
            mServicePackageOverride = mSettings.getTextClassifierServicePackageOverride();
        }

        void registerObserver() {
            DeviceConfig.addOnPropertiesChangedListener(
                    DeviceConfig.NAMESPACE_TEXTCLASSIFIER,
                    mContext.getMainExecutor(),
                    this);
        }

        @Override
        public void onPropertiesChanged(DeviceConfig.Properties properties) {
            final String currentServicePackageOverride =
                    mSettings.getTextClassifierServicePackageOverride();
            if (TextUtils.equals(currentServicePackageOverride, mServicePackageOverride)) {
                return;
            }
            mServicePackageOverride = currentServicePackageOverride;
            onTextClassifierServicePackageOverrideChanged(currentServicePackageOverride);
        }
    }

    /**
     * Wraps an ITextClassifierCallback and modifies the response to it where necessary.
     */
    private static final class CallbackWrapper extends ITextClassifierCallback.Stub {

        private final ITextClassifierCallback mWrapped;

        CallbackWrapper(ITextClassifierCallback wrapped) {
            mWrapped = Objects.requireNonNull(wrapped);
        }

        @Override
        public void onSuccess(Bundle result) {
            final Parcelable parcelled = TextClassifierService.getResponse(result);
            if (parcelled instanceof TextClassification) {
                rewriteTextClassificationIcons(result);
            } else if (parcelled instanceof ConversationActions) {
                rewriteConversationActionsIcons(result);
            } else {
                // do nothing.
            }
            try {
                mWrapped.onSuccess(result);
            } catch (RemoteException e) {
                Slog.e(LOG_TAG, "Callback error", e);
            }
        }

        private static void rewriteTextClassificationIcons(Bundle result) {
            final TextClassification classification = TextClassifierService.getResponse(result);
            boolean rewrite = false;
            final List<RemoteAction> actions = classification.getActions();
            final int size = actions.size();
            final List<RemoteAction> validActions = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                final RemoteAction action = actions.get(i);
                final RemoteAction validAction;
                if (shouldRewriteIcon(action)) {
                    rewrite = true;
                    validAction = validAction(action);
                } else {
                    validAction = action;
                }
                validActions.add(validAction);
            }
            if (rewrite) {
                TextClassifierService.putResponse(
                        result,
                        classification.toBuilder()
                                .clearActions()
                                .addActions(validActions)
                                .build());
            }
        }

        private static void rewriteConversationActionsIcons(Bundle result) {
            final ConversationActions convActions = TextClassifierService.getResponse(result);
            boolean rewrite = false;
            final List<ConversationAction> origConvActions = convActions.getConversationActions();
            final int size = origConvActions.size();
            final List<ConversationAction> validConvActions = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                final ConversationAction convAction = origConvActions.get(i);
                final ConversationAction validConvAction;
                if (shouldRewriteIcon(convAction.getAction())) {
                    rewrite = true;
                    validConvAction = convAction.toBuilder()
                            .setAction(validAction(convAction.getAction()))
                            .build();
                } else {
                    validConvAction = convAction;
                }
                validConvActions.add(validConvAction);
            }
            if (rewrite) {
                TextClassifierService.putResponse(
                        result,
                        new ConversationActions(validConvActions, convActions.getId()));
            }
        }

        private static RemoteAction validAction(RemoteAction action) {
            final RemoteAction newAction = new RemoteAction(
                    changeIcon(action.getIcon()),
                    action.getTitle(),
                    action.getContentDescription(),
                    action.getActionIntent());
            newAction.setEnabled(action.isEnabled());
            newAction.setShouldShowIcon(action.shouldShowIcon());
            return newAction;
        }

        private static boolean shouldRewriteIcon(@Nullable RemoteAction action) {
            // Check whether to rewrite the icon.
            // Rewrite icons to ensure that the icons do not:
            // 1. Leak package names
            // 2. are renderable in the client process.
            return action != null && action.getIcon().getType() == Icon.TYPE_RESOURCE;
        }

        /** Changes icon of type=RESOURCES to icon of type=URI. */
        private static Icon changeIcon(Icon icon) {
            final Uri uri = IconsUriHelper.getInstance()
                    .getContentUri(icon.getResPackage(), icon.getResId());
            return Icon.createWithContentUri(uri);
        }

        @Override
        public void onFailure() {
            try {
                mWrapped.onFailure();
            } catch (RemoteException e) {
                Slog.e(LOG_TAG, "Callback error", e);
            }
        }
    }
}
