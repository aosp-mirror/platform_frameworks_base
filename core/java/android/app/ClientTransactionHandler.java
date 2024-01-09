/*
 * Copyright 2017 The Android Open Source Project
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
package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions.SceneTransitionInfo;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.servertransaction.ClientTransaction;
import android.app.servertransaction.DestroyActivityItem;
import android.app.servertransaction.PendingTransactionActions;
import android.app.servertransaction.TransactionExecutor;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.IBinder;
import android.util.MergedConfiguration;
import android.view.SurfaceControl;
import android.window.SplashScreenView.SplashScreenViewParcelable;
import android.window.WindowContext;
import android.window.WindowContextInfo;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.content.ReferrerIntent;

import java.util.List;
import java.util.Map;

/**
 * Defines operations that a {@link android.app.servertransaction.ClientTransaction} or its items
 * can perform on client.
 * @hide
 */
public abstract class ClientTransactionHandler {

    private boolean mIsExecutingLocalTransaction;

    // Schedule phase related logic and handlers.

    /** Prepare and schedule transaction for execution. */
    void scheduleTransaction(ClientTransaction transaction) {
        transaction.preExecute(this);
        sendMessage(ActivityThread.H.EXECUTE_TRANSACTION, transaction);
    }

    /**
     * Execute transaction immediately without scheduling it. This is used for local requests, so
     * it will also recycle the transaction.
     */
    @VisibleForTesting
    public void executeTransaction(ClientTransaction transaction) {
        mIsExecutingLocalTransaction = true;
        try {
            transaction.preExecute(this);
            getTransactionExecutor().execute(transaction);
        } finally {
            mIsExecutingLocalTransaction = false;
            transaction.recycle();
        }
    }

    /** Returns {@code true} if the current executing ClientTransaction is from local request. */
    public boolean isExecutingLocalTransaction() {
        return mIsExecutingLocalTransaction;
    }

    /**
     * Get the {@link TransactionExecutor} that will be performing lifecycle transitions and
     * callbacks for activities.
     */
    abstract TransactionExecutor getTransactionExecutor();

    abstract void sendMessage(int what, Object obj);

    /** Get activity instance for the token. */
    public abstract Activity getActivity(IBinder token);

    /** Gets the {@link WindowContext} instance for the token. */
    @Nullable
    public abstract Context getWindowContext(@NonNull IBinder clientToken);

    // Prepare phase related logic and handlers. Methods that inform about about pending changes or
    // do other internal bookkeeping.

    /** Set pending config in case it will be updated by other transaction item. */
    public abstract void updatePendingConfiguration(Configuration config);

    /** Set current process state. */
    public abstract void updateProcessState(int processState, boolean fromIpc);

    /** Count how many activities are launching. */
    public abstract void countLaunchingActivities(int num);

    // Execute phase related logic and handlers. Methods here execute actual lifecycle transactions
    // and deliver callbacks.

    /** Get activity and its corresponding transaction item which are going to destroy. */
    public abstract Map<IBinder, DestroyActivityItem> getActivitiesToBeDestroyed();

    /** Destroy the activity. */
    public abstract void handleDestroyActivity(@NonNull ActivityClientRecord r, boolean finishing,
            int configChanges, boolean getNonConfigInstance, String reason);

    /** Pause the activity. */
    public abstract void handlePauseActivity(@NonNull ActivityClientRecord r, boolean finished,
            boolean userLeaving, int configChanges, boolean autoEnteringPip,
            PendingTransactionActions pendingActions, String reason);

    /**
     * Resume the activity.
     * @param r Target activity record.
     * @param finalStateRequest Flag indicating if this call is handling final lifecycle state
     *                          request for a transaction.
     * @param isForward Flag indicating if next transition is forward.
     * @param reason Reason for performing this operation.
     */
    public abstract void handleResumeActivity(@NonNull ActivityClientRecord r,
            boolean finalStateRequest, boolean isForward, boolean shouldSendCompatFakeFocus,
            String reason);

    /**
     * Notify the activity about top resumed state change.
     * @param r Target activity record.
     * @param isTopResumedActivity Current state of the activity, {@code true} if it's the
     *                             topmost resumed activity in the system, {@code false} otherwise.
     * @param reason Reason for performing this operation.
     */
    public abstract void handleTopResumedActivityChanged(@NonNull ActivityClientRecord r,
            boolean isTopResumedActivity, String reason);

