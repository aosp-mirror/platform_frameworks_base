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

import static android.service.autofill.AutofillService.EXTRA_ACTIVITY_TOKEN;
import static android.service.voice.VoiceInteractionSession.KEY_RECEIVER_EXTRAS;
import static android.service.voice.VoiceInteractionSession.KEY_STRUCTURE;
import static android.view.autofill.AutofillManager.FLAG_START_SESSION;

import static com.android.server.autofill.Helper.DEBUG;
import static com.android.server.autofill.Helper.VERBOSE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserManager;
import android.provider.Settings;
import android.service.autofill.AutofillService;
import android.service.autofill.AutofillServiceInfo;
import android.service.autofill.IAutoFillService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.LocalLog;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.IResultReceiver;
import com.android.server.autofill.ui.AutoFillUI;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Bridge between the {@code system_server}'s {@link AutofillManagerService} and the
 * app's {@link IAutoFillService} implementation.
 *
 */
final class AutofillManagerServiceImpl {

    private static final String TAG = "AutofillManagerServiceImpl";

    static final int MSG_SERVICE_SAVE = 1;

    private final int mUserId;
    private final Context mContext;
    private final Object mLock;
    private final AutoFillUI mUi;

    private RemoteCallbackList<IAutoFillManagerClient> mClients;
    private AutofillServiceInfo mInfo;

    private final LocalLog mRequestsHistory;
    /**
     * Whether service was disabled for user due to {@link UserManager} restrictions.
     */
    private boolean mDisabled;

    private final HandlerCaller.Callback mHandlerCallback = (msg) -> {
        switch (msg.what) {
            case MSG_SERVICE_SAVE:
                handleSessionSave((IBinder) msg.obj);
                break;
            default:
                Slog.w(TAG, "invalid msg on handler: " + msg);
        }
    };

    private final HandlerCaller mHandlerCaller = new HandlerCaller(null, Looper.getMainLooper(),
            mHandlerCallback, true);

    /**
     * Cache of pending {@link Session}s, keyed by {@code activityToken}.
     *
     * <p>They're kept until the {@link AutofillService} finished handling a request, an error
     * occurs, or the session times out.
     */
    // TODO(b/33197203): need to make sure service is bound while callback is pending and/or
    // use WeakReference
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, Session> mSessions = new ArrayMap<>();

    /**
     * Receiver of assist data from the app's {@link Activity}.
     */
    private final IResultReceiver mAssistReceiver = new IResultReceiver.Stub() {
        @Override
        public void send(int resultCode, Bundle resultData) throws RemoteException {
            if (VERBOSE) {
                Slog.v(TAG, "resultCode on mAssistReceiver: " + resultCode);
            }

            final AssistStructure structure = resultData.getParcelable(KEY_STRUCTURE);
            if (structure == null) {
                Slog.wtf(TAG, "no assist structure for id " + resultCode);
                return;
            }

            final Bundle receiverExtras = resultData.getBundle(KEY_RECEIVER_EXTRAS);
            if (receiverExtras == null) {
                Slog.wtf(TAG, "No " + KEY_RECEIVER_EXTRAS + " on receiver");
                return;
            }

            final IBinder activityToken = receiverExtras.getBinder(EXTRA_ACTIVITY_TOKEN);
            final Session session;
            synchronized (mLock) {
                session = mSessions.get(activityToken);
                if (session == null) {
                    Slog.w(TAG, "no server session for activityToken " + activityToken);
                    return;
                }
                // TODO(b/33197203): since service is fetching the data (to use for save later),
                // we should optimize what's sent (for example, remove layout containers,
                // color / font info, etc...)
                session.mStructure = structure;
            }


            // TODO(b/33197203, b/33269702): Must fetch the data so it's available later on
            // handleSave(), even if if the activity is gone by then, but structure.ensureData()
            // gives a ONE_WAY warning because system_service could block on app calls.
            // We need to change AssistStructure so it provides a "one-way" writeToParcel()
            // method that sends all the data
            structure.ensureData();

            // Sanitize structure before it's sent to service.
            structure.sanitizeForParceling(true);

            // TODO(b/33197203): Need to pipe the bundle
            session.mRemoteFillService.onFillRequest(structure, null, session.mFlags);
        }
    };

    AutofillManagerServiceImpl(Context context, Object lock, LocalLog requestsHistory,
            int userId, AutoFillUI ui, boolean disabled) {
        mContext = context;
        mLock = lock;
        mRequestsHistory = requestsHistory;
        mUserId = userId;
        mUi = ui;
        updateLocked(disabled);
    }

