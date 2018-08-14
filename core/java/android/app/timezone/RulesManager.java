/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.app.timezone;

import android.annotation.IntDef;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * The interface through which a time zone update application interacts with the Android system
 * to handle time zone rule updates.
 *
 * <p>This interface is intended for use with the default APK-based time zone rules update
 * application but it can also be used by OEMs if that mechanism is turned off using configuration.
 * All callers must possess the {@link android.Manifest.permission#UPDATE_TIME_ZONE_RULES} system
 * permission unless otherwise stated.
 *
 * <p>When using the default mechanism, when properly configured the Android system will send a
 * {@link RulesUpdaterContract#ACTION_TRIGGER_RULES_UPDATE_CHECK} intent with a
 * {@link RulesUpdaterContract#EXTRA_CHECK_TOKEN} extra to the time zone rules updater application
 * when it detects that it or the OEM's APK containing time zone rules data has been modified. The
 * updater application is then responsible for calling one of
 * {@link #requestInstall(ParcelFileDescriptor, byte[], Callback)},
 * {@link #requestUninstall(byte[], Callback)} or
 * {@link #requestNothing(byte[], boolean)}, indicating, respectively, whether a new time zone rules
 * distro should be installed, the current distro should be uninstalled, or there is nothing to do
 * (or that the correct operation could not be determined due to an error). In each case the updater
 * must pass the {@link RulesUpdaterContract#EXTRA_CHECK_TOKEN} value it received from the intent
 * back so the system in the {@code checkToken} parameter.
 *
 * <p>If OEMs want to handle their own time zone rules updates, perhaps via a server-side component
 * rather than an APK, then they should disable the default triggering mechanism in config and are
 * responsible for triggering their own update checks / installs / uninstalls. In this case the
 * "check token" parameter can be left null and there is never any need to call
 * {@link #requestNothing(byte[], boolean)}.
 *
 * <p>OEMs should not mix the default mechanism and their own as this could lead to conflicts and
 * unnecessary checks being triggered.
 *
 * <p>Applications obtain this using {@link android.app.Activity#getSystemService(String)} with
 * {@link Context#TIME_ZONE_RULES_MANAGER_SERVICE}.
 * @hide
 */
public final class RulesManager {
    private static final String TAG = "timezone.RulesManager";
    private static final boolean DEBUG = false;

    /**
     * The action of the intent that the Android system will broadcast when a time zone rules update
     * operation has been successfully staged  (i.e. to be applied next reboot) or unstaged.
     *
     * <p>See {@link #EXTRA_OPERATION_STAGED}
     *
     * <p>This is a protected intent that can only be sent by the system.
     */
    public static final String ACTION_RULES_UPDATE_OPERATION =
            "com.android.intent.action.timezone.RULES_UPDATE_OPERATION";

    /**
     * The key for a boolean extra for the {@link #ACTION_RULES_UPDATE_OPERATION} intent used to
     * indicate whether the operation was a "stage" or an "unstage".
     */
    public static final String EXTRA_OPERATION_STAGED = "staged";

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "SUCCESS", "ERROR_" }, value = {
            SUCCESS,
            ERROR_UNKNOWN_FAILURE,
            ERROR_OPERATION_IN_PROGRESS
    })
    public @interface ResultCode {}

    /**
     * Indicates that an operation succeeded.
     */
    public static final int SUCCESS = 0;

    /**
     * Indicates that an install/uninstall cannot be initiated because there is one already in
     * progress.
     */
    public static final int ERROR_OPERATION_IN_PROGRESS = 1;

    /**
     * Indicates an install / uninstall did not fully succeed for an unknown reason.
     */
    public static final int ERROR_UNKNOWN_FAILURE = 2;

    private final Context mContext;
    private final IRulesManager mIRulesManager;

    public RulesManager(Context context) {
        mContext = context;
        mIRulesManager = IRulesManager.Stub.asInterface(
                ServiceManager.getService(Context.TIME_ZONE_RULES_MANAGER_SERVICE));
    }

    /**
     * Returns information about the current time zone rules state such as the IANA version of
     * the system and any currently installed distro. This method allows clients to determine the
     * current device state, perhaps to see if it can be improved; for example by passing the
     * information to a server that may provide a new distro for download.
     *
     * <p>Callers must possess the {@link android.Manifest.permission#QUERY_TIME_ZONE_RULES} system
     * permission.
     */
    public RulesState getRulesState() {
        try {
            logDebug("mIRulesManager.getRulesState()");
            RulesState rulesState = mIRulesManager.getRulesState();
            logDebug("mIRulesManager.getRulesState() returned " + rulesState);
            return rulesState;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests installation of the supplied distro. The distro must have been checked for integrity
     * by the caller or have been received via a trusted mechanism.
     *
     * @param distroFileDescriptor the file descriptor for the distro
     * @param checkToken an optional token provided if the install was triggered in response to a
     *     {@link RulesUpdaterContract#ACTION_TRIGGER_RULES_UPDATE_CHECK} intent
     * @param callback the {@link Callback} to receive callbacks related to the installation
     * @return {@link #SUCCESS} if the installation will be attempted
     */
    @ResultCode
    public int requestInstall(
            ParcelFileDescriptor distroFileDescriptor, byte[] checkToken, Callback callback)
            throws IOException {

        ICallback iCallback = new CallbackWrapper(mContext, callback);
        try {
            logDebug("mIRulesManager.requestInstall()");
            return mIRulesManager.requestInstall(distroFileDescriptor, checkToken, iCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests uninstallation of the currently installed distro (leaving the device with no
     * distro installed).
     *
     * @param checkToken an optional token provided if the uninstall was triggered in response to a
     *     {@link RulesUpdaterContract#ACTION_TRIGGER_RULES_UPDATE_CHECK} intent
     * @param callback the {@link Callback} to receive callbacks related to the uninstall
     * @return {@link #SUCCESS} if the uninstallation will be attempted
     */
    @ResultCode
    public int requestUninstall(byte[] checkToken, Callback callback) {
        ICallback iCallback = new CallbackWrapper(mContext, callback);
        try {
            logDebug("mIRulesManager.requestUninstall()");
            return mIRulesManager.requestUninstall(checkToken, iCallback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /*
     * We wrap incoming binder calls with a private class implementation that
     * redirects them into main-thread actions.  This serializes the backup
     * progress callbacks nicely within the usual main-thread lifecycle pattern.
     */
    private class CallbackWrapper extends ICallback.Stub {
        final Handler mHandler;
        final Callback mCallback;

        CallbackWrapper(Context context, Callback callback) {
            mCallback = callback;
            mHandler = new Handler(context.getMainLooper());
        }

        // Binder calls into this object just enqueue on the main-thread handler
        @Override
        public void onFinished(int status) {
            logDebug("mCallback.onFinished(status), status=" + status);
            mHandler.post(() -> mCallback.onFinished(status));
        }
    }

    /**
     * Requests the system does not modify the currently installed time zone distro, if any. This
     * method records the fact that a time zone check operation triggered by the system is now
     * complete and there was nothing to do. The token passed should be the one presented when the
     * check was triggered.
     *
     * <p>Note: Passing {@code success == false} may result in more checks being triggered. Clients
     * should be careful not to pass false if the failure is unlikely to resolve by itself.
     *
     * @param checkToken an optional token provided if the install was triggered in response to a
     *     {@link RulesUpdaterContract#ACTION_TRIGGER_RULES_UPDATE_CHECK} intent
     * @param succeeded true if the check was successful, false if it was not successful but may
     *     succeed if it is retried
     */
    public void requestNothing(byte[] checkToken, boolean succeeded) {
        try {
            logDebug("mIRulesManager.requestNothing() with token=" + Arrays.toString(checkToken));
            mIRulesManager.requestNothing(checkToken, succeeded);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    static void logDebug(String msg) {
        if (DEBUG) {
            Log.v(TAG, msg);
        }
    }
}
