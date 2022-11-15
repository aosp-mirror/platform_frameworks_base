/*
 * Copyright (C) 2021 The Android Open Source Project
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

package androidx.window.extensions.embedding;

import static android.app.ActivityManager.START_SUCCESS;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.window.TaskFragmentOrganizer.KEY_ERROR_CALLBACK_OP_TYPE;
import static android.window.TaskFragmentOrganizer.KEY_ERROR_CALLBACK_TASK_FRAGMENT_INFO;
import static android.window.TaskFragmentOrganizer.KEY_ERROR_CALLBACK_THROWABLE;
import static android.window.TaskFragmentTransaction.TYPE_ACTIVITY_REPARENTED_TO_TASK;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_APPEARED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_ERROR;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED;
import static android.window.TaskFragmentTransaction.TYPE_TASK_FRAGMENT_VANISHED;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT;
import static android.window.WindowContainerTransaction.HierarchyOp.HIERARCHY_OP_TYPE_START_ACTIVITY_IN_TASK_FRAGMENT;

import static androidx.window.extensions.embedding.SplitContainer.getFinishPrimaryWithSecondaryBehavior;
import static androidx.window.extensions.embedding.SplitContainer.getFinishSecondaryWithPrimaryBehavior;
import static androidx.window.extensions.embedding.SplitContainer.isStickyPlaceholderRule;
import static androidx.window.extensions.embedding.SplitContainer.shouldFinishAssociatedContainerWhenAdjacent;
import static androidx.window.extensions.embedding.SplitContainer.shouldFinishAssociatedContainerWhenStacked;
import static androidx.window.extensions.embedding.SplitPresenter.RESULT_EXPAND_FAILED_NO_TF_INFO;
import static androidx.window.extensions.embedding.SplitPresenter.getActivityIntentMinDimensionsPair;
import static androidx.window.extensions.embedding.SplitPresenter.shouldShowSplit;

import android.app.Activity;
import android.app.ActivityClient;
import android.app.ActivityOptions;
import android.app.ActivityThread;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.util.SparseArray;
import android.view.WindowMetrics;
import android.window.TaskFragmentAnimationParams;
import android.window.TaskFragmentInfo;
import android.window.TaskFragmentParentInfo;
import android.window.TaskFragmentTransaction;
import android.window.WindowContainerTransaction;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.window.common.CommonFoldingFeature;
import androidx.window.common.DeviceStateManagerFoldingFeatureProducer;
import androidx.window.common.EmptyLifecycleCallbacksAdapter;
import androidx.window.extensions.WindowExtensionsImpl;
import androidx.window.extensions.core.util.function.Consumer;
import androidx.window.extensions.core.util.function.Function;
import androidx.window.extensions.embedding.TransactionManager.TransactionRecord;
import androidx.window.extensions.layout.WindowLayoutComponentImpl;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Main controller class that manages split states and presentation.
 */