    CharSequence getServiceName() {
        if (mInfo == null) {
            return null;
        }
        final ComponentName serviceComponent = mInfo.getServiceInfo().getComponentName();
        final String packageName = serviceComponent.getPackageName();

        try {
            final PackageManager pm = mContext.getPackageManager();
            final ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info);
        } catch (Exception e) {
            Slog.e(TAG, "Could not get label for " + packageName + ": " + e);
            return packageName;
        }
    }

    void updateLocked(boolean disabled) {
        final boolean wasEnabled = isEnabled();
        mDisabled = disabled;
        ComponentName serviceComponent = null;
        ServiceInfo serviceInfo = null;
        final String componentName = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), Settings.Secure.AUTOFILL_SERVICE, mUserId);
        if (!TextUtils.isEmpty(componentName)) {
            try {
                serviceComponent = ComponentName.unflattenFromString(componentName);
                serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                        0, mUserId);
            } catch (RuntimeException | RemoteException e) {
                Slog.e(TAG, "Bad autofill service name " + componentName + ": " + e);
                return;
            }
        }
        try {
            if (serviceInfo != null) {
                mInfo = new AutofillServiceInfo(mContext.getPackageManager(),
                        serviceComponent, mUserId);
            } else {
                mInfo = null;
            }
            if (wasEnabled != isEnabled()) {
                if (!isEnabled()) {
                    final int sessionCount = mSessions.size();
                    for (int i = sessionCount - 1; i >= 0; i--) {
                        final Session session = mSessions.valueAt(i);
                        session.removeSelfLocked();
                    }
                }
                sendStateToClients();
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Bad autofill service name " + componentName + ": " + e);
        }
    }

    /**
     * Used by {@link AutofillManagerServiceShellCommand} to request save for the current top app.
     */
    void requestSaveForUserLocked(IBinder activityToken) {
        if (!isEnabled()) {
            return;
        }
        final Session session = mSessions.get(activityToken);
        if (session == null) {
            Slog.w(TAG, "requestSaveForUserLocked(): no session for " + activityToken);
            return;
        }

        session.callSaveLocked();
    }

    boolean addClientLocked(IAutoFillManagerClient client) {
        if (mClients == null) {
            mClients = new RemoteCallbackList<>();
        }
        mClients.register(client);
        return isEnabled();
    }

    void setAuthenticationResultLocked(Bundle data, IBinder activityToken) {
        if (!isEnabled()) {
            return;
        }
        final Session session = mSessions.get(activityToken);
        if (session != null) {
            session.setAuthenticationResultLocked(data);
        }
    }

    void setHasCallback(IBinder activityToken, boolean hasIt) {
        if (!isEnabled()) {
            return;
        }
        final Session session = mSessions.get(activityToken);
        if (session != null) {
            session.setHasCallback(hasIt);
        }
    }

    void startSessionLocked(@NonNull IBinder activityToken, @Nullable IBinder windowToken,
            @NonNull IBinder appCallbackToken, @NonNull AutofillId autofillId,
            @NonNull Rect virtualBounds, @Nullable AutofillValue value, boolean hasCallback,
            int flags, @NonNull String packageName) {
        if (!isEnabled()) {
            return;
        }

        final String historyItem = "s=" + mInfo.getServiceInfo().packageName
                + " u=" + mUserId + " a=" + activityToken
                + " i=" + autofillId + " b=" + virtualBounds + " hc=" + hasCallback
                + " f=" + flags;
        mRequestsHistory.log(historyItem);

        // TODO(b/33197203): Handle partitioning
        final Session session = mSessions.get(activityToken);
        if (session != null) {
            // Already started...
            return;
        }

        final Session newSession = createSessionByTokenLocked(activityToken,
                windowToken, appCallbackToken, hasCallback, flags, packageName);
        newSession.updateLocked(autofillId, virtualBounds, value, FLAG_START_SESSION);
    }

    void finishSessionLocked(IBinder activityToken) {
        if (!isEnabled()) {
            return;
        }

        final Session session = mSessions.get(activityToken);
        if (session == null) {
            Slog.w(TAG, "finishSessionLocked(): no session for " + activityToken);
            return;
        }

        final boolean finished = session.showSaveLocked();
        if (DEBUG) {
            Log.d(TAG, "finishSessionLocked(): session finished on save? " + finished);
        }
        if (finished) {
            session.removeSelf();
        }
    }

    void cancelSessionLocked(IBinder activityToken) {
        if (!isEnabled()) {
            return;
        }

        final Session session = mSessions.get(activityToken);
        if (session == null) {
            Slog.w(TAG, "cancelSessionLocked(): no session for " + activityToken);
            return;
        }
        session.removeSelfLocked();
    }

    private Session createSessionByTokenLocked(@NonNull IBinder activityToken,
            @Nullable IBinder windowToken, @NonNull IBinder appCallbackToken, boolean hasCallback,
            int flags, @NonNull String packageName) {
        final Session newSession = new Session(this, mUi, mContext, mHandlerCaller, mUserId, mLock,
                activityToken, windowToken, appCallbackToken, hasCallback, flags,
                mInfo.getServiceInfo().getComponentName(), packageName);
        mSessions.put(activityToken, newSession);

        /*
         * TODO(b/33197203): apply security checks below:
         * - checks if disabled by secure settings / device policy
         * - log operation using noteOp()
         * - check flags
         * - display disclosure if needed
         */
        try {
            final Bundle receiverExtras = new Bundle();
            receiverExtras.putBinder(EXTRA_ACTIVITY_TOKEN, activityToken);
            final long identity = Binder.clearCallingIdentity();
            try {
                if (!ActivityManager.getService().requestAutofillData(mAssistReceiver,
                        receiverExtras, activityToken)) {
                    Slog.w(TAG, "failed to request autofill data for " + activityToken);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (RemoteException e) {
            // Should not happen, it's a local call.
        }
        return newSession;
    }

    void updateSessionLocked(IBinder activityToken, AutofillId autofillId, Rect virtualBounds,
            AutofillValue value, int flags) {
        final Session session = mSessions.get(activityToken);
        if (session == null) {
            if (VERBOSE) {
                Slog.v(TAG, "updateSessionLocked(): session gone for " + activityToken);
            }
            return;
        }

        session.updateLocked(autofillId, virtualBounds, value, flags);
    }

    void removeSessionLocked(IBinder activityToken) {
        mSessions.remove(activityToken);
    }

    private void handleSessionSave(IBinder activityToken) {
        synchronized (mLock) {
            final Session session = mSessions.get(activityToken);
            if (session == null) {
                Slog.w(TAG, "handleSessionSave(): already gone: " + activityToken);

                return;
            }
            session.callSaveLocked();
        }
    }

    void destroyLocked() {
        if (VERBOSE) {
            Slog.v(TAG, "destroyLocked()");
        }

        for (Session session : mSessions.values()) {
            session.destroyLocked();
        }
        mSessions.clear();
    }

    void disableSelf() {
        final long identity = Binder.clearCallingIdentity();
        try {
            final String autoFillService = Settings.Secure.getStringForUser(
                    mContext.getContentResolver(), Settings.Secure.AUTOFILL_SERVICE, mUserId);
            if (mInfo.getServiceInfo().getComponentName().equals(
                    ComponentName.unflattenFromString(autoFillService))) {
                Settings.Secure.putStringForUser(mContext.getContentResolver(),
                        Settings.Secure.AUTOFILL_SERVICE, null, mUserId);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    CharSequence getServiceLabel() {
        return mInfo.getServiceInfo().loadLabel(mContext.getPackageManager());
    }

    void dumpLocked(String prefix, PrintWriter pw) {
        final String prefix2 = prefix + "  ";

        pw.print(prefix); pw.print("User :"); pw.println(mUserId);
        pw.print(prefix); pw.print("Component:"); pw.println(mInfo != null
                ? mInfo.getServiceInfo().getComponentName() : null);
        pw.print(prefix); pw.print("Default component: ");
            pw.println(mContext.getString(R.string.config_defaultAutofillService));
        pw.print(prefix); pw.print("Disabled:"); pw.println(mDisabled);

        if (VERBOSE && mInfo != null) {
            // ServiceInfo dump is too noisy and redundant (it can be obtained through other dumps)
            pw.print(prefix); pw.println("ServiceInfo:");
            mInfo.getServiceInfo().dump(new PrintWriterPrinter(pw), prefix + prefix);
        }

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
    }

    void destroySessionsLocked() {
        for (Session session : mSessions.values()) {
            session.removeSelf();
        }
    }

    void listSessionsLocked(ArrayList<String> output) {
        for (IBinder activityToken : mSessions.keySet()) {
            output.add((mInfo != null ? mInfo.getServiceInfo().getComponentName()
                    : null) + ":" + activityToken);
        }
    }

    private void sendStateToClients() {
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
                    client.setState(isEnabled());
                } catch (RemoteException re) {
                    /* ignore */
                }
            }
        } finally {
            clients.finishBroadcast();
        }
    }

    private boolean isEnabled() {
        return mInfo != null && !mDisabled;
    }

    @Override
    public String toString() {
        return "AutofillManagerServiceImpl: [userId=" + mUserId
                + ", component=" + (mInfo != null
                ? mInfo.getServiceInfo().getComponentName() : null) + "]";
    }
}