    /**
     * Stop the activity.
     * @param r Target activity record.
     * @param configChanges Activity configuration changes.
     * @param pendingActions Pending actions to be used on this or later stages of activity
     *                       transaction.
     * @param finalStateRequest Flag indicating if this call is handling final lifecycle state
     *                          request for a transaction.
     * @param reason Reason for performing this operation.
     */
    public abstract void handleStopActivity(@NonNull ActivityClientRecord r, int configChanges,
            PendingTransactionActions pendingActions, boolean finalStateRequest, String reason);

    /** Report that activity was stopped to server. */
    public abstract void reportStop(PendingTransactionActions pendingActions);

    /** Restart the activity after it was stopped. */
    public abstract void performRestartActivity(@NonNull ActivityClientRecord r, boolean start);

     /** Report that activity was refreshed to server. */
    public abstract void reportRefresh(@NonNull ActivityClientRecord r);

    /** Set pending activity configuration in case it will be updated by other transaction item. */
    public abstract void updatePendingActivityConfiguration(@NonNull IBinder token,
            Configuration overrideConfig);

    /** Deliver activity (override) configuration change. */
    public abstract void handleActivityConfigurationChanged(@NonNull ActivityClientRecord r,
            Configuration overrideConfig, int displayId);

    /** Deliver {@link android.window.WindowContextInfo} change. */
    public abstract void handleWindowContextInfoChanged(@NonNull IBinder clientToken,
            @NonNull WindowContextInfo info);

    /** Deliver {@link android.window.WindowContext} window removal event. */
    public abstract void handleWindowContextWindowRemoval(@NonNull IBinder clientToken);

    /** Deliver result from another activity. */
    public abstract void handleSendResult(
            @NonNull ActivityClientRecord r, List<ResultInfo> results, String reason);

    /** Deliver new intent. */
    public abstract void handleNewIntent(
            @NonNull ActivityClientRecord r, List<ReferrerIntent> intents);

    /** Request that an activity enter picture-in-picture. */
    public abstract void handlePictureInPictureRequested(@NonNull ActivityClientRecord r);

    /** Signal to an activity (that is currently in PiP) of PiP state changes. */
    public abstract void handlePictureInPictureStateChanged(@NonNull ActivityClientRecord r,
            PictureInPictureUiState pipState);

    /** Whether the activity want to handle splash screen exit animation */
    public abstract boolean isHandleSplashScreenExit(@NonNull IBinder token);

    /** Attach a splash screen window view to the top of the activity */
    public abstract void handleAttachSplashScreenView(@NonNull ActivityClientRecord r,
            @NonNull SplashScreenViewParcelable parcelable,
            @NonNull SurfaceControl startingWindowLeash);

    /** Perform activity launch. */
    public abstract Activity handleLaunchActivity(@NonNull ActivityClientRecord r,
            PendingTransactionActions pendingActions, int deviceId, Intent customIntent);

    /** Perform activity start. */
    public abstract void handleStartActivity(@NonNull ActivityClientRecord r,
            PendingTransactionActions pendingActions, SceneTransitionInfo sceneTransitionInfo);

    /** Get package info. */
    public abstract LoadedApk getPackageInfoNoCheck(ApplicationInfo ai);

    /** Deliver app configuration change notification and device association. */
    public abstract void handleConfigurationChanged(Configuration config, int deviceId);

    /**
     * Get {@link android.app.ActivityThread.ActivityClientRecord} instance that corresponds to the
     * provided token.
     */
    public abstract ActivityClientRecord getActivityClient(IBinder token);

    /**
     * Prepare activity relaunch to update internal bookkeeping. This is used to track multiple
     * relaunch and config update requests.
     * @param token Activity token.
     * @param pendingResults Activity results to be delivered.
     * @param pendingNewIntents New intent messages to be delivered.
     * @param configChanges Mask of configuration changes that have occurred.
     * @param config New configuration applied to the activity.
     * @param preserveWindow Whether the activity should try to reuse the window it created,
     *                        including the decor view after the relaunch.
     * @return An initialized instance of {@link ActivityThread.ActivityClientRecord} to use during
     *         relaunch, or {@code null} if relaunch cancelled.
     */
    public abstract ActivityClientRecord prepareRelaunchActivity(IBinder token,
            List<ResultInfo> pendingResults, List<ReferrerIntent> pendingNewIntents,
            int configChanges, MergedConfiguration config, boolean preserveWindow);

    /**
     * Perform activity relaunch.
     * @param r Activity client record prepared for relaunch.
     * @param pendingActions Pending actions to be used on later stages of activity transaction.
     * */
    public abstract void handleRelaunchActivity(@NonNull ActivityClientRecord r,
            PendingTransactionActions pendingActions);

    /**
     * Report that relaunch request was handled.
     * @param r Target activity record.
     */
    public abstract void reportRelaunch(@NonNull ActivityClientRecord r);
}