public class SplitController implements JetpackTaskFragmentOrganizer.TaskFragmentCallback,
        ActivityEmbeddingComponent {
    static final String TAG = "SplitController";
    static final boolean ENABLE_SHELL_TRANSITIONS =
            SystemProperties.getBoolean("persist.wm.debug.shell_transit", false);

    @VisibleForTesting
    @GuardedBy("mLock")
    final SplitPresenter mPresenter;

    @VisibleForTesting
    @GuardedBy("mLock")
    final TransactionManager mTransactionManager;

    // Currently applied split configuration.
    @GuardedBy("mLock")
    private final List<EmbeddingRule> mSplitRules = new ArrayList<>();

    /**
     * A developer-defined {@link SplitAttributes} calculator to compute the current
     * {@link SplitAttributes} with the current device and window states.
     * It is registered via {@link #setSplitAttributesCalculator(Function)}
     * and unregistered via {@link #clearSplitAttributesCalculator()}.
     * This is called when:
     * <ul>
     *   <li>{@link SplitPresenter#updateSplitContainer(SplitContainer, TaskFragmentContainer,
     *     WindowContainerTransaction)}</li>
     *   <li>There's a started Activity which matches {@link SplitPairRule} </li>
     *   <li>Checking whether the place holder should be launched if there's a Activity matches
     *   {@link SplitPlaceholderRule} </li>
     * </ul>
     */
    @GuardedBy("mLock")
    @Nullable
    private Function<SplitAttributesCalculatorParams, SplitAttributes> mSplitAttributesCalculator;

    /**
     * Map from Task id to {@link TaskContainer} which contains all TaskFragment and split pair info
     * below it.
     * When the app is host of multiple Tasks, there can be multiple splits controlled by the same
     * organizer.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    final SparseArray<TaskContainer> mTaskContainers = new SparseArray<>();

    /** Callback to Jetpack to notify about changes to split states. */
    @GuardedBy("mLock")
    @Nullable
    private Consumer<List<SplitInfo>> mEmbeddingCallback;
    private final List<SplitInfo> mLastReportedSplitStates = new ArrayList<>();
    private final Handler mHandler;
    final Object mLock = new Object();
    private final ActivityStartMonitor mActivityStartMonitor;

    public SplitController(@NonNull WindowLayoutComponentImpl windowLayoutComponent,
            @NonNull DeviceStateManagerFoldingFeatureProducer foldingFeatureProducer) {
        final MainThreadExecutor executor = new MainThreadExecutor();
        mHandler = executor.mHandler;
        mPresenter = new SplitPresenter(executor, windowLayoutComponent, this);
        mTransactionManager = new TransactionManager(mPresenter);
        final ActivityThread activityThread = ActivityThread.currentActivityThread();
        final Application application = activityThread.getApplication();
        // Register a callback to be notified about activities being created.
        application.registerActivityLifecycleCallbacks(new LifecycleCallbacks());
        // Intercept activity starts to route activities to new containers if necessary.
        Instrumentation instrumentation = activityThread.getInstrumentation();

        mActivityStartMonitor = new ActivityStartMonitor();
        instrumentation.addMonitor(mActivityStartMonitor);
        foldingFeatureProducer.addDataChangedCallback(new FoldingFeatureListener());
    }

    private class FoldingFeatureListener
            implements java.util.function.Consumer<List<CommonFoldingFeature>> {
        @Override
        public void accept(List<CommonFoldingFeature> foldingFeatures) {
            synchronized (mLock) {
                final TransactionRecord transactionRecord = mTransactionManager
                        .startNewTransaction();
                final WindowContainerTransaction wct = transactionRecord.getTransaction();
                for (int i = 0; i < mTaskContainers.size(); i++) {
                    final TaskContainer taskContainer = mTaskContainers.valueAt(i);
                    if (!taskContainer.isVisible()) {
                        continue;
                    }
                    if (taskContainer.getDisplayId() != DEFAULT_DISPLAY) {
                        continue;
                    }
                    // TODO(b/238948678): Support reporting display features in all windowing modes.
                    if (taskContainer.isInMultiWindow()) {
                        continue;
                    }
                    if (taskContainer.isEmpty()) {
                        continue;
                    }
                    updateContainersInTask(wct, taskContainer);
                }
                // The WCT should be applied and merged to the device state change transition if
                // there is one.
                transactionRecord.apply(false /* shouldApplyIndependently */);
            }
        }
    }

    /** Updates the embedding rules applied to future activity launches. */
    @Override
    public void setEmbeddingRules(@NonNull Set<EmbeddingRule> rules) {
        synchronized (mLock) {
            mSplitRules.clear();
            mSplitRules.addAll(rules);
        }
    }

    @Override
    public void setSplitAttributesCalculator(
            @NonNull Function<SplitAttributesCalculatorParams, SplitAttributes> calculator) {
        synchronized (mLock) {
            mSplitAttributesCalculator = calculator;
        }
    }

    @Override
    public void clearSplitAttributesCalculator() {
        synchronized (mLock) {
            mSplitAttributesCalculator = null;
        }
    }

    @GuardedBy("mLock")
    @Nullable
    Function<SplitAttributesCalculatorParams, SplitAttributes> getSplitAttributesCalculator() {
        return mSplitAttributesCalculator;
    }

    @NonNull
    @GuardedBy("mLock")
    @VisibleForTesting
    List<EmbeddingRule> getSplitRules() {
        return mSplitRules;
    }

    /**
     * Registers the split organizer callback to notify about changes to active splits.
     * @deprecated Use {@link #setSplitInfoCallback(Consumer)} starting with
     * {@link WindowExtensionsImpl#getVendorApiLevel()} 2.
     */
    @Deprecated
    @Override
    public void setSplitInfoCallback(
            @NonNull java.util.function.Consumer<List<SplitInfo>> callback) {
        Consumer<List<SplitInfo>> oemConsumer = callback::accept;
        setSplitInfoCallback(oemConsumer);
    }

    /**
     * Registers the split organizer callback to notify about changes to active splits.
     * @since {@link WindowExtensionsImpl#getVendorApiLevel()} 2
     */
    public void setSplitInfoCallback(Consumer<List<SplitInfo>> callback) {
        synchronized (mLock) {
            mEmbeddingCallback = callback;
            updateCallbackIfNecessary();
        }
    }

    /**
     * Clears the listener set in {@link SplitController#setSplitInfoCallback(Consumer)}.
     */
    @Override
    public void clearSplitInfoCallback() {
        synchronized (mLock) {
            mEmbeddingCallback = null;
        }
    }

    /**
     * Called when the transaction is ready so that the organizer can update the TaskFragments based
     * on the changes in transaction.
     */
    @Override
    public void onTransactionReady(@NonNull TaskFragmentTransaction transaction) {
        synchronized (mLock) {
            final TransactionRecord transactionRecord = mTransactionManager.startNewTransaction(
                    transaction.getTransactionToken());
            final WindowContainerTransaction wct = transactionRecord.getTransaction();
            final List<TaskFragmentTransaction.Change> changes = transaction.getChanges();
            for (TaskFragmentTransaction.Change change : changes) {
                final int taskId = change.getTaskId();
                final TaskFragmentInfo info = change.getTaskFragmentInfo();
                switch (change.getType()) {
                    case TYPE_TASK_FRAGMENT_APPEARED:
                        mPresenter.updateTaskFragmentInfo(info);
                        onTaskFragmentAppeared(wct, info);
                        break;
                    case TYPE_TASK_FRAGMENT_INFO_CHANGED:
                        mPresenter.updateTaskFragmentInfo(info);
                        onTaskFragmentInfoChanged(wct, info);
                        break;
                    case TYPE_TASK_FRAGMENT_VANISHED:
                        mPresenter.removeTaskFragmentInfo(info);
                        onTaskFragmentVanished(wct, info);
                        break;
                    case TYPE_TASK_FRAGMENT_PARENT_INFO_CHANGED:
                        onTaskFragmentParentInfoChanged(wct, taskId,
                                change.getTaskFragmentParentInfo());
                        break;
                    case TYPE_TASK_FRAGMENT_ERROR:
                        final Bundle errorBundle = change.getErrorBundle();
                        final IBinder errorToken = change.getErrorCallbackToken();
                        final TaskFragmentInfo errorTaskFragmentInfo = errorBundle.getParcelable(
                                KEY_ERROR_CALLBACK_TASK_FRAGMENT_INFO, TaskFragmentInfo.class);
                        final int opType = errorBundle.getInt(KEY_ERROR_CALLBACK_OP_TYPE);
                        final Throwable exception = errorBundle.getSerializable(
                                KEY_ERROR_CALLBACK_THROWABLE, Throwable.class);
                        if (errorTaskFragmentInfo != null) {
                            mPresenter.updateTaskFragmentInfo(errorTaskFragmentInfo);
                        }
                        onTaskFragmentError(wct, errorToken, errorTaskFragmentInfo, opType,
                                exception);
                        break;
                    case TYPE_ACTIVITY_REPARENTED_TO_TASK:
                        onActivityReparentedToTask(
                                wct,
                                taskId,
                                change.getActivityIntent(),
                                change.getActivityToken());
                        break;
                    default:
                        throw new IllegalArgumentException(
                                "Unknown TaskFragmentEvent=" + change.getType());
                }
            }

            // Notify the server, and the server should apply and merge the
            // WindowContainerTransaction to the active sync to finish the TaskFragmentTransaction.
            transactionRecord.apply(false /* shouldApplyIndependently */);
            updateCallbackIfNecessary();
        }
    }

    /**
     * Called when a TaskFragment is created and organized by this organizer.
     *
     * @param wct   The {@link WindowContainerTransaction} to make any changes with if needed.
     * @param taskFragmentInfo  Info of the TaskFragment that is created.
     */
    // Suppress GuardedBy warning because lint ask to mark this method as
    // @GuardedBy(container.mController.mLock), which is mLock itself
    @SuppressWarnings("GuardedBy")
    @VisibleForTesting
    @GuardedBy("mLock")
    void onTaskFragmentAppeared(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentInfo taskFragmentInfo) {
        final TaskFragmentContainer container = getContainer(taskFragmentInfo.getFragmentToken());
        if (container == null) {
            return;
        }

        container.setInfo(wct, taskFragmentInfo);
        if (container.isFinished()) {
            mTransactionManager.getCurrentTransactionRecord().setOriginType(TRANSIT_CLOSE);
            mPresenter.cleanupContainer(wct, container, false /* shouldFinishDependent */);
        } else {
            // Update with the latest Task configuration.
            updateContainer(wct, container);
        }
    }

    /**
     * Called when the status of an organized TaskFragment is changed.
     *
     * @param wct   The {@link WindowContainerTransaction} to make any changes with if needed.
     * @param taskFragmentInfo  Info of the TaskFragment that is changed.
     */
    // Suppress GuardedBy warning because lint ask to mark this method as
    // @GuardedBy(container.mController.mLock), which is mLock itself
    @SuppressWarnings("GuardedBy")
    @VisibleForTesting
    @GuardedBy("mLock")
    void onTaskFragmentInfoChanged(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentInfo taskFragmentInfo) {
        final TaskFragmentContainer container = getContainer(taskFragmentInfo.getFragmentToken());
        if (container == null) {
            return;
        }

        final boolean wasInPip = isInPictureInPicture(container);
        container.setInfo(wct, taskFragmentInfo);
        final boolean isInPip = isInPictureInPicture(container);
        // Check if there are no running activities - consider the container empty if there are
        // no non-finishing activities left.
        if (!taskFragmentInfo.hasRunningActivity()) {
            if (taskFragmentInfo.isTaskFragmentClearedForPip()) {
                // Do not finish the dependents if the last activity is reparented to PiP.
                // Instead, the original split should be cleanup, and the dependent may be
                // expanded to fullscreen.
                mTransactionManager.getCurrentTransactionRecord().setOriginType(TRANSIT_CLOSE);
                cleanupForEnterPip(wct, container);
                mPresenter.cleanupContainer(wct, container, false /* shouldFinishDependent */);
            } else if (taskFragmentInfo.isTaskClearedForReuse()) {
                // Do not finish the dependents if this TaskFragment was cleared due to
                // launching activity in the Task.
                mTransactionManager.getCurrentTransactionRecord().setOriginType(TRANSIT_CLOSE);
                mPresenter.cleanupContainer(wct, container, false /* shouldFinishDependent */);
            } else if (taskFragmentInfo.isClearedForReorderActivityToFront()) {
                // Do not finish the dependents if this TaskFragment was cleared to reorder
                // the launching Activity to front of the Task.
                mPresenter.cleanupContainer(wct, container, false /* shouldFinishDependent */);
            } else if (!container.isWaitingActivityAppear()) {
                // Do not finish the container before the expected activity appear until
                // timeout.
                mTransactionManager.getCurrentTransactionRecord().setOriginType(TRANSIT_CLOSE);
                mPresenter.cleanupContainer(wct, container, true /* shouldFinishDependent */);
            }
        } else if (wasInPip && isInPip) {
            // No update until exit PIP.
            return;
        } else if (isInPip) {
            // Enter PIP.
            // All overrides will be cleanup.
            container.setLastRequestedBounds(null /* bounds */);
            container.setLastRequestedWindowingMode(WINDOWING_MODE_UNDEFINED);
            cleanupForEnterPip(wct, container);
        } else if (wasInPip) {
            // Exit PIP.
            // Updates the presentation of the container. Expand or launch placeholder if
            // needed.
            updateContainer(wct, container);
        }
    }

    /**
     * Called when an organized TaskFragment is removed.
     *
     * @param wct   The {@link WindowContainerTransaction} to make any changes with if needed.
     * @param taskFragmentInfo  Info of the TaskFragment that is removed.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    void onTaskFragmentVanished(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentInfo taskFragmentInfo) {
        final TaskFragmentContainer container = getContainer(taskFragmentInfo.getFragmentToken());
        if (container != null) {
            // Cleanup if the TaskFragment vanished is not requested by the organizer.
            removeContainer(container);
            // Make sure the containers in the Task are up-to-date.
            updateContainersInTaskIfVisible(wct, container.getTaskId());
        }
        cleanupTaskFragment(taskFragmentInfo.getFragmentToken());
    }

    /**
     * Called when the parent leaf Task of organized TaskFragments is changed.
     * When the leaf Task is changed, the organizer may want to update the TaskFragments in one
     * transaction.
     *
     * For case like screen size change, it will trigger {@link #onTaskFragmentParentInfoChanged}
     * with new Task bounds, but may not trigger {@link #onTaskFragmentInfoChanged} because there
     * can be an override bounds.
     *
     * @param wct   The {@link WindowContainerTransaction} to make any changes with if needed.
     * @param taskId    Id of the parent Task that is changed.
     * @param parentInfo  {@link TaskFragmentParentInfo} of the parent Task.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    void onTaskFragmentParentInfoChanged(@NonNull WindowContainerTransaction wct,
            int taskId, @NonNull TaskFragmentParentInfo parentInfo) {
        final TaskContainer taskContainer = getTaskContainer(taskId);
        if (taskContainer == null || taskContainer.isEmpty()) {
            Log.e(TAG, "onTaskFragmentParentInfoChanged on empty Task id=" + taskId);
            return;
        }
        taskContainer.updateTaskFragmentParentInfo(parentInfo);
        if (!taskContainer.isVisible()) {
            // Don't update containers if the task is not visible. We only update containers when
            // parentInfo#isVisibleRequested is true.
            return;
        }
        if (isInPictureInPicture(parentInfo.getConfiguration())) {
            // No need to update presentation in PIP until the Task exit PIP.
            return;
        }
        updateContainersInTask(wct, taskContainer);
    }

    @GuardedBy("mLock")
    void updateContainersInTaskIfVisible(@NonNull WindowContainerTransaction wct, int taskId) {
        final TaskContainer taskContainer = getTaskContainer(taskId);
        if (taskContainer != null && taskContainer.isVisible()) {
            updateContainersInTask(wct, taskContainer);
        }
    }

    @GuardedBy("mLock")
    private void updateContainersInTask(@NonNull WindowContainerTransaction wct,
            @NonNull TaskContainer taskContainer) {
        // Update all TaskFragments in the Task. Make a copy of the list since some may be
        // removed on updating.
        final List<TaskFragmentContainer> containers =
                new ArrayList<>(taskContainer.mContainers);
        for (int i = containers.size() - 1; i >= 0; i--) {
            final TaskFragmentContainer container = containers.get(i);
            // Wait until onTaskFragmentAppeared to update new container.
            if (!container.isFinished() && !container.isWaitingActivityAppear()) {
                updateContainer(wct, container);
            }
        }
    }

    /**
     * Called when an Activity is reparented to the Task with organized TaskFragment. For example,
     * when an Activity enters and then exits Picture-in-picture, it will be reparented back to its
     * original Task. In this case, we need to notify the organizer so that it can check if the
     * Activity matches any split rule.
     *
     * @param wct   The {@link WindowContainerTransaction} to make any changes with if needed.
     * @param taskId            The Task that the activity is reparented to.
     * @param activityIntent    The intent that the activity is original launched with.
     * @param activityToken     If the activity belongs to the same process as the organizer, this
     *                          will be the actual activity token; if the activity belongs to a
     *                          different process, the server will generate a temporary token that
     *                          the organizer can use to reparent the activity through
     *                          {@link WindowContainerTransaction} if needed.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    void onActivityReparentedToTask(@NonNull WindowContainerTransaction wct,
            int taskId, @NonNull Intent activityIntent,
            @NonNull IBinder activityToken) {
        // If the activity belongs to the current app process, we treat it as a new activity
        // launch.
        final Activity activity = getActivity(activityToken);
        if (activity != null) {
            // We don't allow split as primary for new launch because we currently only support
            // launching to top. We allow split as primary for activity reparent because the
            // activity may be split as primary before it is reparented out. In that case, we
            // want to show it as primary again when it is reparented back.
            if (!resolveActivityToContainer(wct, activity, true /* isOnReparent */)) {
                // When there is no embedding rule matched, try to place it in the top container
                // like a normal launch.
                placeActivityInTopContainer(wct, activity);
            }
            return;
        }

        final TaskContainer taskContainer = getTaskContainer(taskId);
        if (taskContainer == null || taskContainer.isInPictureInPicture()) {
            // We don't embed activity when it is in PIP.
            return;
        }

        // If the activity belongs to a different app process, we treat it as starting new
        // intent, since both actions might result in a new activity that should appear in an
        // organized TaskFragment.
        TaskFragmentContainer targetContainer = resolveStartActivityIntent(wct, taskId,
                activityIntent, null /* launchingActivity */);
        if (targetContainer == null) {
            // When there is no embedding rule matched, try to place it in the top container
            // like a normal launch.
            targetContainer = taskContainer.getTopTaskFragmentContainer();
        }
        if (targetContainer == null) {
            return;
        }
        wct.reparentActivityToTaskFragment(targetContainer.getTaskFragmentToken(),
                activityToken);
        // Because the activity does not belong to the organizer process, we wait until
        // onTaskFragmentAppeared to trigger updateCallbackIfNecessary().
    }

    /**
     * Called when the {@link WindowContainerTransaction} created with
     * {@link WindowContainerTransaction#setErrorCallbackToken(IBinder)} failed on the server side.
     *
     * @param wct   The {@link WindowContainerTransaction} to make any changes with if needed.
     * @param errorCallbackToken    token set in
     *                             {@link WindowContainerTransaction#setErrorCallbackToken(IBinder)}
     * @param taskFragmentInfo  The {@link TaskFragmentInfo}. This could be {@code null} if no
     *                          TaskFragment created.
     * @param opType            The {@link WindowContainerTransaction.HierarchyOp} of the failed
     *                          transaction operation.
     * @param exception             exception from the server side.
     */
    // Suppress GuardedBy warning because lint ask to mark this method as
    // @GuardedBy(container.mController.mLock), which is mLock itself
    @SuppressWarnings("GuardedBy")
    @VisibleForTesting
    @GuardedBy("mLock")
    void onTaskFragmentError(@NonNull WindowContainerTransaction wct,
            @Nullable IBinder errorCallbackToken, @Nullable TaskFragmentInfo taskFragmentInfo,
            int opType, @NonNull Throwable exception) {
        Log.e(TAG, "onTaskFragmentError=" + exception.getMessage());
        switch (opType) {
            case HIERARCHY_OP_TYPE_START_ACTIVITY_IN_TASK_FRAGMENT:
            case HIERARCHY_OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT: {
                final TaskFragmentContainer container;
                if (taskFragmentInfo != null) {
                    container = getContainer(taskFragmentInfo.getFragmentToken());
                } else {
                    container = null;
                }
                if (container == null) {
                    break;
                }

                // Update the latest taskFragmentInfo and perform necessary clean-up
                container.setInfo(wct, taskFragmentInfo);
                container.clearPendingAppearedActivities();
                if (container.isEmpty()) {
                    mTransactionManager.getCurrentTransactionRecord().setOriginType(TRANSIT_CLOSE);
                    mPresenter.cleanupContainer(wct, container, false /* shouldFinishDependent */);
                }
                break;
            }
            default:
                Log.e(TAG, "onTaskFragmentError: taskFragmentInfo = " + taskFragmentInfo
                        + ", opType = " + opType);
        }
    }

    /** Called on receiving {@link #onTaskFragmentVanished} for cleanup. */
    @GuardedBy("mLock")
    private void cleanupTaskFragment(@NonNull IBinder taskFragmentToken) {
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            final TaskContainer taskContainer = mTaskContainers.valueAt(i);
            if (!taskContainer.mFinishedContainer.remove(taskFragmentToken)) {
                continue;
            }
            if (taskContainer.isEmpty()) {
                // Cleanup the TaskContainer if it becomes empty.
                mTaskContainers.remove(taskContainer.getTaskId());
            }
            return;
        }
    }

    /** Returns whether the given {@link TaskContainer} may show in split. */
    // Suppress GuardedBy warning because lint asks to mark this method as
    // @GuardedBy(mPresenter.mController.mLock), which is mLock itself
    @SuppressWarnings("GuardedBy")
    @GuardedBy("mLock")
    private boolean mayShowSplit(@NonNull TaskContainer taskContainer) {
        // No split inside PIP.
        if (taskContainer.isInPictureInPicture()) {
            return false;
        }
        // Always assume the TaskContainer if SplitAttributesCalculator is set
        if (mSplitAttributesCalculator != null) {
            return true;
        }
        // Check if the parent container bounds can support any split rule.
        for (EmbeddingRule rule : mSplitRules) {
            if (!(rule instanceof SplitRule)) {
                continue;
            }
            final SplitRule splitRule = (SplitRule) rule;
            final SplitAttributes splitAttributes = mPresenter.computeSplitAttributes(
                    taskContainer.getTaskProperties(), splitRule, null /* minDimensionsPair */);
            if (shouldShowSplit(splitAttributes)) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    void onActivityCreated(@NonNull WindowContainerTransaction wct,
            @NonNull Activity launchedActivity) {
        // TODO(b/229680885): we don't support launching into primary yet because we want to always
        // launch the new activity on top.
        resolveActivityToContainer(wct, launchedActivity, false /* isOnReparent */);
        updateCallbackIfNecessary();
    }

    /**
     * Checks if the new added activity should be routed to a particular container. It can create a
     * new container for the activity and a new split container if necessary.
     * @param activity      the activity that is newly added to the Task.
     * @param isOnReparent  whether the activity is reparented to the Task instead of new launched.
     *                      We only support to split as primary for reparented activity for now.
     * @return {@code true} if the activity has been handled, such as placed in a TaskFragment, or
     *         in a state that the caller shouldn't handle.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    boolean resolveActivityToContainer(@NonNull WindowContainerTransaction wct,
            @NonNull Activity activity, boolean isOnReparent) {
        if (isInPictureInPicture(activity) || activity.isFinishing()) {
            // We don't embed activity when it is in PIP, or finishing. Return true since we don't
            // want any extra handling.
            return true;
        }

        if (!isOnReparent && getContainerWithActivity(activity) == null
                && getTaskFragmentTokenFromActivityClientRecord(activity) != null) {
            // We can't find the new launched activity in any recorded container, but it is
            // currently placed in an embedded TaskFragment. This can happen in two cases:
            // 1. the activity is embedded in another app.
            // 2. the organizer has already requested to remove the TaskFragment.
            // In either case, return true since we don't want any extra handling.
            Log.d(TAG, "Activity is in a TaskFragment that is not recorded by the organizer. r="
                    + activity);
            return true;
        }

        /*
         * We will check the following to see if there is any embedding rule matched:
         * 1. Whether the new launched activity should always expand.
         * 2. Whether the new launched activity should launch a placeholder.
         * 3. Whether the new launched activity has already been in a split with a rule matched
         *    (likely done in #onStartActivity).
         * 4. Whether the activity below (if any) should be split with the new launched activity.
         * 5. Whether the activity split with the activity below (if any) should be split with the
         *    new launched activity.
         */

        // 1. Whether the new launched activity should always expand.
        if (shouldExpand(activity, null /* intent */)) {
            expandActivity(wct, activity);
            return true;
        }

        // 2. Whether the new launched activity should launch a placeholder.
        if (launchPlaceholderIfNecessary(wct, activity, !isOnReparent)) {
            return true;
        }

        // 3. Whether the new launched activity has already been in a split with a rule matched.
        if (isNewActivityInSplitWithRuleMatched(activity)) {
            return true;
        }

        // 4. Whether the activity below (if any) should be split with the new launched activity.
        final Activity activityBelow = findActivityBelow(activity);
        if (activityBelow == null) {
            // Can't find any activity below.
            return false;
        }
        if (putActivitiesIntoSplitIfNecessary(wct, activityBelow, activity)) {
            // Have split rule of [ activityBelow | launchedActivity ].
            return true;
        }
        if (isOnReparent && putActivitiesIntoSplitIfNecessary(wct, activity, activityBelow)) {
            // Have split rule of [ launchedActivity | activityBelow].
            return true;
        }

        // 5. Whether the activity split with the activity below (if any) should be split with the
        //    new launched activity.
        final TaskFragmentContainer activityBelowContainer = getContainerWithActivity(
                activityBelow);
        final SplitContainer topSplit = getActiveSplitForContainer(activityBelowContainer);
        if (topSplit == null || !isTopMostSplit(topSplit)) {
            // Skip if it is not the topmost split.
            return false;
        }
        final TaskFragmentContainer otherTopContainer =
                topSplit.getPrimaryContainer() == activityBelowContainer
                        ? topSplit.getSecondaryContainer()
                        : topSplit.getPrimaryContainer();
        final Activity otherTopActivity = otherTopContainer.getTopNonFinishingActivity();
        if (otherTopActivity == null || otherTopActivity == activity) {
            // Can't find the top activity on the other split TaskFragment.
            return false;
        }
        if (putActivitiesIntoSplitIfNecessary(wct, otherTopActivity, activity)) {
            // Have split rule of [ otherTopActivity | launchedActivity ].
            return true;
        }
        // Have split rule of [ launchedActivity | otherTopActivity].
        return isOnReparent && putActivitiesIntoSplitIfNecessary(wct, activity, otherTopActivity);
    }

    /**
     * Places the given activity to the top most TaskFragment in the task if there is any.
     */
    @GuardedBy("mLock")
    @VisibleForTesting
    void placeActivityInTopContainer(@NonNull WindowContainerTransaction wct,
            @NonNull Activity activity) {
        if (getContainerWithActivity(activity) != null) {
            // The activity has already been put in a TaskFragment. This is likely to be done by
            // the server when the activity is started.
            return;
        }
        final int taskId = getTaskId(activity);
        final TaskContainer taskContainer = getTaskContainer(taskId);
        if (taskContainer == null) {
            return;
        }
        final TaskFragmentContainer targetContainer = taskContainer.getTopTaskFragmentContainer();
        if (targetContainer == null) {
            return;
        }
        targetContainer.addPendingAppearedActivity(activity);
        wct.reparentActivityToTaskFragment(targetContainer.getTaskFragmentToken(),
                activity.getActivityToken());
    }

    /**
     * Starts an activity to side of the launchingActivity with the provided split config.
     */
    // Suppress GuardedBy warning because lint ask to mark this method as
    // @GuardedBy(container.mController.mLock), which is mLock itself
    @SuppressWarnings("GuardedBy")
    @GuardedBy("mLock")
    private void startActivityToSide(@NonNull WindowContainerTransaction wct,
            @NonNull Activity launchingActivity, @NonNull Intent intent,
            @Nullable Bundle options, @NonNull SplitRule sideRule,
            @NonNull SplitAttributes splitAttributes, @Nullable Consumer<Exception> failureCallback,
            boolean isPlaceholder) {
        try {
            mPresenter.startActivityToSide(wct, launchingActivity, intent, options, sideRule,
                    splitAttributes, isPlaceholder);
        } catch (Exception e) {
            if (failureCallback != null) {
                failureCallback.accept(e);
            }
        }
    }

    /**
     * Expands the given activity by either expanding the TaskFragment it is currently in or putting
     * it into a new expanded TaskFragment.
     */
    @GuardedBy("mLock")
    private void expandActivity(@NonNull WindowContainerTransaction wct,
            @NonNull Activity activity) {
        final TaskFragmentContainer container = getContainerWithActivity(activity);
        if (shouldContainerBeExpanded(container)) {
            // Make sure that the existing container is expanded.
            mPresenter.expandTaskFragment(wct, container.getTaskFragmentToken());
        } else {
            // Put activity into a new expanded container.
            final TaskFragmentContainer newContainer = newContainer(activity, getTaskId(activity));
            mPresenter.expandActivity(wct, newContainer.getTaskFragmentToken(), activity);
        }
    }

    /** Whether the given new launched activity is in a split with a rule matched. */
    // Suppress GuardedBy warning because lint asks to mark this method as
    // @GuardedBy(mPresenter.mController.mLock), which is mLock itself
    @SuppressWarnings("GuardedBy")
    @GuardedBy("mLock")
    private boolean isNewActivityInSplitWithRuleMatched(@NonNull Activity launchedActivity) {
        final TaskFragmentContainer container = getContainerWithActivity(launchedActivity);
        final SplitContainer splitContainer = getActiveSplitForContainer(container);
        if (splitContainer == null) {
            return false;
        }

        if (container == splitContainer.getPrimaryContainer()) {
            // The new launched can be in the primary container when it is starting a new activity
            // onCreate.
            final TaskFragmentContainer secondaryContainer = splitContainer.getSecondaryContainer();
            final Intent secondaryIntent = secondaryContainer.getPendingAppearedIntent();
            if (secondaryIntent != null) {
                // Check with the pending Intent before it is started on the server side.
                // This can happen if the launched Activity start a new Intent to secondary during
                // #onCreated().
                return getSplitRule(launchedActivity, secondaryIntent) != null;
            }
            final Activity secondaryActivity = secondaryContainer.getTopNonFinishingActivity();
            return secondaryActivity != null
                    && getSplitRule(launchedActivity, secondaryActivity) != null;
        }

        // Check if the new launched activity is a placeholder.
        if (splitContainer.getSplitRule() instanceof SplitPlaceholderRule) {
            final SplitPlaceholderRule placeholderRule =
                    (SplitPlaceholderRule) splitContainer.getSplitRule();
            final ComponentName placeholderName = placeholderRule.getPlaceholderIntent()
                    .getComponent();
            // TODO(b/232330767): Do we have a better way to check this?
            return placeholderName == null
                    || placeholderName.equals(launchedActivity.getComponentName())
                    || placeholderRule.getPlaceholderIntent().equals(launchedActivity.getIntent());
        }

        // Check if the new launched activity should be split with the primary top activity.
        final Activity primaryActivity = splitContainer.getPrimaryContainer()
                .getTopNonFinishingActivity();
        if (primaryActivity == null) {
            return false;
        }
        /* TODO(b/231845476) we should always respect clearTop.
        final SplitPairRule curSplitRule = (SplitPairRule) splitContainer.getSplitRule();
        final SplitPairRule splitRule = getSplitRule(primaryActivity, launchedActivity);
        return splitRule != null && haveSamePresentation(splitRule, curSplitRule)
                // If the new launched split rule should clear top and it is not the bottom most,
                // it means we should create a new split pair and clear the existing secondary.
                && (!splitRule.shouldClearTop()
                || container.getBottomMostActivity() == launchedActivity);
         */
        return getSplitRule(primaryActivity, launchedActivity) != null;
    }

    /** Finds the activity below the given activity. */
    @VisibleForTesting
    @Nullable
    @GuardedBy("mLock")
    Activity findActivityBelow(@NonNull Activity activity) {
        Activity activityBelow = null;
        final TaskFragmentContainer container = getContainerWithActivity(activity);
        if (container != null) {
            final List<Activity> containerActivities = container.collectNonFinishingActivities();
            final int index = containerActivities.indexOf(activity);
            if (index > 0) {
                activityBelow = containerActivities.get(index - 1);
            }
        }
        if (activityBelow == null) {
            final IBinder belowToken = ActivityClient.getInstance().getActivityTokenBelow(
                    activity.getActivityToken());
            if (belowToken != null) {
                activityBelow = getActivity(belowToken);
            }
        }
        return activityBelow;
    }

    /**
     * Checks if there is a rule to split the two activities. If there is one, puts them into split
     * and returns {@code true}. Otherwise, returns {@code false}.
     */
    // Suppress GuardedBy warning because lint ask to mark this method as
    // @GuardedBy(mPresenter.mController.mLock), which is mLock itself
    @SuppressWarnings("GuardedBy")
    @GuardedBy("mLock")
    private boolean putActivitiesIntoSplitIfNecessary(@NonNull WindowContainerTransaction wct,
            @NonNull Activity primaryActivity, @NonNull Activity secondaryActivity) {
        final SplitPairRule splitRule = getSplitRule(primaryActivity, secondaryActivity);
        if (splitRule == null) {
            return false;
        }
        final TaskFragmentContainer primaryContainer = getContainerWithActivity(
                primaryActivity);
        final SplitContainer splitContainer = getActiveSplitForContainer(primaryContainer);
        final WindowMetrics taskWindowMetrics = mPresenter.getTaskWindowMetrics(primaryActivity);
        if (splitContainer != null && primaryContainer == splitContainer.getPrimaryContainer()
                && canReuseContainer(splitRule, splitContainer.getSplitRule(), taskWindowMetrics)) {
            // Can launch in the existing secondary container if the rules share the same
            // presentation.
            final TaskFragmentContainer secondaryContainer = splitContainer.getSecondaryContainer();
            if (secondaryContainer == getContainerWithActivity(secondaryActivity)) {
                // The activity is already in the target TaskFragment.
                return true;
            }
            secondaryContainer.addPendingAppearedActivity(secondaryActivity);
            if (mPresenter.expandSplitContainerIfNeeded(wct, splitContainer, primaryActivity,
                    secondaryActivity, null /* secondaryIntent */)
                    != RESULT_EXPAND_FAILED_NO_TF_INFO) {
                wct.reparentActivityToTaskFragment(
                        secondaryContainer.getTaskFragmentToken(),
                        secondaryActivity.getActivityToken());
                return true;
            }
        }
        // Create new split pair.
        mPresenter.createNewSplitContainer(wct, primaryActivity, secondaryActivity, splitRule);
        return true;
    }

    @GuardedBy("mLock")
    private void onActivityConfigurationChanged(@NonNull WindowContainerTransaction wct,
            @NonNull Activity activity) {
        if (activity.isFinishing()) {
            // Do nothing if the activity is currently finishing.
            return;
        }

        if (isInPictureInPicture(activity)) {
            // We don't embed activity when it is in PIP.
            return;
        }
        final TaskFragmentContainer currentContainer = getContainerWithActivity(activity);

        if (currentContainer != null) {
            // Changes to activities in controllers are handled in
            // onTaskFragmentParentInfoChanged
            return;
        }

        // Check if activity requires a placeholder
        launchPlaceholderIfNecessary(wct, activity, false /* isOnCreated */);
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    void onActivityDestroyed(@NonNull Activity activity) {
        if (!activity.isFinishing()) {
            // onDestroyed is triggered without finishing. This happens when the activity is
            // relaunched. In this case, we don't want to cleanup the record.
            return;
        }
        // Remove any pending appeared activity, as the server won't send finished activity to the
        // organizer.
        final IBinder activityToken = activity.getActivityToken();
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            mTaskContainers.valueAt(i).onActivityDestroyed(activityToken);
        }
        // We didn't trigger the callback if there were any pending appeared activities, so check
        // again after the pending is removed.
        updateCallbackIfNecessary();
    }

    /**
     * Called when we have been waiting too long for the TaskFragment to become non-empty after
     * creation.
     */
    @GuardedBy("mLock")
    void onTaskFragmentAppearEmptyTimeout(@NonNull TaskFragmentContainer container) {
        final TransactionRecord transactionRecord = mTransactionManager.startNewTransaction();
        onTaskFragmentAppearEmptyTimeout(transactionRecord.getTransaction(), container);
        // Can be applied independently as a timeout callback.
        transactionRecord.apply(true /* shouldApplyIndependently */);
    }

    /**
     * Called when we have been waiting too long for the TaskFragment to become non-empty after
     * creation.
     */
    @GuardedBy("mLock")
    void onTaskFragmentAppearEmptyTimeout(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer container) {
        mTransactionManager.getCurrentTransactionRecord().setOriginType(TRANSIT_CLOSE);
        mPresenter.cleanupContainer(wct, container, false /* shouldFinishDependent */);
    }

    @Nullable
    @GuardedBy("mLock")
    private TaskFragmentContainer resolveStartActivityIntentFromNonActivityContext(
            @NonNull WindowContainerTransaction wct, @NonNull Intent intent) {
        final int taskCount = mTaskContainers.size();
        if (taskCount == 0) {
            // We don't have other Activity to check split with.
            return null;
        }
        if (taskCount > 1) {
            Log.w(TAG, "App is calling startActivity from a non-Activity context when it has"
                    + " more than one Task. If the new launch Activity is in a different process,"
                    + " and it is expected to be embedded, please start it from an Activity"
                    + " instead.");
            return null;
        }

        // Check whether the Intent should be embedded in the known Task.
        final TaskContainer taskContainer = mTaskContainers.valueAt(0);
        if (taskContainer.isInPictureInPicture()
                || taskContainer.getTopNonFinishingActivity() == null) {
            // We don't embed activity when it is in PIP, or if we can't find any other owner
            // activity in the Task.
            return null;
        }

        return resolveStartActivityIntent(wct, taskContainer.getTaskId(), intent,
                null /* launchingActivity */);
    }

    /**
     * When we are trying to handle a new activity Intent, returns the {@link TaskFragmentContainer}
     * that we should reparent the new activity to if there is any embedding rule matched.
     *
     * @param wct               {@link WindowContainerTransaction} including all the window change
     *                          requests. The caller is responsible to call
     *                          {@link android.window.TaskFragmentOrganizer#applyTransaction}.
     * @param taskId            The Task to start the activity in.
     * @param intent            The {@link Intent} for starting the new launched activity.
     * @param launchingActivity The {@link Activity} that starts the new activity. We will
     *                          prioritize to split the new activity with it if it is not
     *                          {@code null}.
     * @return the {@link TaskFragmentContainer} to start the new activity in. {@code null} if there
     *         is no embedding rule matched.
     */
    @VisibleForTesting
    @Nullable
    @GuardedBy("mLock")
    TaskFragmentContainer resolveStartActivityIntent(@NonNull WindowContainerTransaction wct,
            int taskId, @NonNull Intent intent, @Nullable Activity launchingActivity) {
        /*
         * We will check the following to see if there is any embedding rule matched:
         * 1. Whether the new activity intent should always expand.
         * 2. Whether the launching activity (if set) should be split with the new activity intent.
         * 3. Whether the top activity (if any) should be split with the new activity intent.
         * 4. Whether the top activity (if any) in other split should be split with the new
         *    activity intent.
         */

        // 1. Whether the new activity intent should always expand.
        if (shouldExpand(null /* activity */, intent)) {
            return createEmptyExpandedContainer(wct, intent, taskId, launchingActivity);
        }

        // 2. Whether the launching activity (if set) should be split with the new activity intent.
        if (launchingActivity != null) {
            final TaskFragmentContainer container = getSecondaryContainerForSplitIfAny(wct,
                    launchingActivity, intent, true /* respectClearTop */);
            if (container != null) {
                return container;
            }
        }

        // 3. Whether the top activity (if any) should be split with the new activity intent.
        final TaskContainer taskContainer = getTaskContainer(taskId);
        if (taskContainer == null || taskContainer.getTopTaskFragmentContainer() == null) {
            // There is no other activity in the Task to check split with.
            return null;
        }
        final TaskFragmentContainer topContainer = taskContainer.getTopTaskFragmentContainer();
        final Activity topActivity = topContainer.getTopNonFinishingActivity();
        if (topActivity != null && topActivity != launchingActivity) {
            final TaskFragmentContainer container = getSecondaryContainerForSplitIfAny(wct,
                    topActivity, intent, false /* respectClearTop */);
            if (container != null) {
                return container;
            }
        }

        // 4. Whether the top activity (if any) in other split should be split with the new
        //    activity intent.
        final SplitContainer topSplit = getActiveSplitForContainer(topContainer);
        if (topSplit == null) {
            return null;
        }
        final TaskFragmentContainer otherTopContainer =
                topSplit.getPrimaryContainer() == topContainer
                        ? topSplit.getSecondaryContainer()
                        : topSplit.getPrimaryContainer();
        final Activity otherTopActivity = otherTopContainer.getTopNonFinishingActivity();
        if (otherTopActivity != null && otherTopActivity != launchingActivity) {
            return getSecondaryContainerForSplitIfAny(wct, otherTopActivity, intent,
                    false /* respectClearTop */);
        }
        return null;
    }

    /**
     * Returns an empty expanded {@link TaskFragmentContainer} that we can launch an activity into.
     */
    @GuardedBy("mLock")
    @Nullable
    private TaskFragmentContainer createEmptyExpandedContainer(
            @NonNull WindowContainerTransaction wct, @NonNull Intent intent, int taskId,
            @Nullable Activity launchingActivity) {
        // We need an activity in the organizer process in the same Task to use as the owner
        // activity, as well as to get the Task window info.
        final Activity activityInTask;
        if (launchingActivity != null) {
            activityInTask = launchingActivity;
        } else {
            final TaskContainer taskContainer = getTaskContainer(taskId);
            activityInTask = taskContainer != null
                    ? taskContainer.getTopNonFinishingActivity()
                    : null;
        }
        if (activityInTask == null) {
            // Can't find any activity in the Task that we can use as the owner activity.
            return null;
        }
        final TaskFragmentContainer expandedContainer = newContainer(intent, activityInTask,
                taskId);
        mPresenter.createTaskFragment(wct, expandedContainer.getTaskFragmentToken(),
                activityInTask.getActivityToken(), new Rect(), WINDOWING_MODE_UNDEFINED);
        mPresenter.updateAnimationParams(wct, expandedContainer.getTaskFragmentToken(),
                TaskFragmentAnimationParams.DEFAULT);
        return expandedContainer;
    }

    /**
     * Returns a container for the new activity intent to launch into as splitting with the primary
     * activity.
     */
    @GuardedBy("mLock")
    @Nullable
    private TaskFragmentContainer getSecondaryContainerForSplitIfAny(
            @NonNull WindowContainerTransaction wct, @NonNull Activity primaryActivity,
            @NonNull Intent intent, boolean respectClearTop) {
        final SplitPairRule splitRule = getSplitRule(primaryActivity, intent);
        if (splitRule == null) {
            return null;
        }
        final TaskFragmentContainer existingContainer = getContainerWithActivity(primaryActivity);
        final SplitContainer splitContainer = getActiveSplitForContainer(existingContainer);
        final WindowMetrics taskWindowMetrics = mPresenter.getTaskWindowMetrics(primaryActivity);
        if (splitContainer != null && existingContainer == splitContainer.getPrimaryContainer()
                && (canReuseContainer(splitRule, splitContainer.getSplitRule(), taskWindowMetrics)
                // TODO(b/231845476) we should always respect clearTop.
                || !respectClearTop)
                && mPresenter.expandSplitContainerIfNeeded(wct, splitContainer, primaryActivity,
                        null /* secondaryActivity */, intent) != RESULT_EXPAND_FAILED_NO_TF_INFO) {
            // Can launch in the existing secondary container if the rules share the same
            // presentation.
            return splitContainer.getSecondaryContainer();
        }
        // Create a new TaskFragment to split with the primary activity for the new activity.
        return mPresenter.createNewSplitWithEmptySideContainer(wct, primaryActivity, intent,
                splitRule);
    }

    /**
     * Returns a container that this activity is registered with. An activity can only belong to one
     * container, or no container at all.
     */
    @GuardedBy("mLock")
    @Nullable
    TaskFragmentContainer getContainerWithActivity(@NonNull Activity activity) {
        return getContainerWithActivity(activity.getActivityToken());
    }

    @GuardedBy("mLock")
    @Nullable
    TaskFragmentContainer getContainerWithActivity(@NonNull IBinder activityToken) {
        // Check pending appeared activity first because there can be a delay for the server
        // update.
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            final List<TaskFragmentContainer> containers = mTaskContainers.valueAt(i).mContainers;
            for (int j = containers.size() - 1; j >= 0; j--) {
                final TaskFragmentContainer container = containers.get(j);
                if (container.hasPendingAppearedActivity(activityToken)) {
                    return container;
                }
            }
        }

        // Check appeared activity if there is no such pending appeared activity.
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            final List<TaskFragmentContainer> containers = mTaskContainers.valueAt(i).mContainers;
            for (int j = containers.size() - 1; j >= 0; j--) {
                final TaskFragmentContainer container = containers.get(j);
                if (container.hasAppearedActivity(activityToken)) {
                    return container;
                }
            }
        }
        return null;
    }

    @GuardedBy("mLock")
    TaskFragmentContainer newContainer(@NonNull Activity pendingAppearedActivity, int taskId) {
        return newContainer(pendingAppearedActivity, pendingAppearedActivity, taskId);
    }

    @GuardedBy("mLock")
    TaskFragmentContainer newContainer(@NonNull Activity pendingAppearedActivity,
            @NonNull Activity activityInTask, int taskId) {
        return newContainer(pendingAppearedActivity, null /* pendingAppearedIntent */,
                activityInTask, taskId, null /* pairedPrimaryContainer */);
    }

    @GuardedBy("mLock")
    TaskFragmentContainer newContainer(@NonNull Intent pendingAppearedIntent,
            @NonNull Activity activityInTask, int taskId) {
        return newContainer(null /* pendingAppearedActivity */, pendingAppearedIntent,
                activityInTask, taskId, null /* pairedPrimaryContainer */);
    }

    /**
     * Creates and registers a new organized container with an optional activity that will be
     * re-parented to it in a WCT.
     *
     * @param pendingAppearedActivity   the activity that will be reparented to the TaskFragment.
     * @param pendingAppearedIntent     the Intent that will be started in the TaskFragment.
     * @param activityInTask            activity in the same Task so that we can get the Task bounds
     *                                  if needed.
     * @param taskId                    parent Task of the new TaskFragment.
     * @param pairedPrimaryContainer    the paired primary {@link TaskFragmentContainer}. When it is
     *                                  set, the new container will be added right above it.
     */
    @GuardedBy("mLock")
    TaskFragmentContainer newContainer(@Nullable Activity pendingAppearedActivity,
            @Nullable Intent pendingAppearedIntent, @NonNull Activity activityInTask, int taskId,
            @Nullable TaskFragmentContainer pairedPrimaryContainer) {
        if (activityInTask == null) {
            throw new IllegalArgumentException("activityInTask must not be null,");
        }
        if (!mTaskContainers.contains(taskId)) {
            mTaskContainers.put(taskId, new TaskContainer(taskId, activityInTask));
        }
        final TaskContainer taskContainer = mTaskContainers.get(taskId);
        final TaskFragmentContainer container = new TaskFragmentContainer(pendingAppearedActivity,
                pendingAppearedIntent, taskContainer, this, pairedPrimaryContainer);
        return container;
    }

    /**
     * Creates and registers a new split with the provided containers and configuration. Finishes
     * existing secondary containers if found for the given primary container.
     */
    // Suppress GuardedBy warning because lint ask to mark this method as
    // @GuardedBy(mPresenter.mController.mLock), which is mLock itself
    @SuppressWarnings("GuardedBy")
    @GuardedBy("mLock")
    void registerSplit(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer primaryContainer, @NonNull Activity primaryActivity,
            @NonNull TaskFragmentContainer secondaryContainer,
            @NonNull SplitRule splitRule, @NonNull SplitAttributes splitAttributes) {
        final SplitContainer splitContainer = new SplitContainer(primaryContainer, primaryActivity,
                secondaryContainer, splitRule, splitAttributes);
        // Remove container later to prevent pinning escaping toast showing in lock task mode.
        if (splitRule instanceof SplitPairRule && ((SplitPairRule) splitRule).shouldClearTop()) {
            removeExistingSecondaryContainers(wct, primaryContainer);
        }
        primaryContainer.getTaskContainer().mSplitContainers.add(splitContainer);
    }

    /** Cleanups all the dependencies when the TaskFragment is entering PIP. */
    @GuardedBy("mLock")
    private void cleanupForEnterPip(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer container) {
        final TaskContainer taskContainer = container.getTaskContainer();
        if (taskContainer == null) {
            return;
        }
        final List<SplitContainer> splitsToRemove = new ArrayList<>();
        final Set<TaskFragmentContainer> containersToUpdate = new ArraySet<>();
        for (SplitContainer splitContainer : taskContainer.mSplitContainers) {
            if (splitContainer.getPrimaryContainer() != container
                    && splitContainer.getSecondaryContainer() != container) {
                continue;
            }
            splitsToRemove.add(splitContainer);
            final TaskFragmentContainer splitTf = splitContainer.getPrimaryContainer() == container
                    ? splitContainer.getSecondaryContainer()
                    : splitContainer.getPrimaryContainer();
            containersToUpdate.add(splitTf);
            // We don't want the PIP TaskFragment to be removed as a result of any of its dependents
            // being removed.
            splitTf.removeContainerToFinishOnExit(container);
            if (container.getTopNonFinishingActivity() != null) {
                splitTf.removeActivityToFinishOnExit(container.getTopNonFinishingActivity());
            }
        }
        container.resetDependencies();
        taskContainer.mSplitContainers.removeAll(splitsToRemove);
        // If there is any TaskFragment split with the PIP TaskFragment, update their presentations
        // since the split is dismissed.
        // We don't want to close any of them even if they are dependencies of the PIP TaskFragment.
        for (TaskFragmentContainer containerToUpdate : containersToUpdate) {
            updateContainer(wct, containerToUpdate);
        }
    }

    /**
     * Removes the container from bookkeeping records.
     */
    void removeContainer(@NonNull TaskFragmentContainer container) {
        // Remove all split containers that included this one
        final TaskContainer taskContainer = container.getTaskContainer();
        taskContainer.mContainers.remove(container);
        // Marked as a pending removal which will be removed after it is actually removed on the
        // server side (#onTaskFragmentVanished).
        // In this way, we can keep track of the Task bounds until we no longer have any
        // TaskFragment there.
        taskContainer.mFinishedContainer.add(container.getTaskFragmentToken());

        // Cleanup any split references.
        final List<SplitContainer> containersToRemove = new ArrayList<>();
        for (SplitContainer splitContainer : taskContainer.mSplitContainers) {
            if (container.equals(splitContainer.getSecondaryContainer())
                    || container.equals(splitContainer.getPrimaryContainer())) {
                containersToRemove.add(splitContainer);
            }
        }
        taskContainer.mSplitContainers.removeAll(containersToRemove);

        // Cleanup any dependent references.
        for (TaskFragmentContainer containerToUpdate : taskContainer.mContainers) {
            containerToUpdate.removeContainerToFinishOnExit(container);
        }
    }

    /**
     * Removes a secondary container for the given primary container if an existing split is
     * already registered.
     */
    // Suppress GuardedBy warning because lint asks to mark this method as
    // @GuardedBy(existingSplitContainer.getSecondaryContainer().mController.mLock), which is mLock
    // itself
    @SuppressWarnings("GuardedBy")
    @GuardedBy("mLock")
    private void removeExistingSecondaryContainers(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer primaryContainer) {
        // If the primary container was already in a split - remove the secondary container that
        // is now covered by the new one that replaced it.
        final SplitContainer existingSplitContainer = getActiveSplitForContainer(
                primaryContainer);
        if (existingSplitContainer == null
                || primaryContainer == existingSplitContainer.getSecondaryContainer()) {
            return;
        }

        existingSplitContainer.getSecondaryContainer().finish(
                false /* shouldFinishDependent */, mPresenter, wct, this);
    }

    /**
     * Returns the topmost not finished container in Task of given task id.
     */
    @GuardedBy("mLock")
    @Nullable
    TaskFragmentContainer getTopActiveContainer(int taskId) {
        final TaskContainer taskContainer = mTaskContainers.get(taskId);
        if (taskContainer == null) {
            return null;
        }
        for (int i = taskContainer.mContainers.size() - 1; i >= 0; i--) {
            final TaskFragmentContainer container = taskContainer.mContainers.get(i);
            if (!container.isFinished() && (container.getRunningActivityCount() > 0
                    // We may be waiting for the top TaskFragment to become non-empty after
                    // creation. In that case, we don't want to treat the TaskFragment below it as
                    // top active, otherwise it may incorrectly launch placeholder on top of the
                    // pending TaskFragment.
                    || container.isWaitingActivityAppear())) {
                return container;
            }
        }
        return null;
    }

    /**
     * Updates the presentation of the container. If the container is part of the split or should
     * have a placeholder, it will also update the other part of the split.
     */
    @GuardedBy("mLock")
    void updateContainer(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer container) {
        if (!container.getTaskContainer().isVisible()) {
            // Wait until the Task is visible to avoid unnecessary update when the Task is still in
            // background.
            return;
        }
        if (launchPlaceholderIfNecessary(wct, container)) {
            // Placeholder was launched, the positions will be updated when the activity is added
            // to the secondary container.
            return;
        }
        if (shouldContainerBeExpanded(container)) {
            if (container.getInfo() != null) {
                mPresenter.expandTaskFragment(wct, container.getTaskFragmentToken());
            }
            // If the info is not available yet the task fragment will be expanded when it's ready
            return;
        }
        SplitContainer splitContainer = getActiveSplitForContainer(container);
        if (splitContainer == null) {
            return;
        }
        if (!isTopMostSplit(splitContainer)) {
            // Skip position update - it isn't the topmost split.
            return;
        }
        if (splitContainer.getPrimaryContainer().isFinished()
                || splitContainer.getSecondaryContainer().isFinished()) {
            // Skip position update - one or both containers are finished.
            return;
        }
        final TaskContainer taskContainer = splitContainer.getTaskContainer();
        final SplitRule splitRule = splitContainer.getSplitRule();
        final Pair<Size, Size> minDimensionsPair = splitContainer.getMinDimensionsPair();
        final SplitAttributes splitAttributes = mPresenter.computeSplitAttributes(
                taskContainer.getTaskProperties(), splitRule, minDimensionsPair);
        splitContainer.setSplitAttributes(splitAttributes);
        if (dismissPlaceholderIfNecessary(wct, splitContainer)) {
            // Placeholder was finished, the positions will be updated when its container is emptied
            return;
        }
        mPresenter.updateSplitContainer(splitContainer, container, wct);
    }

    /** Whether the given split is the topmost split in the Task. */
    private boolean isTopMostSplit(@NonNull SplitContainer splitContainer) {
        final List<SplitContainer> splitContainers = splitContainer.getPrimaryContainer()
                .getTaskContainer().mSplitContainers;
        return splitContainer == splitContainers.get(splitContainers.size() - 1);
    }

    /**
     * Returns the top active split container that has the provided container, if available.
     */
    @Nullable
    private SplitContainer getActiveSplitForContainer(@Nullable TaskFragmentContainer container) {
        if (container == null) {
            return null;
        }
        final List<SplitContainer> splitContainers = container.getTaskContainer().mSplitContainers;
        if (splitContainers.isEmpty()) {
            return null;
        }
        for (int i = splitContainers.size() - 1; i >= 0; i--) {
            final SplitContainer splitContainer = splitContainers.get(i);
            if (container.equals(splitContainer.getSecondaryContainer())
                    || container.equals(splitContainer.getPrimaryContainer())) {
                return splitContainer;
            }
        }
        return null;
    }

    /**
     * Returns the active split that has the provided containers as primary and secondary or as
     * secondary and primary, if available.
     */
    @VisibleForTesting
    @Nullable
    SplitContainer getActiveSplitForContainers(
            @NonNull TaskFragmentContainer firstContainer,
            @NonNull TaskFragmentContainer secondContainer) {
        final List<SplitContainer> splitContainers = firstContainer.getTaskContainer()
                .mSplitContainers;
        for (int i = splitContainers.size() - 1; i >= 0; i--) {
            final SplitContainer splitContainer = splitContainers.get(i);
            final TaskFragmentContainer primary = splitContainer.getPrimaryContainer();
            final TaskFragmentContainer secondary = splitContainer.getSecondaryContainer();
            if ((firstContainer == secondary && secondContainer == primary)
                    || (firstContainer == primary && secondContainer == secondary)) {
                return splitContainer;
            }
        }
        return null;
    }

    /**
     * Checks if the container requires a placeholder and launches it if necessary.
     */
    @GuardedBy("mLock")
    private boolean launchPlaceholderIfNecessary(@NonNull WindowContainerTransaction wct,
            @NonNull TaskFragmentContainer container) {
        final Activity topActivity = container.getTopNonFinishingActivity();
        if (topActivity == null) {
            return false;
        }

        return launchPlaceholderIfNecessary(wct, topActivity, false /* isOnCreated */);
    }

    // Suppress GuardedBy warning because lint ask to mark this method as
    // @GuardedBy(mPresenter.mController.mLock), which is mLock itself
    @SuppressWarnings("GuardedBy")
    @GuardedBy("mLock")
    boolean launchPlaceholderIfNecessary(@NonNull WindowContainerTransaction wct,
            @NonNull Activity activity, boolean isOnCreated) {
        if (activity.isFinishing()) {
            return false;
        }

        final TaskFragmentContainer container = getContainerWithActivity(activity);
        if (container != null && !allowLaunchPlaceholder(container)) {
            // We don't allow activity in this TaskFragment to launch placeholder.
            return false;
        }

        // Check if there is enough space for launch
        final SplitPlaceholderRule placeholderRule = getPlaceholderRule(activity);

        if (placeholderRule == null) {
            return false;
        }

        final TaskContainer.TaskProperties taskProperties = mPresenter.getTaskProperties(activity);
        final Pair<Size, Size> minDimensionsPair = getActivityIntentMinDimensionsPair(activity,
                placeholderRule.getPlaceholderIntent());
        final SplitAttributes splitAttributes = mPresenter.computeSplitAttributes(taskProperties,
                placeholderRule, minDimensionsPair);
        if (!SplitPresenter.shouldShowSplit(splitAttributes)) {
            return false;
        }

        // TODO(b/190433398): Handle failed request
        final Bundle options = getPlaceholderOptions(activity, isOnCreated);
        startActivityToSide(wct, activity, placeholderRule.getPlaceholderIntent(), options,
                placeholderRule, splitAttributes, null /* failureCallback */,
                true /* isPlaceholder */);
        return true;
    }

    /** Whether or not to allow activity in this container to launch placeholder. */
    @GuardedBy("mLock")
    private boolean allowLaunchPlaceholder(@NonNull TaskFragmentContainer container) {
        final TaskFragmentContainer topContainer = getTopActiveContainer(container.getTaskId());
        if (container != topContainer) {
            // The container is not the top most.
            if (!container.isVisible()) {
                // In case the container is visible (the one on top may be transparent), we may
                // still want to launch placeholder even if it is not the top most.
                return false;
            }
            if (topContainer.isWaitingActivityAppear()) {
                // When the top container appeared info is not sent by the server yet, the visible
                // check above may not be reliable.
                return false;
            }
        }

        final SplitContainer splitContainer = getActiveSplitForContainer(container);
        if (splitContainer != null && container.equals(splitContainer.getPrimaryContainer())) {
            // Don't launch placeholder for primary split container.
            return false;
        }
        return true;
    }

    /**
     * Gets the activity options for starting the placeholder activity. In case the placeholder is
     * launched when the Task is in the background, we don't want to bring the Task to the front.
     * @param primaryActivity   the primary activity to launch the placeholder from.
     * @param isOnCreated       whether this happens during the primary activity onCreated.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    @Nullable
    Bundle getPlaceholderOptions(@NonNull Activity primaryActivity, boolean isOnCreated) {
        // Setting avoid move to front will also skip the animation. We only want to do that when
        // the Task is currently in background.
        // Check if the primary is resumed or if this is called when the primary is onCreated
        // (not resumed yet).
        if (isOnCreated || primaryActivity.isResumed()) {
            // Only set trigger type if the launch happens in foreground.
            mTransactionManager.getCurrentTransactionRecord().setOriginType(TRANSIT_OPEN);
            return null;
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setAvoidMoveToFront();
        return options.toBundle();
    }

    // Suppress GuardedBy warning because lint ask to mark this method as
    // @GuardedBy(mPresenter.mController.mLock), which is mLock itself
    @SuppressWarnings("GuardedBy")
    @VisibleForTesting
    @GuardedBy("mLock")
    boolean dismissPlaceholderIfNecessary(@NonNull WindowContainerTransaction wct,
            @NonNull SplitContainer splitContainer) {
        if (!splitContainer.isPlaceholderContainer()) {
            return false;
        }

        if (isStickyPlaceholderRule(splitContainer.getSplitRule())) {
            // The placeholder should remain after it was first shown.
            return false;
        }
        final SplitAttributes splitAttributes = splitContainer.getSplitAttributes();
        if (SplitPresenter.shouldShowSplit(splitAttributes)) {
            return false;
        }

        mTransactionManager.getCurrentTransactionRecord().setOriginType(TRANSIT_CLOSE);
        mPresenter.cleanupContainer(wct, splitContainer.getSecondaryContainer(),
                false /* shouldFinishDependent */);
        return true;
    }

    /**
     * Returns the rule to launch a placeholder for the activity with the provided component name
     * if it is configured in the split config.
     */
    @GuardedBy("mLock")
    private SplitPlaceholderRule getPlaceholderRule(@NonNull Activity activity) {
        for (EmbeddingRule rule : mSplitRules) {
            if (!(rule instanceof SplitPlaceholderRule)) {
                continue;
            }
            SplitPlaceholderRule placeholderRule = (SplitPlaceholderRule) rule;
            if (placeholderRule.matchesActivity(activity)) {
                return placeholderRule;
            }
        }
        return null;
    }

    /**
     * Notifies listeners about changes to split states if necessary.
     */
    @VisibleForTesting
    @GuardedBy("mLock")
    void updateCallbackIfNecessary() {
        if (mEmbeddingCallback == null || !readyToReportToClient()) {
            return;
        }
        final List<SplitInfo> currentSplitStates = getActiveSplitStates();
        if (mLastReportedSplitStates.equals(currentSplitStates)) {
            return;
        }
        mLastReportedSplitStates.clear();
        mLastReportedSplitStates.addAll(currentSplitStates);
        mEmbeddingCallback.accept(currentSplitStates);
    }

    /**
     * Returns a list of descriptors for currently active split states.
     */
    @GuardedBy("mLock")
    @NonNull
    private List<SplitInfo> getActiveSplitStates() {
        final List<SplitInfo> splitStates = new ArrayList<>();
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            mTaskContainers.valueAt(i).getSplitStates(splitStates);
        }
        return splitStates;
    }

    /**
     * Whether we can now report the split states to the client.
     */
    @GuardedBy("mLock")
    private boolean readyToReportToClient() {
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            if (mTaskContainers.valueAt(i).isInIntermediateState()) {
                // If any Task is in an intermediate state, wait for the server update.
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code true} if the container is expanded to occupy full task size.
     * Returns {@code false} if the container is included in an active split.
     */
    boolean shouldContainerBeExpanded(@Nullable TaskFragmentContainer container) {
        if (container == null) {
            return false;
        }
        return getActiveSplitForContainer(container) == null;
    }

    /**
     * Returns a split rule for the provided pair of primary activity and secondary activity intent
     * if available.
     */
    @GuardedBy("mLock")
    @Nullable
    private SplitPairRule getSplitRule(@NonNull Activity primaryActivity,
            @NonNull Intent secondaryActivityIntent) {
        for (EmbeddingRule rule : mSplitRules) {
            if (!(rule instanceof SplitPairRule)) {
                continue;
            }
            SplitPairRule pairRule = (SplitPairRule) rule;
            if (pairRule.matchesActivityIntentPair(primaryActivity, secondaryActivityIntent)) {
                return pairRule;
            }
        }
        return null;
    }

    /**
     * Returns a split rule for the provided pair of primary and secondary activities if available.
     */
    @GuardedBy("mLock")
    @Nullable
    private SplitPairRule getSplitRule(@NonNull Activity primaryActivity,
            @NonNull Activity secondaryActivity) {
        for (EmbeddingRule rule : mSplitRules) {
            if (!(rule instanceof SplitPairRule)) {
                continue;
            }
            SplitPairRule pairRule = (SplitPairRule) rule;
            final Intent intent = secondaryActivity.getIntent();
            if (pairRule.matchesActivityPair(primaryActivity, secondaryActivity)
                    && (intent == null
                    || pairRule.matchesActivityIntentPair(primaryActivity, intent))) {
                return pairRule;
            }
        }
        return null;
    }

    @Nullable
    @GuardedBy("mLock")
    TaskFragmentContainer getContainer(@NonNull IBinder fragmentToken) {
        for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
            final List<TaskFragmentContainer> containers = mTaskContainers.valueAt(i).mContainers;
            for (TaskFragmentContainer container : containers) {
                if (container.getTaskFragmentToken().equals(fragmentToken)) {
                    return container;
                }
            }
        }
        return null;
    }

    @Nullable
    @GuardedBy("mLock")
    TaskContainer getTaskContainer(int taskId) {
        return mTaskContainers.get(taskId);
    }

    Handler getHandler() {
        return mHandler;
    }

    @GuardedBy("mLock")
    int getTaskId(@NonNull Activity activity) {
        // Prefer to get the taskId from TaskFragmentContainer because Activity.getTaskId() is an
        // IPC call.
        final TaskFragmentContainer container = getContainerWithActivity(activity);
        return container != null ? container.getTaskId() : activity.getTaskId();
    }

    @Nullable
    Activity getActivity(@NonNull IBinder activityToken) {
        return ActivityThread.currentActivityThread().getActivity(activityToken);
    }

    @VisibleForTesting
    ActivityStartMonitor getActivityStartMonitor() {
        return mActivityStartMonitor;
    }

    /**
     * Gets the token of the TaskFragment that embedded this activity. It is available as soon as
     * the activity is created and attached, so it can be used during {@link #onActivityCreated}
     * before the server notifies the organizer to avoid racing condition.
     */
    @VisibleForTesting
    @Nullable
    IBinder getTaskFragmentTokenFromActivityClientRecord(@NonNull Activity activity) {
        final ActivityThread.ActivityClientRecord record = ActivityThread.currentActivityThread()
                .getActivityClient(activity.getActivityToken());
        return record != null ? record.mTaskFragmentToken : null;
    }

    /**
     * Returns {@code true} if an Activity with the provided component name should always be
     * expanded to occupy full task bounds. Such activity must not be put in a split.
     */
    @GuardedBy("mLock")
    private boolean shouldExpand(@Nullable Activity activity, @Nullable Intent intent) {
        for (EmbeddingRule rule : mSplitRules) {
            if (!(rule instanceof ActivityRule)) {
                continue;
            }
            ActivityRule activityRule = (ActivityRule) rule;
            if (!activityRule.shouldAlwaysExpand()) {
                continue;
            }
            if (activity != null && activityRule.matchesActivity(activity)) {
                return true;
            } else if (intent != null && activityRule.matchesIntent(intent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the associated container should be destroyed together with a finishing
     * container. There is a case when primary containers for placeholders should be retained
     * despite the rule configuration to finish primary with secondary - if they are marked as
     * 'sticky' and the placeholder was finished when fully overlapping the primary container.
     * @return {@code true} if the associated container should be retained (and not be finished).
     */
    // Suppress GuardedBy warning because lint ask to mark this method as
    // @GuardedBy(mPresenter.mController.mLock), which is mLock itself
    @SuppressWarnings("GuardedBy")
    @GuardedBy("mLock")
    boolean shouldRetainAssociatedContainer(@NonNull TaskFragmentContainer finishingContainer,
            @NonNull TaskFragmentContainer associatedContainer) {
        SplitContainer splitContainer = getActiveSplitForContainers(associatedContainer,
                finishingContainer);
        if (splitContainer == null) {
            // Containers are not in the same split, no need to retain.
            return false;
        }
        // Find the finish behavior for the associated container
        int finishBehavior;
        SplitRule splitRule = splitContainer.getSplitRule();
        if (finishingContainer == splitContainer.getPrimaryContainer()) {
            finishBehavior = getFinishSecondaryWithPrimaryBehavior(splitRule);
        } else {
            finishBehavior = getFinishPrimaryWithSecondaryBehavior(splitRule);
        }
        // Decide whether the associated container should be retained based on the current
        // presentation mode.
        if (shouldShowSplit(splitContainer)) {
            return !shouldFinishAssociatedContainerWhenAdjacent(finishBehavior);
        } else {
            return !shouldFinishAssociatedContainerWhenStacked(finishBehavior);
        }
    }

    /**
     * @see #shouldRetainAssociatedContainer(TaskFragmentContainer, TaskFragmentContainer)
     */
    @GuardedBy("mLock")
    boolean shouldRetainAssociatedActivity(@NonNull TaskFragmentContainer finishingContainer,
            @NonNull Activity associatedActivity) {
        final TaskFragmentContainer associatedContainer = getContainerWithActivity(
                associatedActivity);
        if (associatedContainer == null) {
            return false;
        }

        return shouldRetainAssociatedContainer(finishingContainer, associatedContainer);
    }

    private final class LifecycleCallbacks extends EmptyLifecycleCallbacksAdapter {

        @Override
        public void onActivityPreCreated(@NonNull Activity activity,
                @Nullable Bundle savedInstanceState) {
            if (activity.isChild()) {
                // Skip Activity that is child of another Activity (ActivityGroup) because it's
                // window will just be a child of the parent Activity window.
                return;
            }
            synchronized (mLock) {
                final IBinder activityToken = activity.getActivityToken();
                final IBinder initialTaskFragmentToken =
                        getTaskFragmentTokenFromActivityClientRecord(activity);
                // If the activity is not embedded, then it will not have an initial task fragment
                // token so no further action is needed.
                if (initialTaskFragmentToken == null) {
                    return;
                }
                for (int i = mTaskContainers.size() - 1; i >= 0; i--) {
                    final List<TaskFragmentContainer> containers = mTaskContainers.valueAt(i)
                            .mContainers;
                    for (int j = containers.size() - 1; j >= 0; j--) {
                        final TaskFragmentContainer container = containers.get(j);
                        if (!container.hasActivity(activityToken)
                                && container.getTaskFragmentToken()
                                .equals(initialTaskFragmentToken)) {
                            // The onTaskFragmentInfoChanged callback containing this activity has
                            // not reached the client yet, so add the activity to the pending
                            // appeared activities.
                            container.addPendingAppearedActivity(activity);
                            return;
                        }
                    }
                }
            }
        }

        @Override
        public void onActivityPostCreated(@NonNull Activity activity,
                @Nullable Bundle savedInstanceState) {
            if (activity.isChild()) {
                // Skip Activity that is child of another Activity (ActivityGroup) because it's
                // window will just be a child of the parent Activity window.
                return;
            }
            // Calling after Activity#onCreate is complete to allow the app launch something
            // first. In case of a configured placeholder activity we want to make sure
            // that we don't launch it if an activity itself already requested something to be
            // launched to side.
            synchronized (mLock) {
                final TransactionRecord transactionRecord = mTransactionManager
                        .startNewTransaction();
                transactionRecord.setOriginType(TRANSIT_OPEN);
                SplitController.this.onActivityCreated(transactionRecord.getTransaction(),
                        activity);
                // The WCT should be applied and merged to the activity launch transition.
                transactionRecord.apply(false /* shouldApplyIndependently */);
            }
        }

        @Override
        public void onActivityConfigurationChanged(@NonNull Activity activity) {
            if (activity.isChild()) {
                // Skip Activity that is child of another Activity (ActivityGroup) because it's
                // window will just be a child of the parent Activity window.
                return;
            }
            synchronized (mLock) {
                final TransactionRecord transactionRecord = mTransactionManager
                        .startNewTransaction();
                SplitController.this.onActivityConfigurationChanged(
                        transactionRecord.getTransaction(), activity);
                // The WCT should be applied and merged to the Task change transition so that the
                // placeholder is launched in the same transition.
                transactionRecord.apply(false /* shouldApplyIndependently */);
            }
        }

        @Override
        public void onActivityPostDestroyed(@NonNull Activity activity) {
            if (activity.isChild()) {
                // Skip Activity that is child of another Activity (ActivityGroup) because it's
                // window will just be a child of the parent Activity window.
                return;
            }
            synchronized (mLock) {
                SplitController.this.onActivityDestroyed(activity);
            }
        }
    }

    /** Executor that posts on the main application thread. */
    private static class MainThreadExecutor implements Executor {
        private final Handler mHandler = new Handler(Looper.getMainLooper());

        @Override
        public void execute(@NonNull Runnable r) {
            mHandler.post(r);
        }
    }

    /**
     * A monitor that intercepts all activity start requests originating in the client process and
     * can amend them to target a specific task fragment to form a split.
     */
    @VisibleForTesting
    class ActivityStartMonitor extends Instrumentation.ActivityMonitor {
        @VisibleForTesting
        @GuardedBy("mLock")
        Intent mCurrentIntent;

        @Override
        public Instrumentation.ActivityResult onStartActivity(@NonNull Context who,
                @NonNull Intent intent, @NonNull Bundle options) {
            // TODO(b/232042367): Consolidate the activity create handling so that we can handle
            // cross-process the same as normal.

            final Activity launchingActivity;
            if (who instanceof Activity) {
                // We will check if the new activity should be split with the activity that launched
                // it.
                final Activity activity = (Activity) who;
                // For Activity that is child of another Activity (ActivityGroup), treat the parent
                // Activity as the launching one because it's window will just be a child of the
                // parent Activity window.
                launchingActivity = activity.isChild() ? activity.getParent() : activity;
                if (isInPictureInPicture(launchingActivity)) {
                    // We don't embed activity when it is in PIP.
                    return super.onStartActivity(who, intent, options);
                }
            } else {
                // When the context to start activity is not an Activity context, we will check if
                // the new activity should be embedded in the known Task belonging to the organizer
                // process. @see #resolveStartActivityIntentFromNonActivityContext
                // It is a current security limitation that we can't access the activity info of
                // other process even if it is in the same Task.
                launchingActivity = null;
            }

            synchronized (mLock) {
                final TransactionRecord transactionRecord = mTransactionManager
                        .startNewTransaction();
                transactionRecord.setOriginType(TRANSIT_OPEN);
                final WindowContainerTransaction wct = transactionRecord.getTransaction();
                final TaskFragmentContainer launchedInTaskFragment;
                if (launchingActivity != null) {
                    final int taskId = getTaskId(launchingActivity);
                    launchedInTaskFragment = resolveStartActivityIntent(wct, taskId, intent,
                            launchingActivity);
                } else {
                    launchedInTaskFragment = resolveStartActivityIntentFromNonActivityContext(wct,
                            intent);
                }
                if (launchedInTaskFragment != null) {
                    // Make sure the WCT is applied immediately instead of being queued so that the
                    // TaskFragment will be ready before activity attachment.
                    transactionRecord.apply(false /* shouldApplyIndependently */);
                    // Amend the request to let the WM know that the activity should be placed in
                    // the dedicated container.
                    options.putBinder(ActivityOptions.KEY_LAUNCH_TASK_FRAGMENT_TOKEN,
                            launchedInTaskFragment.getTaskFragmentToken());
                    mCurrentIntent = intent;
                } else {
                    transactionRecord.abort();
                }
            }

            return super.onStartActivity(who, intent, options);
        }

        @Override
        public void onStartActivityResult(int result, @NonNull Bundle bOptions) {
            super.onStartActivityResult(result, bOptions);
            synchronized (mLock) {
                if (mCurrentIntent != null && result != START_SUCCESS) {
                    // Clear the pending appeared intent if the activity was not started
                    // successfully.
                    final IBinder token = bOptions.getBinder(
                            ActivityOptions.KEY_LAUNCH_TASK_FRAGMENT_TOKEN);
                    if (token != null) {
                        final TaskFragmentContainer container = getContainer(token);
                        if (container != null) {
                            container.clearPendingAppearedIntentIfNeeded(mCurrentIntent);
                        }
                    }
                }
                mCurrentIntent = null;
            }
        }
    }

    /**
     * Checks if an activity is embedded and its presentation is customized by a
     * {@link android.window.TaskFragmentOrganizer} to only occupy a portion of Task bounds.
     */
    @Override
    public boolean isActivityEmbedded(@NonNull Activity activity) {
        synchronized (mLock) {
            return mPresenter.isActivityEmbedded(activity.getActivityToken());
        }
    }

    /**
     * If the two rules have the same presentation, we can reuse the same {@link SplitContainer} if
     * there is any.
     */
    private static boolean canReuseContainer(@NonNull SplitRule rule1, @NonNull SplitRule rule2,
            @NonNull WindowMetrics parentWindowMetrics) {
        if (!isContainerReusableRule(rule1) || !isContainerReusableRule(rule2)) {
            return false;
        }
        return haveSamePresentation((SplitPairRule) rule1, (SplitPairRule) rule2,
                parentWindowMetrics);
    }

    /** Whether the two rules have the same presentation. */
    @VisibleForTesting
    static boolean haveSamePresentation(@NonNull SplitPairRule rule1,
            @NonNull SplitPairRule rule2, @NonNull WindowMetrics parentWindowMetrics) {
        if (rule1.getTag() != null || rule2.getTag() != null) {
            // Tag must be unique if it is set. We don't want to reuse the container if the rules
            // have different tags because they can have different SplitAttributes later through
            // SplitAttributesCalculator.
            return Objects.equals(rule1.getTag(), rule2.getTag());
        }
        // If both rules don't have tag, compare all SplitRules' properties that may affect their
        // SplitAttributes.
        // TODO(b/231655482): add util method to do the comparison in SplitPairRule.
        return rule1.getDefaultSplitAttributes().equals(rule2.getDefaultSplitAttributes())
                && rule1.checkParentMetrics(parentWindowMetrics)
                == rule2.checkParentMetrics(parentWindowMetrics)
                && rule1.getFinishPrimaryWithSecondary() == rule2.getFinishPrimaryWithSecondary()
                && rule1.getFinishSecondaryWithPrimary() == rule2.getFinishSecondaryWithPrimary();
    }

    /**
     * Whether it is ok for other rule to reuse the {@link TaskFragmentContainer} of the given
     * rule.
     */
    private static boolean isContainerReusableRule(@NonNull SplitRule rule) {
        // We don't expect to reuse the placeholder rule.
        if (!(rule instanceof SplitPairRule)) {
            return false;
        }
        final SplitPairRule pairRule = (SplitPairRule) rule;

        // Not reuse if it needs to destroy the existing.
        return !pairRule.shouldClearTop();
    }

    private static boolean isInPictureInPicture(@NonNull Activity activity) {
        return isInPictureInPicture(activity.getResources().getConfiguration());
    }

    private static boolean isInPictureInPicture(@NonNull TaskFragmentContainer tf) {
        return isInPictureInPicture(tf.getInfo().getConfiguration());
    }

    private static boolean isInPictureInPicture(@Nullable Configuration configuration) {
        return configuration != null
                && configuration.windowConfiguration.getWindowingMode() == WINDOWING_MODE_PINNED;
    }
}
