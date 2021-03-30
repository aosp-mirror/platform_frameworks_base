/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.autofill;

import static android.service.autofill.FillRequest.FLAG_MANUAL_REQUEST;
import static android.view.autofill.AutofillManager.ACTION_START_SESSION;
import static android.view.autofill.AutofillManager.FLAG_ADD_CLIENT_ENABLED;
import static android.view.autofill.AutofillManager.FLAG_ADD_CLIENT_ENABLED_FOR_AUGMENTED_AUTOFILL_ONLY;
import static android.view.autofill.AutofillManager.NO_SESSION;
import static android.view.autofill.AutofillManager.RECEIVER_FLAG_SESSION_FOR_AUGMENTED_AUTOFILL_ONLY;

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManagerInternal;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.metrics.LogMaker;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.autofill.AutofillService;
import android.service.autofill.AutofillServiceInfo;
import android.service.autofill.FieldClassification;
import android.service.autofill.FieldClassification.Match;
import android.service.autofill.FillEventHistory;
import android.service.autofill.FillEventHistory.Event;
import android.service.autofill.FillEventHistory.Event.NoSaveReason;
import android.service.autofill.FillResponse;
import android.service.autofill.IAutoFillService;
import android.service.autofill.InlineSuggestionRenderService;
import android.service.autofill.SaveInfo;
import android.service.autofill.UserData;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.LocalLog;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillManager.SmartSuggestionMode;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.server.LocalServices;
import com.android.server.autofill.AutofillManagerService.AutofillCompatState;
import com.android.server.autofill.AutofillManagerService.DisabledInfoCache;
import com.android.server.autofill.RemoteAugmentedAutofillService.RemoteAugmentedAutofillServiceCallbacks;
import com.android.server.autofill.ui.AutoFillUI;
import com.android.server.contentcapture.ContentCaptureManagerInternal;
import com.android.server.infra.AbstractPerUserSystemService;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
/**
 * Bridge between the {@code system_server}'s {@link AutofillManagerService} and the
 * app's {@link IAutoFillService} implementation.
 *
 */
