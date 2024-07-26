/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app.servertransaction;

import static android.app.WindowConfiguration.areConfigurationsEqualForDisplay;
import static android.view.Display.INVALID_DISPLAY;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.ActivityThread;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManagerGlobal;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.window.ActivityWindowInfo;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;

/**
 * Singleton controller to manage listeners to individual {@link ClientTransaction}.
 *
 * @hide
 */
public class ClientTransactionListenerController {

    private static final String TAG = "ClientTransactionListenerController";

    private static ClientTransactionListenerController sController;

    private final Object mLock = new Object();
    private final DisplayManagerGlobal mDisplayManager;

    /** Listeners registered via {@link #registerActivityWindowInfoChangedListener(BiConsumer)}. */
    @GuardedBy("mLock")
    private final ArraySet<BiConsumer<IBinder, ActivityWindowInfo>>
            mActivityWindowInfoChangedListeners = new ArraySet<>();

    /**
     * Keeps track of the Context whose Configuration will get updated, mapping to the config before
     * the change.
     */
    @GuardedBy("mLock")
    private final ArrayMap<Context, Configuration> mContextToPreChangedConfigMap = new ArrayMap<>();

    /** Whether there is an {@link ClientTransaction} being executed. */
    @GuardedBy("mLock")
    private boolean mIsClientTransactionExecuting;

    /** Gets the singleton controller. */
    @NonNull
    public static ClientTransactionListenerController getInstance() {
        synchronized (ClientTransactionListenerController.class) {
            if (sController == null) {
                sController = new ClientTransactionListenerController(
                        DisplayManagerGlobal.getInstance());
            }
            return sController;
        }
    }

    /** Creates a new instance for test only. */
    @VisibleForTesting
    @NonNull
    public static ClientTransactionListenerController createInstanceForTesting(
            @NonNull DisplayManagerGlobal displayManager) {
        return new ClientTransactionListenerController(displayManager);
    }

    private ClientTransactionListenerController(@NonNull DisplayManagerGlobal displayManager) {
        mDisplayManager = requireNonNull(displayManager);
    }

    /**
     * Registers to listen on activity {@link ActivityWindowInfo} change.
     * The listener will be invoked with two parameters: {@link Activity#getActivityToken()} and
     * {@link ActivityWindowInfo}.
     */
    public void registerActivityWindowInfoChangedListener(
            @NonNull BiConsumer<IBinder, ActivityWindowInfo> listener) {
        synchronized (mLock) {
            mActivityWindowInfoChangedListeners.add(listener);
        }
    }

    /**
     * Unregisters the listener that was previously registered via
     * {@link #registerActivityWindowInfoChangedListener(BiConsumer)}
     */
    public void unregisterActivityWindowInfoChangedListener(
            @NonNull BiConsumer<IBinder, ActivityWindowInfo> listener) {
        synchronized (mLock) {
            mActivityWindowInfoChangedListeners.remove(listener);
        }
    }

    /**
     * Called when receives a {@link ClientTransaction} that is updating an activity's
     * {@link ActivityWindowInfo}.
     */
    public void onActivityWindowInfoChanged(@NonNull IBinder activityToken,
            @NonNull ActivityWindowInfo activityWindowInfo) {
        final Object[] activityWindowInfoChangedListeners;
        synchronized (mLock) {
            if (mActivityWindowInfoChangedListeners.isEmpty()) {
                return;
            }
            activityWindowInfoChangedListeners = mActivityWindowInfoChangedListeners.toArray();
        }
        for (Object activityWindowInfoChangedListener : activityWindowInfoChangedListeners) {
            ((BiConsumer<IBinder, ActivityWindowInfo>) activityWindowInfoChangedListener)
                    .accept(activityToken, new ActivityWindowInfo(activityWindowInfo));
        }
    }

    /** Called when starts executing a remote {@link ClientTransaction}. */
    public void onClientTransactionStarted() {
        synchronized (mLock) {
            mIsClientTransactionExecuting = true;
        }
    }

    /** Called when finishes executing a remote {@link ClientTransaction}. */
    public void onClientTransactionFinished() {
        final ArraySet<Integer> configUpdatedDisplayIds;
        synchronized (mLock) {
            mIsClientTransactionExecuting = false;

            // When {@link Configuration} is changed, we want to trigger display change callback as
            // well, because Display reads some fields from {@link Configuration}.
            if (mContextToPreChangedConfigMap.isEmpty()) {
                return;
            }

            // Calculate display ids that have config changed.
            configUpdatedDisplayIds = new ArraySet<>();
            final int contextCount = mContextToPreChangedConfigMap.size();
            try {
                for (int i = 0; i < contextCount; i++) {
                    final Context context = mContextToPreChangedConfigMap.keyAt(i);
                    final Configuration preChangedConfig = mContextToPreChangedConfigMap.valueAt(i);
                    if (shouldReportDisplayChange(context, preChangedConfig)) {
                        configUpdatedDisplayIds.add(context.getDisplayId());
                    }
                }
            } finally {
                mContextToPreChangedConfigMap.clear();
            }
        }

        // Dispatch the display changed callbacks.
        try {
            final int displayCount = configUpdatedDisplayIds.size();
            for (int i = 0; i < displayCount; i++) {
                final int displayId = configUpdatedDisplayIds.valueAt(i);
                onDisplayChanged(displayId);
            }
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Failed to notify DisplayListener because the Handler is shutting down");
        }
    }

    /** Called before updating the Configuration of the given {@code context}. */
    public void onContextConfigurationPreChanged(@NonNull Context context) {
        if (ActivityThread.isSystem()) {
            // Not enable for system server.
            return;
        }
        synchronized (mLock) {
            if (mContextToPreChangedConfigMap.containsKey(context)) {
                // There is an earlier change that hasn't been reported yet.
                return;
            }
            mContextToPreChangedConfigMap.put(context,
                    new Configuration(context.getResources().getConfiguration()));
        }
    }

    /** Called after updating the Configuration of the given {@code context}. */
    public void onContextConfigurationPostChanged(@NonNull Context context) {
        if (ActivityThread.isSystem()) {
            // Not enable for system server.
            return;
        }
        int changedDisplayId = INVALID_DISPLAY;
        synchronized (mLock) {
            if (mIsClientTransactionExecuting) {
                // Wait until #onClientTransactionFinished to prevent it from triggering the same
                // #onDisplayChanged multiple times within the same ClientTransaction.
                return;
            }
            final Configuration preChangedConfig = mContextToPreChangedConfigMap.remove(context);
            if (preChangedConfig != null && shouldReportDisplayChange(context, preChangedConfig)) {
                changedDisplayId = context.getDisplayId();
            }
        }

        if (changedDisplayId != INVALID_DISPLAY) {
            try {
                onDisplayChanged(changedDisplayId);
            } catch (RejectedExecutionException e) {
                Log.w(TAG, "Failed to notify DisplayListener because the Handler is shutting down");
            }
        }
    }

    private boolean shouldReportDisplayChange(@NonNull Context context,
            @NonNull Configuration preChangedConfig) {
        final Configuration postChangedConfig = context.getResources().getConfiguration();
        return !areConfigurationsEqualForDisplay(postChangedConfig, preChangedConfig);
    }

    /**
     * Called when receives a {@link Configuration} changed event that is updating display-related
     * window configuration.
     *
     * @throws RejectedExecutionException if the display listener handler is closing.
     */
    @VisibleForTesting
    public void onDisplayChanged(int displayId) throws RejectedExecutionException {
        mDisplayManager.handleDisplayChangeFromWindowManager(displayId);
    }
}
