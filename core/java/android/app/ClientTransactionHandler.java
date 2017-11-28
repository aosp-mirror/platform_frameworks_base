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

import android.app.servertransaction.ClientTransaction;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;

import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.ReferrerIntent;

import java.util.List;

/**
 * Defines operations that a {@link android.app.servertransaction.ClientTransaction} or its items
 * can perform on client.
 * @hide
 */
public abstract class ClientTransactionHandler {

    // Schedule phase related logic and handlers.

    /** Prepare and schedule transaction for execution. */
    void scheduleTransaction(ClientTransaction transaction) {
        transaction.prepare(this);
        sendMessage(ActivityThread.H.EXECUTE_TRANSACTION, transaction);
    }

    abstract void sendMessage(int what, Object obj);


    // Prepare phase related logic and handlers. Methods that inform about about pending changes or
    // do other internal bookkeeping.

    /** Get current lifecycle request number to maintain correct ordering. */
    public abstract int getLifecycleSeq();

    /** Set pending config in case it will be updated by other transaction item. */
    public abstract void updatePendingConfiguration(Configuration config);

    /** Set current process state. */
    public abstract void updateProcessState(int processState, boolean fromIpc);


    // Execute phase related logic and handlers. Methods here execute actual lifecycle transactions
    // and deliver callbacks.

    /** Destroy the activity. */
    public abstract void handleDestroyActivity(IBinder token, boolean finishing, int configChanges,
            boolean getNonConfigInstance);

    /** Pause the activity. */
    public abstract void handlePauseActivity(IBinder token, boolean finished, boolean userLeaving,
            int configChanges, boolean dontReport, int seq);

    /** Resume the activity. */
    public abstract void handleResumeActivity(IBinder token, boolean clearHide, boolean isForward,
            boolean reallyResume, int seq, String reason);

    /** Stop the activity. */
    public abstract void handleStopActivity(IBinder token, boolean show, int configChanges,
            int seq);

    /** Deliver activity (override) configuration change. */
    public abstract void handleActivityConfigurationChanged(IBinder activityToken,
            Configuration overrideConfig, int displayId);

    /** Deliver result from another activity. */
    public abstract void handleSendResult(IBinder token, List<ResultInfo> results);

    /** Deliver multi-window mode change notification. */
    public abstract void handleMultiWindowModeChanged(IBinder token, boolean isInMultiWindowMode,
            Configuration overrideConfig);

    /** Deliver new intent. */
    public abstract void handleNewIntent(IBinder token, List<ReferrerIntent> intents,
            boolean andPause);

    /** Deliver picture-in-picture mode change notification. */
    public abstract void handlePictureInPictureModeChanged(IBinder token, boolean isInPipMode,
            Configuration overrideConfig);

    /** Update window visibility. */
    public abstract void handleWindowVisibility(IBinder token, boolean show);

    /** Perform activity launch. */
    public abstract void handleLaunchActivity(IBinder token, Intent intent, int ident,
            ActivityInfo info, Configuration overrideConfig, CompatibilityInfo compatInfo,
            String referrer, IVoiceInteractor voiceInteractor, Bundle state,
            PersistableBundle persistentState, List<ResultInfo> pendingResults,
            List<ReferrerIntent> pendingNewIntents, boolean notResumed, boolean isForward,
            ProfilerInfo profilerInfo);

    /** Deliver app configuration change notification. */
    public abstract void handleConfigurationChanged(Configuration config);
}