final class AutofillManagerServiceImpl
        extends AbstractPerUserSystemService<AutofillManagerServiceImpl, AutofillManagerService> {

    private static final String TAG = "AutofillManagerServiceImpl";
    private static final int MAX_SESSION_ID_CREATE_TRIES = 2048;

    /** Minimum interval to prune abandoned sessions */
    private static final int MAX_ABANDONED_SESSION_MILLIS = 30_000;

    private final AutoFillUI mUi;
    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    @GuardedBy("mLock")
    private RemoteCallbackList<IAutoFillManagerClient> mClients;

    @GuardedBy("mLock")
    private AutofillServiceInfo mInfo;

    private static final Random sRandom = new Random();

    private final LocalLog mUiLatencyHistory;
    private final LocalLog mWtfHistory;
    private final FieldClassificationStrategy mFieldClassificationStrategy;

    @GuardedBy("mLock")
    @Nullable
    private RemoteInlineSuggestionRenderService mRemoteInlineSuggestionRenderService;

    /**
     * Data used for field classification.
     */
    @GuardedBy("mLock")
    private UserData mUserData;

    private final Handler mHandler = new Handler(Looper.getMainLooper(), null, true);

    /**
     * Cache of pending {@link Session}s, keyed by sessionId.
     *
     * <p>They're kept until the {@link AutofillService} finished handling a request, an error
     * occurs, or the session is abandoned.
     */
    @GuardedBy("mLock")
    private final SparseArray<Session> mSessions = new SparseArray<>();

    /** The last selection */
    @GuardedBy("mLock")
    private FillEventHistory mEventHistory;

    /**
     * The last inline augmented autofill selection. Note that we don't log the selection from the
     * dropdown UI since the service owns the UI in that case.
     */
    @GuardedBy("mLock")
    private FillEventHistory mAugmentedAutofillEventHistory;

    /** Shared instance, doesn't need to be logged */
    private final AutofillCompatState mAutofillCompatState;

    /** When was {@link PruneTask} last executed? */
    private long mLastPrune = 0;

    /**
     * Reference to the {@link RemoteAugmentedAutofillService}, is set on demand.
     */
    @GuardedBy("mLock")
    @Nullable
    private RemoteAugmentedAutofillService mRemoteAugmentedAutofillService;

    @GuardedBy("mLock")
    @Nullable
    private ServiceInfo mRemoteAugmentedAutofillServiceInfo;

    private final InputMethodManagerInternal mInputMethodManagerInternal;

    private final ContentCaptureManagerInternal mContentCaptureManagerInternal;

    private final DisabledInfoCache mDisabledInfoCache;

    AutofillManagerServiceImpl(AutofillManagerService master, Object lock,
            LocalLog uiLatencyHistory, LocalLog wtfHistory, int userId, AutoFillUI ui,
            AutofillCompatState autofillCompatState,
            boolean disabled, DisabledInfoCache disableCache) {
        super(master, lock, userId);

        mUiLatencyHistory = uiLatencyHistory;
        mWtfHistory = wtfHistory;
        mUi = ui;
        mFieldClassificationStrategy = new FieldClassificationStrategy(getContext(), userId);
        mAutofillCompatState = autofillCompatState;
        mInputMethodManagerInternal = LocalServices.getService(InputMethodManagerInternal.class);
        mContentCaptureManagerInternal = LocalServices.getService(
                ContentCaptureManagerInternal.class);
        mDisabledInfoCache = disableCache;
        updateLocked(disabled);
    }

    boolean sendActivityAssistDataToContentCapture(@NonNull IBinder activityToken,
            @NonNull Bundle data) {
        if (mContentCaptureManagerInternal != null) {
            mContentCaptureManagerInternal.sendActivityAssistData(getUserId(), activityToken, data);
            return true;
        }

        return false;
    }

    @GuardedBy("mLock")
    void onBackKeyPressed() {
        final RemoteAugmentedAutofillService remoteService =
                getRemoteAugmentedAutofillServiceLocked();
        if (remoteService != null) {
            remoteService.onDestroyAutofillWindowsRequest();
        }
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected boolean updateLocked(boolean disabled) {
        forceRemoveAllSessionsLocked();
        final boolean enabledChanged = super.updateLocked(disabled);
        if (enabledChanged) {
            if (!isEnabledLocked()) {
                final int sessionCount = mSessions.size();
                for (int i = sessionCount - 1; i >= 0; i--) {
                    final Session session = mSessions.valueAt(i);
                    session.removeFromServiceLocked();
                }
            }
            sendStateToClients(/* resetClient= */ false);
        }
        updateRemoteAugmentedAutofillService();
        updateRemoteInlineSuggestionRenderServiceLocked();

        return enabledChanged;
    }

    @Override // from PerUserSystemService
    protected ServiceInfo newServiceInfoLocked(@NonNull ComponentName serviceComponent)
            throws NameNotFoundException {
        mInfo = new AutofillServiceInfo(getContext(), serviceComponent, mUserId);
        return mInfo.getServiceInfo();
    }

    @Nullable
    String[] getUrlBarResourceIdsForCompatMode(@NonNull String packageName) {
        return mAutofillCompatState.getUrlBarResourceIds(packageName, mUserId);
    }

    /**
     * Adds the client and return the proper flags
     *
     * @return {@code 0} if disabled, {@code FLAG_ADD_CLIENT_ENABLED} if enabled (it might be
     * OR'ed with {@code FLAG_AUGMENTED_AUTOFILL_REQUEST}).
     */
    @GuardedBy("mLock")
    int addClientLocked(IAutoFillManagerClient client, ComponentName componentName) {
        if (mClients == null) {
            mClients = new RemoteCallbackList<>();
        }
        mClients.register(client);

        if (isEnabledLocked()) return FLAG_ADD_CLIENT_ENABLED;

        // Check if it's enabled for augmented autofill
        if (componentName != null && isAugmentedAutofillServiceAvailableLocked()
                && isWhitelistedForAugmentedAutofillLocked(componentName)) {
            return FLAG_ADD_CLIENT_ENABLED_FOR_AUGMENTED_AUTOFILL_ONLY;
        }

        // No flags / disabled
        return 0;
    }

    @GuardedBy("mLock")
    void removeClientLocked(IAutoFillManagerClient client) {
        if (mClients != null) {
            mClients.unregister(client);
        }
    }

    @GuardedBy("mLock")
    void setAuthenticationResultLocked(Bundle data, int sessionId, int authenticationId, int uid) {
        if (!isEnabledLocked()) {
            return;
        }
        final Session session = mSessions.get(sessionId);
        if (session != null && uid == session.uid) {
            session.setAuthenticationResultLocked(data, authenticationId);
        }
    }

    void setHasCallback(int sessionId, int uid, boolean hasIt) {
        if (!isEnabledLocked()) {
            return;
        }
        final Session session = mSessions.get(sessionId);
        if (session != null && uid == session.uid) {
            synchronized (mLock) {
                session.setHasCallbackLocked(hasIt);
            }
        }
    }

    /**
     * Starts a new session.
     *
     * @return {@code long} whose right-most 32 bits represent the session id (which is always
     * non-negative), and the left-most contains extra flags (currently either {@code 0} or
     * {@link AutofillManager#RECEIVER_FLAG_SESSION_FOR_AUGMENTED_AUTOFILL_ONLY}).
     */
    @GuardedBy("mLock")
    long startSessionLocked(@NonNull IBinder activityToken, int taskId, int clientUid,
            @NonNull IBinder clientCallback, @NonNull AutofillId autofillId,
            @NonNull Rect virtualBounds, @Nullable AutofillValue value, boolean hasCallback,
            @NonNull ComponentName clientActivity, boolean compatMode,
            boolean bindInstantServiceAllowed, int flags) {
        // FLAG_AUGMENTED_AUTOFILL_REQUEST is set in the flags when standard autofill is disabled
        // but the package is allowlisted for augmented autofill
        boolean forAugmentedAutofillOnly = (flags
                & FLAG_ADD_CLIENT_ENABLED_FOR_AUGMENTED_AUTOFILL_ONLY) != 0;
        if (!isEnabledLocked() && !forAugmentedAutofillOnly) {
            return 0;
        }

        if (!forAugmentedAutofillOnly && isAutofillDisabledLocked(clientActivity)) {
            // Standard autofill is enabled, but service disabled autofill for this activity; that
            // means no session, unless the activity is allowlisted for augmented autofill
            if (isWhitelistedForAugmentedAutofillLocked(clientActivity)) {
                if (sDebug) {
                    Slog.d(TAG, "startSession(" + clientActivity + "): disabled by service but "
                            + "whitelisted for augmented autofill");
                }
                forAugmentedAutofillOnly = true;

            } else {
                if (sDebug) {
                    Slog.d(TAG, "startSession(" + clientActivity + "): ignored because "
                            + "disabled by service and not whitelisted for augmented autofill");
                }
                final IAutoFillManagerClient client = IAutoFillManagerClient.Stub
                        .asInterface(clientCallback);
                try {
                    client.setSessionFinished(AutofillManager.STATE_DISABLED_BY_SERVICE,
                            /* autofillableIds= */ null);
                } catch (RemoteException e) {
                    Slog.w(TAG,
                            "Could not notify " + clientActivity + " that it's disabled: " + e);
                }

                return NO_SESSION;
            }
        }

        if (sVerbose) {
            Slog.v(TAG, "startSession(): token=" + activityToken + ", flags=" + flags
                    + ", forAugmentedAutofillOnly=" + forAugmentedAutofillOnly);
        }

        // Occasionally clean up abandoned sessions
        pruneAbandonedSessionsLocked();

        final Session newSession = createSessionByTokenLocked(activityToken, taskId, clientUid,
                clientCallback, hasCallback, clientActivity, compatMode,
                bindInstantServiceAllowed, forAugmentedAutofillOnly, flags);
        if (newSession == null) {
            return NO_SESSION;
        }

        // Service can be null when it's only for augmented autofill
        String servicePackageName = mInfo == null ? null : mInfo.getServiceInfo().packageName;
        final String historyItem =
                "id=" + newSession.id + " uid=" + clientUid + " a=" + clientActivity.toShortString()
                + " s=" + servicePackageName
                + " u=" + mUserId + " i=" + autofillId + " b=" + virtualBounds
                + " hc=" + hasCallback + " f=" + flags + " aa=" + forAugmentedAutofillOnly;
        mMaster.logRequestLocked(historyItem);

        newSession.updateLocked(autofillId, virtualBounds, value, ACTION_START_SESSION, flags);

        if (forAugmentedAutofillOnly) {
            // Must embed the flag in the response, at the high-end side of the long.
            // (session is always positive, so we don't have to worry about the signal bit)
            final long extraFlags =
                    ((long) RECEIVER_FLAG_SESSION_FOR_AUGMENTED_AUTOFILL_ONLY) << 32;
            final long result = extraFlags | newSession.id;
            return result;
        } else {
            return newSession.id;
        }
    }

    /**
     * Remove abandoned sessions if needed.
     */
    @GuardedBy("mLock")
    private void pruneAbandonedSessionsLocked() {
        long now = System.currentTimeMillis();
        if (mLastPrune < now - MAX_ABANDONED_SESSION_MILLIS) {
            mLastPrune = now;

            if (mSessions.size() > 0) {
                (new PruneTask()).execute();
            }
        }
    }

    @GuardedBy("mLock")
    void setAutofillFailureLocked(int sessionId, int uid, @NonNull List<AutofillId> ids) {
        if (!isEnabledLocked()) {
            return;
        }
        final Session session = mSessions.get(sessionId);
        if (session == null || uid != session.uid) {
            Slog.v(TAG, "setAutofillFailure(): no session for " + sessionId + "(" + uid + ")");
            return;
        }
        session.setAutofillFailureLocked(ids);
    }

    @GuardedBy("mLock")
    void finishSessionLocked(int sessionId, int uid) {
        if (!isEnabledLocked()) {
            return;
        }

        final Session session = mSessions.get(sessionId);
        if (session == null || uid != session.uid) {
            if (sVerbose) {
                Slog.v(TAG, "finishSessionLocked(): no session for " + sessionId + "(" + uid + ")");
            }
            return;
        }

        final Session.SaveResult saveResult = session.showSaveLocked();

        session.logContextCommitted(saveResult.getNoSaveReason());

        if (saveResult.isLogSaveShown()) {
            session.logSaveUiShown();
        }

        final boolean finished = saveResult.isRemoveSession();
        if (sVerbose) Slog.v(TAG, "finishSessionLocked(): session finished on save? " + finished);

        if (finished) {
            session.removeFromServiceLocked();
        }
    }

    @GuardedBy("mLock")
    void cancelSessionLocked(int sessionId, int uid) {
        if (!isEnabledLocked()) {
            return;
        }

        final Session session = mSessions.get(sessionId);
        if (session == null || uid != session.uid) {
            Slog.w(TAG, "cancelSessionLocked(): no session for " + sessionId + "(" + uid + ")");
            return;
        }
        session.removeFromServiceLocked();
    }

    @GuardedBy("mLock")
    void disableOwnedAutofillServicesLocked(int uid) {
        Slog.i(TAG, "disableOwnedServices(" + uid + "): " + mInfo);
        if (mInfo == null) return;

        final ServiceInfo serviceInfo = mInfo.getServiceInfo();
        if (serviceInfo.applicationInfo.uid != uid) {
            Slog.w(TAG, "disableOwnedServices(): ignored when called by UID " + uid
                    + " instead of " + serviceInfo.applicationInfo.uid
                    + " for service " + mInfo);
            return;
        }


        final long identity = Binder.clearCallingIdentity();
        try {
            final String autoFillService = getComponentNameLocked();
            final ComponentName componentName = serviceInfo.getComponentName();
            if (componentName.equals(ComponentName.unflattenFromString(autoFillService))) {
                mMetricsLogger.action(MetricsEvent.AUTOFILL_SERVICE_DISABLED_SELF,
                        componentName.getPackageName());
                Settings.Secure.putStringForUser(getContext().getContentResolver(),
                        Settings.Secure.AUTOFILL_SERVICE, null, mUserId);
                forceRemoveAllSessionsLocked();
            } else {
                Slog.w(TAG, "disableOwnedServices(): ignored because current service ("
                        + serviceInfo + ") does not match Settings (" + autoFillService + ")");
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @GuardedBy("mLock")
    private Session createSessionByTokenLocked(@NonNull IBinder clientActivityToken, int taskId,
            int clientUid, @NonNull IBinder clientCallback, boolean hasCallback,
            @NonNull ComponentName clientActivity, boolean compatMode,
            boolean bindInstantServiceAllowed, boolean forAugmentedAutofillOnly, int flags) {
        // use random ids so that one app cannot know that another app creates sessions
        int sessionId;
        int tries = 0;
        do {
            tries++;
            if (tries > MAX_SESSION_ID_CREATE_TRIES) {
                Slog.w(TAG, "Cannot create session in " + MAX_SESSION_ID_CREATE_TRIES + " tries");
                return null;
            }

            sessionId = Math.abs(sRandom.nextInt());
        } while (sessionId == 0 || sessionId == NO_SESSION
                || mSessions.indexOfKey(sessionId) >= 0);

        assertCallerLocked(clientActivity, compatMode);

        // It's null when the session is just for augmented autofill
        final ComponentName serviceComponentName = mInfo == null ? null
                : mInfo.getServiceInfo().getComponentName();
        final Session newSession = new Session(this, mUi, getContext(), mHandler, mUserId, mLock,
                sessionId, taskId, clientUid, clientActivityToken, clientCallback, hasCallback,
                mUiLatencyHistory, mWtfHistory, serviceComponentName,
                clientActivity, compatMode, bindInstantServiceAllowed, forAugmentedAutofillOnly,
                flags, mInputMethodManagerInternal);
        mSessions.put(newSession.id, newSession);

        return newSession;
    }

    /**
     * Asserts the component is owned by the caller.
     */
    private void assertCallerLocked(@NonNull ComponentName componentName, boolean compatMode) {
        final String packageName = componentName.getPackageName();
        final PackageManager pm = getContext().getPackageManager();
        final int callingUid = Binder.getCallingUid();
        final int packageUid;
        try {
            packageUid = pm.getPackageUidAsUser(packageName, UserHandle.getCallingUserId());
        } catch (NameNotFoundException e) {
            throw new SecurityException("Could not verify UID for " + componentName);
        }
        if (callingUid != packageUid && !LocalServices.getService(ActivityManagerInternal.class)
                .hasRunningActivity(callingUid, packageName)) {
            final String[] packages = pm.getPackagesForUid(callingUid);
            final String callingPackage = packages != null ? packages[0] : "uid-" + callingUid;
            Slog.w(TAG, "App (package=" + callingPackage + ", UID=" + callingUid
                    + ") passed component (" + componentName + ") owned by UID " + packageUid);

            // NOTE: not using Helper.newLogMaker() because we don't have the session id
            final LogMaker log = new LogMaker(MetricsEvent.AUTOFILL_FORGED_COMPONENT_ATTEMPT)
                    .setPackageName(callingPackage)
                    .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE, getServicePackageName())
                    .addTaggedData(MetricsEvent.FIELD_AUTOFILL_FORGED_COMPONENT_NAME,
                            componentName == null ? "null" : componentName.flattenToShortString());
            if (compatMode) {
                log.addTaggedData(MetricsEvent.FIELD_AUTOFILL_COMPAT_MODE, 1);
            }
            mMetricsLogger.write(log);

            throw new SecurityException("Invalid component: " + componentName);
        }
    }

    /**
     * Restores a session after an activity was temporarily destroyed.
     *
     * @param sessionId The id of the session to restore
     * @param uid UID of the process that tries to restore the session
     * @param activityToken The new instance of the activity
     * @param appCallback The callbacks to the activity
     */
    boolean restoreSession(int sessionId, int uid, @NonNull IBinder activityToken,
            @NonNull IBinder appCallback) {
        final Session session = mSessions.get(sessionId);

        if (session == null || uid != session.uid) {
            return false;
        } else {
            session.switchActivity(activityToken, appCallback);
            return true;
        }
    }

    /**
     * Updates a session and returns whether it should be restarted.
     */
    @GuardedBy("mLock")
    boolean updateSessionLocked(int sessionId, int uid, AutofillId autofillId, Rect virtualBounds,
            AutofillValue value, int action, int flags) {
        final Session session = mSessions.get(sessionId);
        if (session == null || session.uid != uid) {
            if ((flags & FLAG_MANUAL_REQUEST) != 0) {
                if (sDebug) {
                    Slog.d(TAG, "restarting session " + sessionId + " due to manual request on "
                            + autofillId);
                }
                return true;
            }
            if (sVerbose) {
                Slog.v(TAG, "updateSessionLocked(): session gone for " + sessionId
                        + "(" + uid + ")");
            }
            return false;
        }

        session.updateLocked(autofillId, virtualBounds, value, action, flags);
        return false;
    }

    @GuardedBy("mLock")
    void removeSessionLocked(int sessionId) {
        mSessions.remove(sessionId);
    }

    /**
     * Ges the previous sessions asked to be kept alive in a given activity task.
     *
     * @param session session calling this method (so it's excluded from the result).
     */
    @Nullable
    @GuardedBy("mLock")
    ArrayList<Session> getPreviousSessionsLocked(@NonNull Session session) {
        final int size = mSessions.size();
        ArrayList<Session> previousSessions = null;
        for (int i = 0; i < size; i++) {
            final Session previousSession = mSessions.valueAt(i);
            if (previousSession.taskId == session.taskId && previousSession.id != session.id
                    && (previousSession.getSaveInfoFlagsLocked() & SaveInfo.FLAG_DELAY_SAVE) != 0) {
                if (previousSessions == null) {
                    previousSessions = new ArrayList<>(size);
                }
                previousSessions.add(previousSession);
            }
        }
        // TODO(b/113281366): remove returned sessions / add CTS test
        return previousSessions;
    }

    void handleSessionSave(Session session) {
        synchronized (mLock) {
            if (mSessions.get(session.id) == null) {
                Slog.w(TAG, "handleSessionSave(): already gone: " + session.id);

                return;
            }
            session.callSaveLocked();
        }
    }

    void onPendingSaveUi(int operation, @NonNull IBinder token) {
        if (sVerbose) Slog.v(TAG, "onPendingSaveUi(" + operation + "): " + token);
        synchronized (mLock) {
            final int sessionCount = mSessions.size();
            for (int i = sessionCount - 1; i >= 0; i--) {
                final Session session = mSessions.valueAt(i);
                if (session.isSaveUiPendingForTokenLocked(token)) {
                    session.onPendingSaveUi(operation, token);
                    return;
                }
            }
        }
        if (sDebug) {
            Slog.d(TAG, "No pending Save UI for token " + token + " and operation "
                    + DebugUtils.flagsToString(AutofillManager.class, "PENDING_UI_OPERATION_",
                            operation));
        }
    }

    @GuardedBy("mLock")
    @Override // from PerUserSystemService
    protected void handlePackageUpdateLocked(@NonNull String packageName) {
        final ServiceInfo serviceInfo = mFieldClassificationStrategy.getServiceInfo();
        if (serviceInfo != null && serviceInfo.packageName.equals(packageName)) {
            resetExtServiceLocked();
        }
    }

    @GuardedBy("mLock")
    void resetExtServiceLocked() {
        if (sVerbose) Slog.v(TAG, "reset autofill service.");
        mFieldClassificationStrategy.reset();
    }

    @GuardedBy("mLock")
    void destroyLocked() {
        if (sVerbose) Slog.v(TAG, "destroyLocked()");

        resetExtServiceLocked();

        final int numSessions = mSessions.size();
        final ArraySet<RemoteFillService> remoteFillServices = new ArraySet<>(numSessions);
        for (int i = 0; i < numSessions; i++) {
            final RemoteFillService remoteFillService = mSessions.valueAt(i).destroyLocked();
            if (remoteFillService != null) {
                remoteFillServices.add(remoteFillService);
            }
        }
        mSessions.clear();
        for (int i = 0; i < remoteFillServices.size(); i++) {
            remoteFillServices.valueAt(i).destroy();
        }

        sendStateToClients(/* resetclient=*/ true);
        if (mClients != null) {
            mClients.kill();
            mClients = null;
        }
    }

    /**
     * Initializes the last fill selection after an autofill service returned a new
     * {@link FillResponse}.
     */
    void setLastResponse(int sessionId, @NonNull FillResponse response) {
        synchronized (mLock) {
            mEventHistory = new FillEventHistory(sessionId, response.getClientState());
        }
    }

    void setLastAugmentedAutofillResponse(int sessionId) {
        synchronized (mLock) {
            mAugmentedAutofillEventHistory = new FillEventHistory(sessionId, /* clientState= */
                    null);
        }
    }

    /**
     * Resets the last fill selection.
     */
    void resetLastResponse() {
        synchronized (mLock) {
            mEventHistory = null;
        }
    }

    void resetLastAugmentedAutofillResponse() {
        synchronized (mLock) {
            mAugmentedAutofillEventHistory = null;
        }
    }

    @GuardedBy("mLock")
    private boolean isValidEventLocked(String method, int sessionId) {
        if (mEventHistory == null) {
            Slog.w(TAG, method + ": not logging event because history is null");
            return false;
        }
        if (sessionId != mEventHistory.getSessionId()) {
            if (sDebug) {
                Slog.d(TAG, method + ": not logging event for session " + sessionId
                        + " because tracked session is " + mEventHistory.getSessionId());
            }
            return false;
        }
        return true;
    }

    /**
     * Updates the last fill selection when an authentication was selected.
     */
    void setAuthenticationSelected(int sessionId, @Nullable Bundle clientState) {
        synchronized (mLock) {
            if (isValidEventLocked("setAuthenticationSelected()", sessionId)) {
                mEventHistory.addEvent(
                        new Event(Event.TYPE_AUTHENTICATION_SELECTED, null, clientState, null, null,
                                null, null, null, null, null, null));
            }
        }
    }

    /**
     * Updates the last fill selection when an dataset authentication was selected.
     */
    void logDatasetAuthenticationSelected(@Nullable String selectedDataset, int sessionId,
            @Nullable Bundle clientState) {
        synchronized (mLock) {
            if (isValidEventLocked("logDatasetAuthenticationSelected()", sessionId)) {
                mEventHistory.addEvent(
                        new Event(Event.TYPE_DATASET_AUTHENTICATION_SELECTED, selectedDataset,
                                clientState, null, null, null, null, null, null, null, null));
            }
        }
    }

    /**
     * Updates the last fill selection when an save Ui is shown.
     */
    void logSaveShown(int sessionId, @Nullable Bundle clientState) {
        synchronized (mLock) {
            if (isValidEventLocked("logSaveShown()", sessionId)) {
                mEventHistory.addEvent(new Event(Event.TYPE_SAVE_SHOWN, null, clientState, null,
                        null, null, null, null, null, null, null));
            }
        }
    }

    /**
     * Updates the last fill response when a dataset was selected.
     */
    void logDatasetSelected(@Nullable String selectedDataset, int sessionId,
            @Nullable Bundle clientState) {
        synchronized (mLock) {
            if (isValidEventLocked("logDatasetSelected()", sessionId)) {
                mEventHistory.addEvent(
                        new Event(Event.TYPE_DATASET_SELECTED, selectedDataset, clientState, null,
                                null, null, null, null, null, null, null));
            }
        }
    }

    /**
     * Updates the last fill response when a dataset is shown.
     */
    void logDatasetShown(int sessionId, @Nullable Bundle clientState) {
        synchronized (mLock) {
            if (isValidEventLocked("logDatasetShown", sessionId)) {
                mEventHistory.addEvent(
                        new Event(Event.TYPE_DATASETS_SHOWN, null, clientState, null, null, null,
                                null, null, null, null, null));
            }
        }
    }

    void logAugmentedAutofillAuthenticationSelected(int sessionId, @Nullable String selectedDataset,
            @Nullable Bundle clientState) {
        synchronized (mLock) {
            if (mAugmentedAutofillEventHistory == null
                    || mAugmentedAutofillEventHistory.getSessionId() != sessionId) {
                return;
            }
            mAugmentedAutofillEventHistory.addEvent(
                    new Event(Event.TYPE_DATASET_AUTHENTICATION_SELECTED, selectedDataset,
                            clientState, null, null, null, null, null, null, null, null));
        }
    }

    void logAugmentedAutofillSelected(int sessionId, @Nullable String suggestionId,
            @Nullable Bundle clientState) {
        synchronized (mLock) {
            if (mAugmentedAutofillEventHistory == null
                    || mAugmentedAutofillEventHistory.getSessionId() != sessionId) {
                return;
            }
            mAugmentedAutofillEventHistory.addEvent(
                    new Event(Event.TYPE_DATASET_SELECTED, suggestionId, clientState, null, null,
                            null, null, null, null, null, null));
        }
    }

    void logAugmentedAutofillShown(int sessionId, @Nullable Bundle clientState) {
        synchronized (mLock) {
            if (mAugmentedAutofillEventHistory == null
                    || mAugmentedAutofillEventHistory.getSessionId() != sessionId) {
                return;
            }
            mAugmentedAutofillEventHistory.addEvent(
                    new Event(Event.TYPE_DATASETS_SHOWN, null, clientState, null, null, null,
                            null, null, null, null, null));

        }
    }

    /**
     * Updates the last fill response when an autofill context is committed.
     */
    @GuardedBy("mLock")
    void logContextCommittedLocked(int sessionId, @Nullable Bundle clientState,
            @Nullable ArrayList<String> selectedDatasets,
            @Nullable ArraySet<String> ignoredDatasets,
            @Nullable ArrayList<AutofillId> changedFieldIds,
            @Nullable ArrayList<String> changedDatasetIds,
            @Nullable ArrayList<AutofillId> manuallyFilledFieldIds,
            @Nullable ArrayList<ArrayList<String>> manuallyFilledDatasetIds,
            @NonNull ComponentName appComponentName, boolean compatMode) {
        logContextCommittedLocked(sessionId, clientState, selectedDatasets, ignoredDatasets,
                changedFieldIds, changedDatasetIds, manuallyFilledFieldIds,
                manuallyFilledDatasetIds, /* detectedFieldIdsList= */ null,
                /* detectedFieldClassificationsList= */ null, appComponentName, compatMode,
                Event.NO_SAVE_REASON_NONE);
    }

    @GuardedBy("mLock")
    void logContextCommittedLocked(int sessionId, @Nullable Bundle clientState,
            @Nullable ArrayList<String> selectedDatasets,
            @Nullable ArraySet<String> ignoredDatasets,
            @Nullable ArrayList<AutofillId> changedFieldIds,
            @Nullable ArrayList<String> changedDatasetIds,
            @Nullable ArrayList<AutofillId> manuallyFilledFieldIds,
            @Nullable ArrayList<ArrayList<String>> manuallyFilledDatasetIds,
            @Nullable ArrayList<AutofillId> detectedFieldIdsList,
            @Nullable ArrayList<FieldClassification> detectedFieldClassificationsList,
            @NonNull ComponentName appComponentName, boolean compatMode,
            @NoSaveReason int saveDialogNotShowReason) {
        if (isValidEventLocked("logDatasetNotSelected()", sessionId)) {
            if (sVerbose) {
                Slog.v(TAG, "logContextCommitted() with FieldClassification: id=" + sessionId
                        + ", selectedDatasets=" + selectedDatasets
                        + ", ignoredDatasetIds=" + ignoredDatasets
                        + ", changedAutofillIds=" + changedFieldIds
                        + ", changedDatasetIds=" + changedDatasetIds
                        + ", manuallyFilledFieldIds=" + manuallyFilledFieldIds
                        + ", detectedFieldIds=" + detectedFieldIdsList
                        + ", detectedFieldClassifications=" + detectedFieldClassificationsList
                        + ", appComponentName=" + appComponentName.toShortString()
                        + ", compatMode=" + compatMode
                        + ", saveDialogNotShowReason=" + saveDialogNotShowReason);
            }
            AutofillId[] detectedFieldsIds = null;
            FieldClassification[] detectedFieldClassifications = null;
            if (detectedFieldIdsList != null) {
                detectedFieldsIds = new AutofillId[detectedFieldIdsList.size()];
                detectedFieldIdsList.toArray(detectedFieldsIds);
                detectedFieldClassifications =
                        new FieldClassification[detectedFieldClassificationsList.size()];
                detectedFieldClassificationsList.toArray(detectedFieldClassifications);

                final int numberFields = detectedFieldsIds.length;
                int totalSize = 0;
                float totalScore = 0;
                for (int i = 0; i < numberFields; i++) {
                    final FieldClassification fc = detectedFieldClassifications[i];
                    final List<Match> matches = fc.getMatches();
                    final int size = matches.size();
                    totalSize += size;
                    for (int j = 0; j < size; j++) {
                        totalScore += matches.get(j).getScore();
                    }
                }

                final int averageScore = (int) ((totalScore * 100) / totalSize);
                mMetricsLogger.write(Helper
                        .newLogMaker(MetricsEvent.AUTOFILL_FIELD_CLASSIFICATION_MATCHES,
                                appComponentName, getServicePackageName(), sessionId, compatMode)
                        .setCounterValue(numberFields)
                        .addTaggedData(MetricsEvent.FIELD_AUTOFILL_MATCH_SCORE,
                                averageScore));
            }
            mEventHistory.addEvent(new Event(Event.TYPE_CONTEXT_COMMITTED, null,
                    clientState, selectedDatasets, ignoredDatasets,
                    changedFieldIds, changedDatasetIds,
                    manuallyFilledFieldIds, manuallyFilledDatasetIds,
                    detectedFieldsIds, detectedFieldClassifications, saveDialogNotShowReason));
        }
    }

    /**
     * Gets the fill event history.
     *
     * @param callingUid The calling uid
     * @return The history for the autofill or the augmented autofill events depending on the {@code
     * callingUid}, or {@code null} if there is none.
     */
    FillEventHistory getFillEventHistory(int callingUid) {
        synchronized (mLock) {
            if (mEventHistory != null
                    && isCalledByServiceLocked("getFillEventHistory", callingUid)) {
                return mEventHistory;
            }
            if (mAugmentedAutofillEventHistory != null && isCalledByAugmentedAutofillServiceLocked(
                    "getFillEventHistory", callingUid)) {
                return mAugmentedAutofillEventHistory;
            }
        }
        return null;
    }

    // Called by Session - does not need to check uid
    UserData getUserData() {
        synchronized (mLock) {
            return mUserData;
        }
    }

    // Called by AutofillManager
    UserData getUserData(int callingUid) {
        synchronized (mLock) {
            if (isCalledByServiceLocked("getUserData", callingUid)) {
                return mUserData;
            }
        }
        return null;
    }

    // Called by AutofillManager
    void setUserData(int callingUid, UserData userData) {
        synchronized (mLock) {
            if (!isCalledByServiceLocked("setUserData", callingUid)) {
                return;
            }
            mUserData = userData;
            // Log it
            final int numberFields = mUserData == null ? 0: mUserData.getCategoryIds().length;
            // NOTE: contrary to most metrics, the service name is logged as the main package name
            // here, not as MetricsEvent.FIELD_AUTOFILL_SERVICE
            mMetricsLogger.write(new LogMaker(MetricsEvent.AUTOFILL_USERDATA_UPDATED)
                    .setPackageName(getServicePackageName())
                    .addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_VALUES, numberFields));
        }
    }

    @GuardedBy("mLock")
    private boolean isCalledByServiceLocked(@NonNull String methodName, int callingUid) {
        final int serviceUid = getServiceUidLocked();
        if (serviceUid != callingUid) {
            Slog.w(TAG, methodName + "() called by UID " + callingUid
                    + ", but service UID is " + serviceUid);
            return false;
        }
        return true;
    }

    @GuardedBy("mLock")
    @SmartSuggestionMode int getSupportedSmartSuggestionModesLocked() {
        return mMaster.getSupportedSmartSuggestionModesLocked();
    }

    @Override
    @GuardedBy("mLock")
    protected void dumpLocked(String prefix, PrintWriter pw) {
        super.dumpLocked(prefix, pw);

        final String prefix2 = prefix + "  ";

        pw.print(prefix); pw.print("UID: "); pw.println(getServiceUidLocked());
        pw.print(prefix); pw.print("Autofill Service Info: ");
        if (mInfo == null) {
            pw.println("N/A");
        } else {
            pw.println();
            mInfo.dump(prefix2, pw);
        }
        pw.print(prefix); pw.print("Default component: "); pw.println(getContext()
                .getString(R.string.config_defaultAutofillService));

        pw.print(prefix); pw.println("mAugmentedAutofillNamer: ");
        pw.print(prefix2); mMaster.mAugmentedAutofillResolver.dumpShort(pw, mUserId); pw.println();

        if (mRemoteAugmentedAutofillService != null) {
            pw.print(prefix); pw.println("RemoteAugmentedAutofillService: ");
            mRemoteAugmentedAutofillService.dump(prefix2, pw);
        }
        if (mRemoteAugmentedAutofillServiceInfo != null) {
            pw.print(prefix); pw.print("RemoteAugmentedAutofillServiceInfo: ");
            pw.println(mRemoteAugmentedAutofillServiceInfo);
        }

        pw.print(prefix); pw.print("Field classification enabled: ");
            pw.println(isFieldClassificationEnabledLocked());
        pw.print(prefix); pw.print("Compat pkgs: ");
        final ArrayMap<String, Long> compatPkgs = getCompatibilityPackagesLocked();
        if (compatPkgs == null) {
            pw.println("N/A");
        } else {
            pw.println(compatPkgs);
        }
        pw.print(prefix); pw.print("Inline Suggestions Enabled: ");
        pw.println(isInlineSuggestionsEnabledLocked());
        pw.print(prefix); pw.print("Last prune: "); pw.println(mLastPrune);

        mDisabledInfoCache.dump(mUserId, prefix, pw);

        final int size = mSessions.size();
        if (size == 0) {
            pw.print(prefix); pw.println("No sessions");
        } else {
            pw.print(prefix); pw.print(size); pw.println(" sessions:");
            for (int i = 0; i < size; i++) {
                pw.print(prefix); pw.print("#"); pw.println(i + 1);
                mSessions.valueAt(i).dumpLocked(prefix2, pw);
            }
        }

        pw.print(prefix); pw.print("Clients: ");
        if (mClients == null) {
            pw.println("N/A");
        } else {
            pw.println();
            mClients.dump(pw, prefix2);
        }

        if (mEventHistory == null || mEventHistory.getEvents() == null
                || mEventHistory.getEvents().size() == 0) {
            pw.print(prefix); pw.println("No event on last fill response");
        } else {
            pw.print(prefix); pw.println("Events of last fill response:");
            pw.print(prefix);

            int numEvents = mEventHistory.getEvents().size();
            for (int i = 0; i < numEvents; i++) {
                final Event event = mEventHistory.getEvents().get(i);
                pw.println("  " + i + ": eventType=" + event.getType() + " datasetId="
                        + event.getDatasetId());
            }
        }

        pw.print(prefix); pw.print("User data: ");
        if (mUserData == null) {
            pw.println("N/A");
        } else {
            pw.println();
            mUserData.dump(prefix2, pw);
        }

        pw.print(prefix); pw.println("Field Classification strategy: ");
        mFieldClassificationStrategy.dump(prefix2, pw);
    }

    @GuardedBy("mLock")
    void forceRemoveAllSessionsLocked() {
        final int sessionCount = mSessions.size();
        if (sessionCount == 0) {
            mUi.destroyAll(null, null, false);
            return;
        }

        for (int i = sessionCount - 1; i >= 0; i--) {
            mSessions.valueAt(i).forceRemoveFromServiceLocked();
        }
    }

    @GuardedBy("mLock")
    void forceRemoveForAugmentedOnlySessionsLocked() {
        final int sessionCount = mSessions.size();
        for (int i = sessionCount - 1; i >= 0; i--) {
            mSessions.valueAt(i).forceRemoveFromServiceIfForAugmentedOnlyLocked();
        }
    }

    /**
     * This method is called exclusively in response to {@code Intent.ACTION_CLOSE_SYSTEM_DIALOGS}.
     * The method removes all sessions that are finished but showing SaveUI due to how SaveUI is
     * managed (see b/64940307). Otherwise it will remove any augmented autofill generated windows.
     */
    // TODO(b/64940307): remove this method if SaveUI is refactored to be attached on activities
    @GuardedBy("mLock")
    void forceRemoveFinishedSessionsLocked() {
        final int sessionCount = mSessions.size();
        for (int i = sessionCount - 1; i >= 0; i--) {
            final Session session = mSessions.valueAt(i);
            if (session.isSaveUiShowingLocked()) {
                if (sDebug) Slog.d(TAG, "destroyFinishedSessionsLocked(): " + session.id);
                session.forceRemoveFromServiceLocked();
            } else {
                session.destroyAugmentedAutofillWindowsLocked();
            }
        }
    }

    @GuardedBy("mLock")
    void listSessionsLocked(ArrayList<String> output) {
        final int numSessions = mSessions.size();
        if (numSessions <= 0) return;

        final String fmt = "%d:%s:%s";
        for (int i = 0; i < numSessions; i++) {
            final int id = mSessions.keyAt(i);
            final String service = mInfo == null
                    ? "no_svc"
                    : mInfo.getServiceInfo().getComponentName().flattenToShortString();
            final String augmentedService = mRemoteAugmentedAutofillServiceInfo == null
                    ? "no_aug"
                    : mRemoteAugmentedAutofillServiceInfo.getComponentName().flattenToShortString();
            output.add(String.format(fmt, id, service, augmentedService));
        }
    }

    @GuardedBy("mLock")
    @Nullable ArrayMap<String, Long> getCompatibilityPackagesLocked() {
        if (mInfo != null) {
            return mInfo.getCompatibilityPackages();
        }
        return null;
    }

    @GuardedBy("mLock")
    boolean isInlineSuggestionsEnabledLocked() {
        if (mInfo != null) {
            return mInfo.isInlineSuggestionsEnabled();
        }
        return false;
    }

    @GuardedBy("mLock")
    @Nullable RemoteAugmentedAutofillService getRemoteAugmentedAutofillServiceLocked() {
        if (mRemoteAugmentedAutofillService == null) {
            final String serviceName = mMaster.mAugmentedAutofillResolver.getServiceName(mUserId);
            if (serviceName == null) {
                if (mMaster.verbose) {
                    Slog.v(TAG, "getRemoteAugmentedAutofillServiceLocked(): not set");
                }
                return null;
            }
            final Pair<ServiceInfo, ComponentName> pair = RemoteAugmentedAutofillService
                    .getComponentName(serviceName, mUserId,
                            mMaster.mAugmentedAutofillResolver.isTemporary(mUserId));
            if (pair == null) return null;

            mRemoteAugmentedAutofillServiceInfo = pair.first;
            final ComponentName componentName = pair.second;
            if (sVerbose) {
                Slog.v(TAG, "getRemoteAugmentedAutofillServiceLocked(): " + componentName);
            }

            final RemoteAugmentedAutofillServiceCallbacks callbacks =
                    new RemoteAugmentedAutofillServiceCallbacks() {
                        @Override
                        public void resetLastResponse() {
                            AutofillManagerServiceImpl.this.resetLastAugmentedAutofillResponse();
                        }

                        @Override
                        public void setLastResponse(int sessionId) {
                            AutofillManagerServiceImpl.this.setLastAugmentedAutofillResponse(
                                    sessionId);
                        }

                        @Override
                        public void logAugmentedAutofillShown(int sessionId, Bundle clientState) {
                            AutofillManagerServiceImpl.this.logAugmentedAutofillShown(sessionId,
                                    clientState);
                        }

                        @Override
                        public void logAugmentedAutofillSelected(int sessionId,
                                String suggestionId, Bundle clientState) {
                            AutofillManagerServiceImpl.this.logAugmentedAutofillSelected(sessionId,
                                    suggestionId, clientState);
                        }

                        @Override
                        public void logAugmentedAutofillAuthenticationSelected(int sessionId,
                                String suggestionId, Bundle clientState) {
                            AutofillManagerServiceImpl.this
                                    .logAugmentedAutofillAuthenticationSelected(
                                            sessionId, suggestionId, clientState);
                        }

                        @Override
                        public void onServiceDied(@NonNull RemoteAugmentedAutofillService service) {
                            Slog.w(TAG, "remote augmented autofill service died");
                            final RemoteAugmentedAutofillService remoteService =
                                    mRemoteAugmentedAutofillService;
                            if (remoteService != null) {
                                remoteService.unbind();
                            }
                            mRemoteAugmentedAutofillService = null;
                        }
                    };
            final int serviceUid = mRemoteAugmentedAutofillServiceInfo.applicationInfo.uid;
            mRemoteAugmentedAutofillService = new RemoteAugmentedAutofillService(getContext(),
                    serviceUid, componentName,
                    mUserId, callbacks, mMaster.isInstantServiceAllowed(),
                    mMaster.verbose, mMaster.mAugmentedServiceIdleUnbindTimeoutMs,
                    mMaster.mAugmentedServiceRequestTimeoutMs);
        }

        return mRemoteAugmentedAutofillService;
    }

    @GuardedBy("mLock")
    @Nullable RemoteAugmentedAutofillService getRemoteAugmentedAutofillServiceIfCreatedLocked() {
        return mRemoteAugmentedAutofillService;
    }

    /**
     * Called when the {@link AutofillManagerService#mAugmentedAutofillResolver}
     * changed (among other places).
     */
    void updateRemoteAugmentedAutofillService() {
        synchronized (mLock) {
            if (mRemoteAugmentedAutofillService != null) {
                if (sVerbose) {
                    Slog.v(TAG, "updateRemoteAugmentedAutofillService(): "
                            + "destroying old remote service");
                }
                forceRemoveForAugmentedOnlySessionsLocked();
                mRemoteAugmentedAutofillService.unbind();
                mRemoteAugmentedAutofillService = null;
                mRemoteAugmentedAutofillServiceInfo = null;
                resetAugmentedAutofillWhitelistLocked();
            }

            final boolean available = isAugmentedAutofillServiceAvailableLocked();
            if (sVerbose) Slog.v(TAG, "updateRemoteAugmentedAutofillService(): " + available);

            if (available) {
                mRemoteAugmentedAutofillService = getRemoteAugmentedAutofillServiceLocked();
            }
        }
    }

    private boolean isAugmentedAutofillServiceAvailableLocked() {
        if (mMaster.verbose) {
            Slog.v(TAG, "isAugmentedAutofillService(): "
                    + "setupCompleted=" + isSetupCompletedLocked()
                    + ", disabled=" + isDisabledByUserRestrictionsLocked()
                    + ", augmentedService="
                    + mMaster.mAugmentedAutofillResolver.getServiceName(mUserId));
        }
        if (!isSetupCompletedLocked() || isDisabledByUserRestrictionsLocked()
                || mMaster.mAugmentedAutofillResolver.getServiceName(mUserId) == null) {
            return false;
        }
        return true;
    }

    boolean isAugmentedAutofillServiceForUserLocked(int callingUid) {
        return mRemoteAugmentedAutofillServiceInfo != null
                && mRemoteAugmentedAutofillServiceInfo.applicationInfo.uid == callingUid;
    }

    /**
     * Sets which packages and activities can trigger augmented autofill.
     *
     * @return whether caller UID is the augmented autofill service for the user
     */
    @GuardedBy("mLock")
    boolean setAugmentedAutofillWhitelistLocked(@Nullable List<String> packages,
            @Nullable List<ComponentName> activities, int callingUid) {

        if (!isCalledByAugmentedAutofillServiceLocked("setAugmentedAutofillWhitelistLocked",
                callingUid)) {
            return false;
        }
        if (mMaster.verbose) {
            Slog.v(TAG, "setAugmentedAutofillWhitelistLocked(packages=" + packages + ", activities="
                    + activities + ")");
        }
        whitelistForAugmentedAutofillPackages(packages, activities);
        final String serviceName;
        if (mRemoteAugmentedAutofillServiceInfo != null) {
            serviceName = mRemoteAugmentedAutofillServiceInfo.getComponentName()
                    .flattenToShortString();
        } else {
            Slog.e(TAG, "setAugmentedAutofillWhitelistLocked(): no service");
            serviceName = "N/A";
        }

        final LogMaker log = new LogMaker(MetricsEvent.AUTOFILL_AUGMENTED_WHITELIST_REQUEST)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE, serviceName);
        if (packages != null) {
            log.addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUMBER_PACKAGES, packages.size());
        }
        if (activities != null) {
            log.addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUMBER_ACTIVITIES, activities.size());
        }
        mMetricsLogger.write(log);

        return true;
    }

    @GuardedBy("mLock")
    private boolean isCalledByAugmentedAutofillServiceLocked(@NonNull String methodName,
            int callingUid) {
        // Lazy load service first
        final RemoteAugmentedAutofillService service = getRemoteAugmentedAutofillServiceLocked();
        if (service == null) {
            Slog.w(TAG, methodName + "() called by UID " + callingUid
                    + ", but there is no augmented autofill service defined for user "
                    + getUserId());
            return false;
        }

        if (getAugmentedAutofillServiceUidLocked() != callingUid) {
            Slog.w(TAG, methodName + "() called by UID " + callingUid
                    + ", but service UID is " + getAugmentedAutofillServiceUidLocked()
                    + " for user " + getUserId());
            return false;
        }
        return true;
    }

    @GuardedBy("mLock")
    private int getAugmentedAutofillServiceUidLocked() {
        if (mRemoteAugmentedAutofillServiceInfo == null) {
            if (mMaster.verbose) {
                Slog.v(TAG, "getAugmentedAutofillServiceUid(): "
                        + "no mRemoteAugmentedAutofillServiceInfo");
            }
            return Process.INVALID_UID;
        }
        return mRemoteAugmentedAutofillServiceInfo.applicationInfo.uid;
    }

    @GuardedBy("mLock")
    boolean isWhitelistedForAugmentedAutofillLocked(@NonNull ComponentName componentName) {
        return mMaster.mAugmentedAutofillState.isWhitelisted(mUserId, componentName);
    }

    /**
     * @throws IllegalArgumentException if packages or components are empty.
     */
    private void whitelistForAugmentedAutofillPackages(@Nullable List<String> packages,
            @Nullable List<ComponentName> components) {
        // TODO(b/123100824): add CTS test for when it's null
        synchronized (mLock) {
            if (mMaster.verbose) {
                Slog.v(TAG, "whitelisting packages: " + packages + "and activities: " + components);
            }
            mMaster.mAugmentedAutofillState.setWhitelist(mUserId, packages, components);
        }
    }

    /**
     * Resets the augmented autofill allowlist.
     */
    @GuardedBy("mLock")
    void resetAugmentedAutofillWhitelistLocked() {
        if (mMaster.verbose) {
            Slog.v(TAG, "resetting augmented autofill whitelist");
        }
        mMaster.mAugmentedAutofillState.resetWhitelist(mUserId);
    }

    private void sendStateToClients(boolean resetClient) {
        final RemoteCallbackList<IAutoFillManagerClient> clients;
        final int userClientCount;
        synchronized (mLock) {
            if (mClients == null) {
                return;
            }
            clients = mClients;
            userClientCount = clients.beginBroadcast();
        }
        try {
            for (int i = 0; i < userClientCount; i++) {
                final IAutoFillManagerClient client = clients.getBroadcastItem(i);
                try {
                    final boolean resetSession;
                    final boolean isEnabled;
                    synchronized (mLock) {
                        resetSession = resetClient || isClientSessionDestroyedLocked(client);
                        isEnabled = isEnabledLocked();
                    }
                    int flags = 0;
                    if (isEnabled) {
                        flags |= AutofillManager.SET_STATE_FLAG_ENABLED;
                    }
                    if (resetSession) {
                        flags |= AutofillManager.SET_STATE_FLAG_RESET_SESSION;
                    }
                    if (resetClient) {
                        flags |= AutofillManager.SET_STATE_FLAG_RESET_CLIENT;
                    }
                    if (sDebug) {
                        flags |= AutofillManager.SET_STATE_FLAG_DEBUG;
                    }
                    if (sVerbose) {
                        flags |= AutofillManager.SET_STATE_FLAG_VERBOSE;
                    }
                    client.setState(flags);
                } catch (RemoteException re) {
                    /* ignore */
                }
            }
        } finally {
            clients.finishBroadcast();
        }
    }

    @GuardedBy("mLock")
    private boolean isClientSessionDestroyedLocked(IAutoFillManagerClient client) {
        final int sessionCount = mSessions.size();
        for (int i = 0; i < sessionCount; i++) {
            final Session session = mSessions.valueAt(i);
            if (session.getClient().equals(client)) {
                return session.isDestroyed();
            }
        }
        return true;
    }

    /**
     * Called by {@link Session} when service asked to disable autofill for an app.
     */
    void disableAutofillForApp(@NonNull String packageName, long duration, int sessionId,
            boolean compatMode) {
        synchronized (mLock) {
            long expiration = SystemClock.elapsedRealtime() + duration;
            // Protect it against overflow
            if (expiration < 0) {
                expiration = Long.MAX_VALUE;
            }
            mDisabledInfoCache.addDisabledAppLocked(mUserId, packageName, expiration);

            int intDuration = duration > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) duration;
            mMetricsLogger.write(Helper.newLogMaker(MetricsEvent.AUTOFILL_SERVICE_DISABLED_APP,
                    packageName, getServicePackageName(), sessionId, compatMode)
                    .addTaggedData(MetricsEvent.FIELD_AUTOFILL_DURATION, intDuration));
        }
    }

    /**
     * Called by {@link Session} when service asked to disable autofill an app.
     */
    void disableAutofillForActivity(@NonNull ComponentName componentName, long duration,
            int sessionId, boolean compatMode) {
        synchronized (mLock) {
            long expiration = SystemClock.elapsedRealtime() + duration;
            // Protect it against overflow
            if (expiration < 0) {
                expiration = Long.MAX_VALUE;
            }
            mDisabledInfoCache.addDisabledActivityLocked(mUserId, componentName, expiration);
            final int intDuration = duration > Integer.MAX_VALUE
                    ? Integer.MAX_VALUE
                    : (int) duration;
            // NOTE: not using Helper.newLogMaker() because we're setting the componentName instead
            // of package name
            final LogMaker log = new LogMaker(MetricsEvent.AUTOFILL_SERVICE_DISABLED_ACTIVITY)
                    .setComponentName(componentName)
                    .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE, getServicePackageName())
                    .addTaggedData(MetricsEvent.FIELD_AUTOFILL_DURATION, intDuration)
                    .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SESSION_ID, sessionId);
            if (compatMode) {
                log.addTaggedData(MetricsEvent.FIELD_AUTOFILL_COMPAT_MODE, 1);
            }
            mMetricsLogger.write(log);
        }
    }

    /**
     * Checks if autofill is disabled by service to the given activity.
     */
    @GuardedBy("mLock")
    private boolean isAutofillDisabledLocked(@NonNull ComponentName componentName) {
        return mDisabledInfoCache.isAutofillDisabledLocked(mUserId, componentName);
    }

    // Called by AutofillManager, checks UID.
    boolean isFieldClassificationEnabled(int callingUid) {
        synchronized (mLock) {
            if (!isCalledByServiceLocked("isFieldClassificationEnabled", callingUid)) {
                return false;
            }
            return isFieldClassificationEnabledLocked();
        }
    }

    // Called by internally, no need to check UID.
    boolean isFieldClassificationEnabledLocked() {
        return Settings.Secure.getIntForUser(
                getContext().getContentResolver(),
                Settings.Secure.AUTOFILL_FEATURE_FIELD_CLASSIFICATION, 1,
                mUserId) == 1;
    }

    FieldClassificationStrategy getFieldClassificationStrategy() {
        return mFieldClassificationStrategy;
    }

    String[] getAvailableFieldClassificationAlgorithms(int callingUid) {
        synchronized (mLock) {
            if (!isCalledByServiceLocked("getFCAlgorithms()", callingUid)) {
                return null;
            }
        }
        return mFieldClassificationStrategy.getAvailableAlgorithms();
    }

    String getDefaultFieldClassificationAlgorithm(int callingUid) {
        synchronized (mLock) {
            if (!isCalledByServiceLocked("getDefaultFCAlgorithm()", callingUid)) {
                return null;
            }
        }
        return mFieldClassificationStrategy.getDefaultAlgorithm();
    }

    private void updateRemoteInlineSuggestionRenderServiceLocked() {
        if (mRemoteInlineSuggestionRenderService != null) {
            if (sVerbose) {
                Slog.v(TAG, "updateRemoteInlineSuggestionRenderService(): "
                        + "destroying old remote service");
            }
            mRemoteInlineSuggestionRenderService = null;
        }

        mRemoteInlineSuggestionRenderService = getRemoteInlineSuggestionRenderServiceLocked();
    }

    @Nullable RemoteInlineSuggestionRenderService getRemoteInlineSuggestionRenderServiceLocked() {
        if (mRemoteInlineSuggestionRenderService == null) {
            final ComponentName componentName = RemoteInlineSuggestionRenderService
                .getServiceComponentName(getContext(), mUserId);
            if (componentName == null) {
                Slog.w(TAG, "No valid component found for InlineSuggestionRenderService");
                return null;
            }

            mRemoteInlineSuggestionRenderService = new RemoteInlineSuggestionRenderService(
                    getContext(), componentName, InlineSuggestionRenderService.SERVICE_INTERFACE,
                    mUserId, new InlineSuggestionRenderCallbacksImpl(),
                    mMaster.isBindInstantServiceAllowed(), mMaster.verbose);
        }

        return mRemoteInlineSuggestionRenderService;
    }

    private class InlineSuggestionRenderCallbacksImpl implements
            RemoteInlineSuggestionRenderService.InlineSuggestionRenderCallbacks {

        @Override // from InlineSuggestionRenderCallbacksImpl
        public void onServiceDied(@NonNull RemoteInlineSuggestionRenderService service) {
            // Don't do anything; eventually the system will bind to it again...
            Slog.w(TAG, "remote service died: " + service);
            mRemoteInlineSuggestionRenderService = null;
        }
    }

    void onSwitchInputMethod() {
        synchronized (mLock) {
            final int sessionCount = mSessions.size();
            for (int i = 0; i < sessionCount; i++) {
                final Session session = mSessions.valueAt(i);
                session.onSwitchInputMethodLocked();
            }
        }
    }

    @Override
    public String toString() {
        return "AutofillManagerServiceImpl: [userId=" + mUserId
                + ", component=" + (mInfo != null
                ? mInfo.getServiceInfo().getComponentName() : null) + "]";
    }

    /** Task used to prune abandoned session */
    private class PruneTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... ignored) {
            int numSessionsToRemove;

            SparseArray<IBinder> sessionsToRemove;

            synchronized (mLock) {
                numSessionsToRemove = mSessions.size();
                sessionsToRemove = new SparseArray<>(numSessionsToRemove);

                for (int i = 0; i < numSessionsToRemove; i++) {
                    Session session = mSessions.valueAt(i);

                    sessionsToRemove.put(session.id, session.getActivityTokenLocked());
                }
            }

            final ActivityTaskManagerInternal atmInternal = LocalServices.getService(
                    ActivityTaskManagerInternal.class);

            // Only remove sessions which's activities are not known to the activity manager anymore
            for (int i = 0; i < numSessionsToRemove; i++) {
                // The activity task manager cannot resolve activities that have been removed.
                if (atmInternal.getActivityName(sessionsToRemove.valueAt(i)) != null) {
                    sessionsToRemove.removeAt(i);
                    i--;
                    numSessionsToRemove--;
                }
            }

            synchronized (mLock) {
                for (int i = 0; i < numSessionsToRemove; i++) {
                    Session sessionToRemove = mSessions.get(sessionsToRemove.keyAt(i));

                    if (sessionToRemove != null && sessionsToRemove.valueAt(i)
                            == sessionToRemove.getActivityTokenLocked()) {
                        if (sessionToRemove.isSaveUiShowingLocked()) {
                            if (sVerbose) {
                                Slog.v(TAG, "Session " + sessionToRemove.id + " is saving");
                            }
                        } else {
                            if (sDebug) {
                                Slog.i(TAG, "Prune session " + sessionToRemove.id + " ("
                                    + sessionToRemove.getActivityTokenLocked() + ")");
                            }
                            sessionToRemove.removeFromServiceLocked();
                        }
                    }
                }
            }

            return null;
        }
    }
}
