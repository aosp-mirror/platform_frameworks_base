/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.PendingIntent;
import android.app.WindowConfiguration;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ShortcutInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.view.InsetsState;
import android.view.SurfaceControl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Represents a collection of operations on some WindowContainers that should be applied all at
 * once.
 *
 * @hide
 */
@TestApi
public final class WindowContainerTransaction implements Parcelable {
    private final ArrayMap<IBinder, Change> mChanges = new ArrayMap<>();

    // Flat list because re-order operations are order-dependent
    private final ArrayList<HierarchyOp> mHierarchyOps = new ArrayList<>();

    @Nullable
    private IBinder mErrorCallbackToken;

    @Nullable
    private ITaskFragmentOrganizer mTaskFragmentOrganizer;

    public WindowContainerTransaction() {}

    private WindowContainerTransaction(Parcel in) {
        in.readMap(mChanges, null /* loader */);
        in.readTypedList(mHierarchyOps, HierarchyOp.CREATOR);
        mErrorCallbackToken = in.readStrongBinder();
        mTaskFragmentOrganizer = ITaskFragmentOrganizer.Stub.asInterface(in.readStrongBinder());
    }

    private Change getOrCreateChange(IBinder token) {
        Change out = mChanges.get(token);
        if (out == null) {
            out = new Change();
            mChanges.put(token, out);
        }
        return out;
    }

    /**
     * Resize a container.
     */
    @NonNull
    public WindowContainerTransaction setBounds(
            @NonNull WindowContainerToken container,@NonNull Rect bounds) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mConfiguration.windowConfiguration.setBounds(bounds);
        chg.mConfigSetMask |= ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
        chg.mWindowSetMask |= WindowConfiguration.WINDOW_CONFIG_BOUNDS;
        return this;
    }

    /**
     * Resize a container's app bounds. This is the bounds used to report appWidth/Height to an
     * app's DisplayInfo. It is derived by subtracting the overlapping portion of the navbar from
     * the full bounds.
     */
    @NonNull
    public WindowContainerTransaction setAppBounds(
            @NonNull WindowContainerToken container,@NonNull Rect appBounds) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mConfiguration.windowConfiguration.setAppBounds(appBounds);
        chg.mConfigSetMask |= ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
        chg.mWindowSetMask |= WindowConfiguration.WINDOW_CONFIG_APP_BOUNDS;
        return this;
    }

    /**
     * Resize a container's configuration size. The configuration size is what gets reported to the
     * app via screenWidth/HeightDp and influences which resources get loaded. This size is
     * derived by subtracting the overlapping portions of both the statusbar and the navbar from
     * the full bounds.
     */
    @NonNull
    public WindowContainerTransaction setScreenSizeDp(
            @NonNull WindowContainerToken container, int w, int h) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mConfiguration.screenWidthDp = w;
        chg.mConfiguration.screenHeightDp = h;
        chg.mConfigSetMask |= ActivityInfo.CONFIG_SCREEN_SIZE;
        return this;
    }

    /**
     * Notify {@link com.android.server.wm.PinnedTaskController} that the picture-in-picture task
     * has finished the enter animation with the given bounds.
     */
    @NonNull
    public WindowContainerTransaction scheduleFinishEnterPip(
            @NonNull WindowContainerToken container,@NonNull Rect bounds) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mPinnedBounds = new Rect(bounds);
        chg.mChangeMask |= Change.CHANGE_PIP_CALLBACK;

        return this;
    }

    /**
     * Send a SurfaceControl transaction to the server, which the server will apply in sync with
     * the next bounds change. As this uses deferred transaction and not BLAST it is only
     * able to sync with a single window, and the first visible window in this hierarchy of type
     * BASE_APPLICATION to resize will be used. If there are bound changes included in this
     * WindowContainer transaction (from setBounds or scheduleFinishEnterPip), the SurfaceControl
     * transaction will be synced with those bounds. If there are no changes, then
     * the SurfaceControl transaction will be synced with the next bounds change. This means
     * that you can call this, apply the WindowContainer transaction, and then later call
     * dismissPip() to achieve synchronization.
     */
    @NonNull
    public WindowContainerTransaction setBoundsChangeTransaction(
            @NonNull WindowContainerToken container,@NonNull SurfaceControl.Transaction t) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mBoundsChangeTransaction = t;
        chg.mChangeMask |= Change.CHANGE_BOUNDS_TRANSACTION;
        return this;
    }

    /**
     * Like {@link #setBoundsChangeTransaction} but instead queues up a setPosition/WindowCrop
     * on a container's surface control. This is useful when a boundsChangeTransaction needs to be
     * queued up on a Task that won't be organized until the end of this window-container
     * transaction.
     *
     * This requires that, at the end of this transaction, `task` will be organized; otherwise
     * the server will throw an IllegalArgumentException.
     *
     * WARNING: Use this carefully. Whatever is set here should match the expected bounds after
     *          the transaction completes since it will likely be replaced by it. This call is
     *          intended to pre-emptively set bounds on a surface in sync with a buffer when
     *          otherwise the new bounds and the new buffer would update on different frames.
     *
     * TODO(b/134365562): remove once TaskOrg drives full-screen or BLAST is enabled.
     *
     * @hide
     */
    @NonNull
    public WindowContainerTransaction setBoundsChangeTransaction(
            @NonNull WindowContainerToken task, @NonNull Rect surfaceBounds) {
        Change chg = getOrCreateChange(task.asBinder());
        if (chg.mBoundsChangeSurfaceBounds == null) {
            chg.mBoundsChangeSurfaceBounds = new Rect();
        }
        chg.mBoundsChangeSurfaceBounds.set(surfaceBounds);
        chg.mChangeMask |= Change.CHANGE_BOUNDS_TRANSACTION_RECT;
        return this;
    }

    /**
     * Set the windowing mode of children of a given root task, without changing
     * the windowing mode of the Task itself. This can be used during transitions
     * for example to make the activity render it's fullscreen configuration
     * while the Task is still in PIP, so you can complete the animation.
     *
     * TODO(b/134365562): Can be removed once TaskOrg drives full-screen
     */
    @NonNull
    public WindowContainerTransaction setActivityWindowingMode(
            @NonNull WindowContainerToken container, int windowingMode) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mActivityWindowingMode = windowingMode;
        return this;
    }

    /**
     * Sets the windowing mode of the given container.
     */
    @NonNull
    public WindowContainerTransaction setWindowingMode(
            @NonNull WindowContainerToken container, int windowingMode) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mWindowingMode = windowingMode;
        return this;
    }

    /**
     * Sets whether a container or any of its children can be focusable. When {@code false}, no
     * child can be focused; however, when {@code true}, it is still possible for children to be
     * non-focusable due to WM policy.
     */
    @NonNull
    public WindowContainerTransaction setFocusable(
            @NonNull WindowContainerToken container, boolean focusable) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mFocusable = focusable;
        chg.mChangeMask |= Change.CHANGE_FOCUSABLE;
        return this;
    }

    /**
     * Sets whether a container or its children should be hidden. When {@code false}, the existing
     * visibility of the container applies, but when {@code true} the container will be forced
     * to be hidden.
     */
    @NonNull
    public WindowContainerTransaction setHidden(
            @NonNull WindowContainerToken container, boolean hidden) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mHidden = hidden;
        chg.mChangeMask |= Change.CHANGE_HIDDEN;
        return this;
    }

    /**
     * Set the smallestScreenWidth of a container.
     */
    @NonNull
    public WindowContainerTransaction setSmallestScreenWidthDp(
            @NonNull WindowContainerToken container, int widthDp) {
        Change cfg = getOrCreateChange(container.asBinder());
        cfg.mConfiguration.smallestScreenWidthDp = widthDp;
        cfg.mConfigSetMask |= ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
        return this;
    }

    /**
     * Sets whether a container should ignore the orientation request from apps and windows below
     * it. It currently only applies to {@link com.android.server.wm.DisplayArea}. When
     * {@code false}, it may rotate based on the orientation request; When {@code true}, it can
     * never specify orientation, but shows the fixed-orientation apps below it in the letterbox.
     * @hide
     */
    @NonNull
    public WindowContainerTransaction setIgnoreOrientationRequest(
            @NonNull WindowContainerToken container, boolean ignoreOrientationRequest) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mIgnoreOrientationRequest = ignoreOrientationRequest;
        chg.mChangeMask |= Change.CHANGE_IGNORE_ORIENTATION_REQUEST;
        return this;
    }

    /**
     * Sets whether a task should be translucent. When {@code false}, the existing translucent of
     * the task applies, but when {@code true} the task will be forced to be translucent.
     * @hide
     */
    @NonNull
    public WindowContainerTransaction setForceTranslucent(
            @NonNull WindowContainerToken container, boolean forceTranslucent) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mForceTranslucent = forceTranslucent;
        chg.mChangeMask |= Change.CHANGE_FORCE_TRANSLUCENT;
        return this;
    }

    /**
     * Used in conjunction with a shell-transition call (usually finishTransition). This is
     * basically a message to the transition system that a particular task should NOT go into
     * PIP even though it normally would. This is to deal with some edge-case situations where
     * Recents will "commit" the transition to go home, but then not actually go-home.
     * @hide
     */
    @NonNull
    public WindowContainerTransaction setDoNotPip(@NonNull WindowContainerToken container) {
        Change chg = getOrCreateChange(container.asBinder());
        chg.mChangeMask |= Change.CHANGE_FORCE_NO_PIP;
        return this;
    }

    /**
     * Reparents a container into another one. The effect of a {@code null} parent can vary. For
     * example, reparenting a stack to {@code null} will reparent it to its display.
     *
     * @param onTop When {@code true}, the child goes to the top of parent; otherwise it goes to
     *              the bottom.
     */
    @NonNull
    public WindowContainerTransaction reparent(@NonNull WindowContainerToken child,
            @Nullable WindowContainerToken parent, boolean onTop) {
        mHierarchyOps.add(HierarchyOp.createForReparent(child.asBinder(),
                parent == null ? null : parent.asBinder(),
                onTop));
        return this;
    }

    /**
     * Reorders a container within its parent.
     *
     * @param onTop When {@code true}, the child goes to the top of parent; otherwise it goes to
     *              the bottom.
     */
    @NonNull
    public WindowContainerTransaction reorder(@NonNull WindowContainerToken child, boolean onTop) {
        mHierarchyOps.add(HierarchyOp.createForReorder(child.asBinder(), onTop));
        return this;
    }

    /**
     * Reparent's all children tasks or the top task of {@param currentParent} in the specified
     * {@param windowingMode} and {@param activityType} to {@param newParent} in their current
     * z-order.
     *
     * @param currentParent of the tasks to perform the operation no.
     *                      {@code null} will perform the operation on the display.
     * @param newParent for the tasks. {@code null} will perform the operation on the display.
     * @param windowingModes of the tasks to reparent.
     * @param activityTypes of the tasks to reparent.
     * @param onTop When {@code true}, the child goes to the top of parent; otherwise it goes to
     *              the bottom.
     * @param reparentTopOnly When {@code true}, only reparent the top task which fit windowingModes
     *                        and activityTypes.
     * @hide
     */
    @NonNull
    public WindowContainerTransaction reparentTasks(@Nullable WindowContainerToken currentParent,
            @Nullable WindowContainerToken newParent, @Nullable int[] windowingModes,
            @Nullable int[] activityTypes, boolean onTop, boolean reparentTopOnly) {
        mHierarchyOps.add(HierarchyOp.createForChildrenTasksReparent(
                currentParent != null ? currentParent.asBinder() : null,
                newParent != null ? newParent.asBinder() : null,
                windowingModes,
                activityTypes,
                onTop,
                reparentTopOnly));
        return this;
    }

    /**
     * Reparent's all children tasks of {@param currentParent} in the specified
     * {@param windowingMode} and {@param activityType} to {@param newParent} in their current
     * z-order.
     *
     * @param currentParent of the tasks to perform the operation no.
     *                      {@code null} will perform the operation on the display.
     * @param newParent for the tasks. {@code null} will perform the operation on the display.
     * @param windowingModes of the tasks to reparent. {@code null} ignore this attribute when
     *                       perform the operation.
     * @param activityTypes of the tasks to reparent.  {@code null} ignore this attribute when
     *                      perform the operation.
     * @param onTop When {@code true}, the child goes to the top of parent; otherwise it goes to
     *              the bottom.
     */
    @NonNull
    public WindowContainerTransaction reparentTasks(@Nullable WindowContainerToken currentParent,
            @Nullable WindowContainerToken newParent, @Nullable int[] windowingModes,
            @Nullable int[] activityTypes, boolean onTop) {
        return reparentTasks(currentParent, newParent, windowingModes, activityTypes, onTop,
                false /* reparentTopOnly */);
    }

    /**
     * Sets whether a container should be the launch root for the specified windowing mode and
     * activity type. This currently only applies to Task containers created by organizer.
     */
    @NonNull
    public WindowContainerTransaction setLaunchRoot(@NonNull WindowContainerToken container,
            @Nullable int[] windowingModes, @Nullable int[] activityTypes) {
        mHierarchyOps.add(HierarchyOp.createForSetLaunchRoot(
                container.asBinder(),
                windowingModes,
                activityTypes));
        return this;
    }

    /**
     * Sets to containers adjacent to each other. Containers below two visible adjacent roots will
     * be made invisible. This currently only applies to TaskFragment containers created by
     * organizer.
     * @param root1 the first root.
     * @param root2 the second root.
     */
    @NonNull
    public WindowContainerTransaction setAdjacentRoots(
            @NonNull WindowContainerToken root1, @NonNull WindowContainerToken root2) {
        mHierarchyOps.add(HierarchyOp.createForAdjacentRoots(
                root1.asBinder(),
                root2.asBinder()));
        return this;
    }

    /**
     * This is temp function for compatible with old cts tests suite and it equal to
     * {@link #setAdjacentRoots(WindowContainerToken, WindowContainerToken).
     * @deprecated should use {@link #setAdjacentRoots(WindowContainerToken, WindowContainerToken)}
     */
    @Deprecated
    @NonNull
    public WindowContainerTransaction setAdjacentRoots(
            @NonNull WindowContainerToken root1, @NonNull WindowContainerToken root2,
            boolean moveTogether) {
        return setAdjacentRoots(root1, root2);
    }

    /**
     * Sets the container as launch adjacent flag root. Task starting with
     * {@link FLAG_ACTIVITY_LAUNCH_ADJACENT} will be launching to.
     */
    @NonNull
    public WindowContainerTransaction setLaunchAdjacentFlagRoot(
            @NonNull WindowContainerToken container) {
        mHierarchyOps.add(HierarchyOp.createForSetLaunchAdjacentFlagRoot(container.asBinder(),
                false /* clearRoot */));
        return this;
    }

    /**
     * Clears launch adjacent flag root for the display area of passing container.
     */
    @NonNull
    public WindowContainerTransaction clearLaunchAdjacentFlagRoot(
            @NonNull WindowContainerToken container) {
        mHierarchyOps.add(HierarchyOp.createForSetLaunchAdjacentFlagRoot(container.asBinder(),
                true /* clearRoot */));
        return this;
    }

    /**
     * Starts a task by id. The task is expected to already exist (eg. as a recent task).
     * @param taskId Id of task to start.
     * @param options bundle containing ActivityOptions for the task's top activity.
     * @hide
     */
    @NonNull
    public WindowContainerTransaction startTask(int taskId, @Nullable Bundle options) {
        mHierarchyOps.add(HierarchyOp.createForTaskLaunch(taskId, options));
        return this;
    }

    /**
     * Finds and removes a task and its children using its container token. The task is removed
     * from recents.
     * @param containerToken ContainerToken of Task to be removed
     */
    @NonNull
    public WindowContainerTransaction removeTask(@NonNull WindowContainerToken containerToken) {
        mHierarchyOps.add(HierarchyOp.createForRemoveTask(containerToken.asBinder()));
        return this;
    }

    /**
     * Sends a pending intent in sync.
     * @param sender The PendingIntent sender.
     * @param intent The fillIn intent to patch over the sender's base intent.
     * @param options bundle containing ActivityOptions for the task's top activity.
     * @hide
     */
    @NonNull
    public WindowContainerTransaction sendPendingIntent(PendingIntent sender, Intent intent,
            @Nullable Bundle options) {
        mHierarchyOps.add(new HierarchyOp.Builder(HierarchyOp.HIERARCHY_OP_TYPE_PENDING_INTENT)
                .setLaunchOptions(options)
                .setPendingIntent(sender)
                .setActivityIntent(intent)
                .build());
        return this;
    }

    /**
     * Starts activity(s) from a shortcut.
     * @param callingPackage The package launching the shortcut.
     * @param shortcutInfo Information about the shortcut to start
     * @param options bundle containing ActivityOptions for the task's top activity.
     * @hide
     */
    @NonNull
    public WindowContainerTransaction startShortcut(@NonNull String callingPackage,
            @NonNull ShortcutInfo shortcutInfo, @Nullable Bundle options) {
        mHierarchyOps.add(HierarchyOp.createForStartShortcut(
                callingPackage, shortcutInfo, options));
        return this;
    }

    /**
     * Creates a new TaskFragment with the given options.
     * @param taskFragmentOptions the options used to create the TaskFragment.
     */
    @NonNull
    public WindowContainerTransaction createTaskFragment(
            @NonNull TaskFragmentCreationParams taskFragmentOptions) {
        final HierarchyOp hierarchyOp =
                new HierarchyOp.Builder(HierarchyOp.HIERARCHY_OP_TYPE_CREATE_TASK_FRAGMENT)
                        .setTaskFragmentCreationOptions(taskFragmentOptions)
                        .build();
        mHierarchyOps.add(hierarchyOp);
        return this;
    }

    /**
     * Deletes an existing TaskFragment. Any remaining activities below it will be destroyed.
     * @param taskFragment  the TaskFragment to be removed.
     */
    @NonNull
    public WindowContainerTransaction deleteTaskFragment(
            @NonNull WindowContainerToken taskFragment) {
        final HierarchyOp hierarchyOp =
                new HierarchyOp.Builder(HierarchyOp.HIERARCHY_OP_TYPE_DELETE_TASK_FRAGMENT)
                        .setContainer(taskFragment.asBinder())
                        .build();
        mHierarchyOps.add(hierarchyOp);
        return this;
    }

    /**
     * Starts an activity in the TaskFragment.
     * @param fragmentToken client assigned unique token to create TaskFragment with specified in
     *                      {@link TaskFragmentCreationParams#getFragmentToken()}.
     * @param callerToken  the activity token that initialized the activity launch.
     * @param activityIntent    intent to start the activity.
     * @param activityOptions    ActivityOptions to start the activity with.
     * @see android.content.Context#startActivity(Intent, Bundle).
     */
    @NonNull
    public WindowContainerTransaction startActivityInTaskFragment(
            @NonNull IBinder fragmentToken, @NonNull IBinder callerToken,
            @NonNull Intent activityIntent, @Nullable Bundle activityOptions) {
        final HierarchyOp hierarchyOp =
                new HierarchyOp.Builder(
                        HierarchyOp.HIERARCHY_OP_TYPE_START_ACTIVITY_IN_TASK_FRAGMENT)
                        .setContainer(fragmentToken)
                        .setReparentContainer(callerToken)
                        .setActivityIntent(activityIntent)
                        .setLaunchOptions(activityOptions)
                        .build();
        mHierarchyOps.add(hierarchyOp);
        return this;
    }

    /**
     * Moves an activity into the TaskFragment.
     * @param fragmentToken client assigned unique token to create TaskFragment with specified in
     *                      {@link TaskFragmentCreationParams#getFragmentToken()}.
     * @param activityToken activity to be reparented.
     */
    @NonNull
    public WindowContainerTransaction reparentActivityToTaskFragment(
            @NonNull IBinder fragmentToken, @NonNull IBinder activityToken) {
        final HierarchyOp hierarchyOp =
                new HierarchyOp.Builder(
                        HierarchyOp.HIERARCHY_OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT)
                        .setReparentContainer(fragmentToken)
                        .setContainer(activityToken)
                        .build();
        mHierarchyOps.add(hierarchyOp);
        return this;
    }

    /**
     * Reparents all children of one TaskFragment to another.
     * @param oldParent children of this TaskFragment will be reparented.
     * @param newParent the new parent TaskFragment to move the children to. If {@code null}, the
     *                  children will be moved to the leaf Task.
     */
    @NonNull
    public WindowContainerTransaction reparentChildren(
            @NonNull WindowContainerToken oldParent,
            @Nullable WindowContainerToken newParent) {
        final HierarchyOp hierarchyOp =
                new HierarchyOp.Builder(HierarchyOp.HIERARCHY_OP_TYPE_REPARENT_CHILDREN)
                        .setContainer(oldParent.asBinder())
                        .setReparentContainer(newParent != null ? newParent.asBinder() : null)
                        .build();
        mHierarchyOps.add(hierarchyOp);
        return this;
    }

    /**
     * Sets to TaskFragments adjacent to each other. Containers below two visible adjacent
     * TaskFragments will be made invisible. This is similar to
     * {@link #setAdjacentRoots(WindowContainerToken, WindowContainerToken)}, but can be used with
     * fragmentTokens when that TaskFragments haven't been created (but will be created in the same
     * {@link WindowContainerTransaction}).
     * To reset it, pass {@code null} for {@code fragmentToken2}.
     * @param fragmentToken1    client assigned unique token to create TaskFragment with specified
     *                          in {@link TaskFragmentCreationParams#getFragmentToken()}.
     * @param fragmentToken2    client assigned unique token to create TaskFragment with specified
     *                          in {@link TaskFragmentCreationParams#getFragmentToken()}. If it is
     *                          {@code null}, the transaction will reset the adjacent TaskFragment.
     */
    @NonNull
    public WindowContainerTransaction setAdjacentTaskFragments(
            @NonNull IBinder fragmentToken1, @Nullable IBinder fragmentToken2,
            @Nullable TaskFragmentAdjacentParams params) {
        final HierarchyOp hierarchyOp =
                new HierarchyOp.Builder(HierarchyOp.HIERARCHY_OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS)
                        .setContainer(fragmentToken1)
                        .setReparentContainer(fragmentToken2)
                        .setLaunchOptions(params != null ? params.toBundle() : null)
                        .build();
        mHierarchyOps.add(hierarchyOp);
        return this;
    }

    /**
     * If `container` was brought to front as a transient-launch (eg. recents), this will reorder
     * the container back to where it was prior to the transient-launch. This way if a transient
     * launch is "aborted", the z-ordering of containers in WM should be restored to before the
     * launch.
     * @hide
     */
    @NonNull
    public WindowContainerTransaction restoreTransientOrder(
            @NonNull WindowContainerToken container) {
        final HierarchyOp hierarchyOp =
                new HierarchyOp.Builder(HierarchyOp.HIERARCHY_OP_TYPE_RESTORE_TRANSIENT_ORDER)
                        .setContainer(container.asBinder())
                        .build();
        mHierarchyOps.add(hierarchyOp);
        return this;
    }

    /**
     * Adds a given {@code Rect} as a rect insets provider on the {@code receiverWindowContainer}.
     * This will trigger a change of insets for all the children in the subtree of
     * {@code receiverWindowContainer}.
     *
     * @param receiverWindowContainer the window container which the insets provider need to be
     *                                added to
     * @param insetsProviderFrame the frame that will be added as Insets provider
     * @param insetsTypes types of insets the rect provides
     * @hide
     */
    @NonNull
    public WindowContainerTransaction addRectInsetsProvider(
            @NonNull WindowContainerToken receiverWindowContainer,
            @NonNull Rect insetsProviderFrame,
            @InsetsState.InternalInsetsType int[] insetsTypes) {
        final HierarchyOp hierarchyOp =
                new HierarchyOp.Builder(
                        HierarchyOp.HIERARCHY_OP_TYPE_ADD_RECT_INSETS_PROVIDER)
                        .setContainer(receiverWindowContainer.asBinder())
                        .setInsetsProviderFrame(insetsProviderFrame)
                        .setInsetsTypes(insetsTypes)
                        .build();
        mHierarchyOps.add(hierarchyOp);
        return this;
    }

    /**
     * Removes the insets provider for the given types from the
     * {@code receiverWindowContainer}. This will trigger a change of insets for all the children
     * in the subtree of {@code receiverWindowContainer}.
     *
     * @param receiverWindowContainer the window container which the insets-override-provider has
     *                                to be removed from
     * @param insetsTypes types of insets that have to be removed
     * @hide
     */
    @NonNull
    public WindowContainerTransaction removeInsetsProvider(
            @NonNull WindowContainerToken receiverWindowContainer,
            @InsetsState.InternalInsetsType int[] insetsTypes) {
        final HierarchyOp hierarchyOp =
                new HierarchyOp.Builder(HierarchyOp.HIERARCHY_OP_TYPE_REMOVE_INSETS_PROVIDER)
                        .setContainer(receiverWindowContainer.asBinder())
                        .setInsetsTypes(insetsTypes)
                        .build();
        mHierarchyOps.add(hierarchyOp);
        return this;
    }

    /**
     * Requests focus on the top running Activity in the given TaskFragment. This will only take
     * effect if there is no focus, or if the current focus is in the same Task as the requested
     * TaskFragment.
     * @param fragmentToken client assigned unique token to create TaskFragment with specified in
     *                      {@link TaskFragmentCreationParams#getFragmentToken()}.
     */
    @NonNull
    public WindowContainerTransaction requestFocusOnTaskFragment(@NonNull IBinder fragmentToken) {
        final HierarchyOp hierarchyOp =
                new HierarchyOp.Builder(
                        HierarchyOp.HIERARCHY_OP_TYPE_REQUEST_FOCUS_ON_TASK_FRAGMENT)
                        .setContainer(fragmentToken)
                        .build();
        mHierarchyOps.add(hierarchyOp);
        return this;
    }

    /**
     * Finishes the Activity.
     * Comparing to directly calling {@link android.app.Activity#finish()}, calling this can make
     * sure the finishing happens in the same transaction with other operations.
     * @param activityToken activity to be finished.
     */
    @NonNull
    public WindowContainerTransaction finishActivity(@NonNull IBinder activityToken) {
        final HierarchyOp hierarchyOp =
                new HierarchyOp.Builder(
                        HierarchyOp.HIERARCHY_OP_TYPE_FINISH_ACTIVITY)
                        .setContainer(activityToken)
                        .build();
        mHierarchyOps.add(hierarchyOp);
        return this;
    }

    /**
     * Sets/removes the always on top flag for this {@code windowContainer}. See
     * {@link com.android.server.wm.ConfigurationContainer#setAlwaysOnTop(boolean)}.
     * Please note that this method is only intended to be used for a
     * {@link com.android.server.wm.DisplayArea}.
     *
     * <p>
     *     Setting always on top to {@code True} will also make the {@code windowContainer} to move
     *     to the top.
     * </p>
     * <p>
     *     Setting always on top to {@code False} will make this {@code windowContainer} to move
     *     below the other always on top sibling containers.
     * </p>
     *
     * @param windowContainer the container which the flag need to be updated for.
     * @param alwaysOnTop denotes whether or not always on top flag should be set.
     * @hide
     */
    @NonNull
    public WindowContainerTransaction setAlwaysOnTop(
            @NonNull WindowContainerToken windowContainer,
            boolean alwaysOnTop) {
        final HierarchyOp hierarchyOp =
                new HierarchyOp.Builder(
                        HierarchyOp.HIERARCHY_OP_TYPE_SET_ALWAYS_ON_TOP)
                        .setContainer(windowContainer.asBinder())
                        .setAlwaysOnTop(alwaysOnTop)
                        .build();
        mHierarchyOps.add(hierarchyOp);
        return this;
    }

    /**
     * When this {@link WindowContainerTransaction} failed to finish on the server side, it will
     * trigger callback with this {@param errorCallbackToken}.
     * @param errorCallbackToken    client provided token that will be passed back as parameter in
     *                              the callback if there is an error on the server side.
     * @see ITaskFragmentOrganizer#onTaskFragmentError
     */
    @NonNull
    public WindowContainerTransaction setErrorCallbackToken(@NonNull IBinder errorCallbackToken) {
        if (mErrorCallbackToken != null) {
            throw new IllegalStateException("Can't set multiple error token for one transaction.");
        }
        mErrorCallbackToken = errorCallbackToken;
        return this;
    }

    /**
     * Sets the {@link TaskFragmentOrganizer} that applies this {@link WindowContainerTransaction}.
     * When this is set, the server side will not check for the permission of
     * {@link android.Manifest.permission#MANAGE_ACTIVITY_TASKS}, but will ensure this WCT only
     * contains operations that are allowed for this organizer, such as modifying TaskFragments that
     * are organized by this organizer.
     * @hide
     */
    @NonNull
    public WindowContainerTransaction setTaskFragmentOrganizer(
            @NonNull ITaskFragmentOrganizer organizer) {
        mTaskFragmentOrganizer = organizer;
        return this;
    }

    /**
     * Merges another WCT into this one.
     * @param transfer When true, this will transfer everything from other potentially leaving
     *                 other in an unusable state. When false, other is left alone, but
     *                 SurfaceFlinger Transactions will not be merged.
     * @hide
     */
    public void merge(WindowContainerTransaction other, boolean transfer) {
        for (int i = 0, n = other.mChanges.size(); i < n; ++i) {
            final IBinder key = other.mChanges.keyAt(i);
            Change existing = mChanges.get(key);
            if (existing == null) {
                existing = new Change();
                mChanges.put(key, existing);
            }
            existing.merge(other.mChanges.valueAt(i), transfer);
        }
        for (int i = 0, n = other.mHierarchyOps.size(); i < n; ++i) {
            mHierarchyOps.add(transfer ? other.mHierarchyOps.get(i)
                    : new HierarchyOp(other.mHierarchyOps.get(i)));
        }
        if (mErrorCallbackToken != null && other.mErrorCallbackToken != null && mErrorCallbackToken
                != other.mErrorCallbackToken) {
            throw new IllegalArgumentException("Can't merge two WCTs with different error token");
        }
        final IBinder taskFragmentOrganizerAsBinder = mTaskFragmentOrganizer != null
                ? mTaskFragmentOrganizer.asBinder()
                : null;
        final IBinder otherTaskFragmentOrganizerAsBinder = other.mTaskFragmentOrganizer != null
                ? other.mTaskFragmentOrganizer.asBinder()
                : null;
        if (!Objects.equals(taskFragmentOrganizerAsBinder, otherTaskFragmentOrganizerAsBinder)) {
            throw new IllegalArgumentException(
                    "Can't merge two WCTs from different TaskFragmentOrganizers");
        }
        mErrorCallbackToken = mErrorCallbackToken != null
                ? mErrorCallbackToken
                : other.mErrorCallbackToken;
    }

    /** @hide */
    public boolean isEmpty() {
        return mChanges.isEmpty() && mHierarchyOps.isEmpty();
    }

    /** @hide */
    public Map<IBinder, Change> getChanges() {
        return mChanges;
    }

    /** @hide */
    public List<HierarchyOp> getHierarchyOps() {
        return mHierarchyOps;
    }

    /** @hide */
    @Nullable
    public IBinder getErrorCallbackToken() {
        return mErrorCallbackToken;
    }

    /** @hide */
    @Nullable
    public ITaskFragmentOrganizer getTaskFragmentOrganizer() {
        return mTaskFragmentOrganizer;
    }

    @Override
    @NonNull
    public String toString() {
        return "WindowContainerTransaction {"
                + " changes = " + mChanges
                + " hops = " + mHierarchyOps
                + " errorCallbackToken=" + mErrorCallbackToken
                + " taskFragmentOrganizer=" + mTaskFragmentOrganizer
                + " }";
    }

    @Override
    /** @hide */
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeMap(mChanges);
        dest.writeTypedList(mHierarchyOps);
        dest.writeStrongBinder(mErrorCallbackToken);
        dest.writeStrongInterface(mTaskFragmentOrganizer);
    }

    @Override
    /** @hide */
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<WindowContainerTransaction> CREATOR =
            new Creator<WindowContainerTransaction>() {
                @Override
                public WindowContainerTransaction createFromParcel(Parcel in) {
                    return new WindowContainerTransaction(in);
                }

                @Override
                public WindowContainerTransaction[] newArray(int size) {
                    return new WindowContainerTransaction[size];
                }
            };

    /**
     * Holds changes on a single WindowContainer including Configuration changes.
     * @hide
     */
    public static class Change implements Parcelable {
        public static final int CHANGE_FOCUSABLE = 1;
        public static final int CHANGE_BOUNDS_TRANSACTION = 1 << 1;
        public static final int CHANGE_PIP_CALLBACK = 1 << 2;
        public static final int CHANGE_HIDDEN = 1 << 3;
        public static final int CHANGE_BOUNDS_TRANSACTION_RECT = 1 << 4;
        public static final int CHANGE_IGNORE_ORIENTATION_REQUEST = 1 << 5;
        public static final int CHANGE_FORCE_NO_PIP = 1 << 6;
        public static final int CHANGE_FORCE_TRANSLUCENT = 1 << 7;

        private final Configuration mConfiguration = new Configuration();
        private boolean mFocusable = true;
        private boolean mHidden = false;
        private boolean mIgnoreOrientationRequest = false;
        private boolean mForceTranslucent = false;

        private int mChangeMask = 0;
        private @ActivityInfo.Config int mConfigSetMask = 0;
        private @WindowConfiguration.WindowConfig int mWindowSetMask = 0;

        private Rect mPinnedBounds = null;
        private SurfaceControl.Transaction mBoundsChangeTransaction = null;
        private Rect mBoundsChangeSurfaceBounds = null;

        private int mActivityWindowingMode = -1;
        private int mWindowingMode = -1;

        public Change() {}

        protected Change(Parcel in) {
            mConfiguration.readFromParcel(in);
            mFocusable = in.readBoolean();
            mHidden = in.readBoolean();
            mIgnoreOrientationRequest = in.readBoolean();
            mForceTranslucent = in.readBoolean();
            mChangeMask = in.readInt();
            mConfigSetMask = in.readInt();
            mWindowSetMask = in.readInt();
            if ((mChangeMask & Change.CHANGE_PIP_CALLBACK) != 0) {
                mPinnedBounds = new Rect();
                mPinnedBounds.readFromParcel(in);
            }
            if ((mChangeMask & Change.CHANGE_BOUNDS_TRANSACTION) != 0) {
                mBoundsChangeTransaction =
                    SurfaceControl.Transaction.CREATOR.createFromParcel(in);
            }
            if ((mChangeMask & Change.CHANGE_BOUNDS_TRANSACTION_RECT) != 0) {
                mBoundsChangeSurfaceBounds = new Rect();
                mBoundsChangeSurfaceBounds.readFromParcel(in);
            }

            mWindowingMode = in.readInt();
            mActivityWindowingMode = in.readInt();
        }

        /**
         * @param transfer When true, this will transfer other into this leaving other in an
         *                 undefined state. Use this if you don't intend to use other. When false,
         *                 SurfaceFlinger Transactions will not merge.
         */
        public void merge(Change other, boolean transfer) {
            mConfiguration.setTo(other.mConfiguration, other.mConfigSetMask, other.mWindowSetMask);
            mConfigSetMask |= other.mConfigSetMask;
            mWindowSetMask |= other.mWindowSetMask;
            if ((other.mChangeMask & CHANGE_FOCUSABLE) != 0) {
                mFocusable = other.mFocusable;
            }
            if (transfer && (other.mChangeMask & CHANGE_BOUNDS_TRANSACTION) != 0) {
                mBoundsChangeTransaction = other.mBoundsChangeTransaction;
                other.mBoundsChangeTransaction = null;
            }
            if ((other.mChangeMask & CHANGE_PIP_CALLBACK) != 0) {
                mPinnedBounds = transfer ? other.mPinnedBounds : new Rect(other.mPinnedBounds);
            }
            if ((other.mChangeMask & CHANGE_HIDDEN) != 0) {
                mHidden = other.mHidden;
            }
            if ((other.mChangeMask & CHANGE_IGNORE_ORIENTATION_REQUEST) != 0) {
                mIgnoreOrientationRequest = other.mIgnoreOrientationRequest;
            }
            if ((other.mChangeMask & CHANGE_FORCE_TRANSLUCENT) != 0) {
                mForceTranslucent = other.mForceTranslucent;
            }
            mChangeMask |= other.mChangeMask;
            if (other.mActivityWindowingMode >= 0) {
                mActivityWindowingMode = other.mActivityWindowingMode;
            }
            if (other.mWindowingMode >= 0) {
                mWindowingMode = other.mWindowingMode;
            }
            if (other.mBoundsChangeSurfaceBounds != null) {
                mBoundsChangeSurfaceBounds = transfer ? other.mBoundsChangeSurfaceBounds
                        : new Rect(other.mBoundsChangeSurfaceBounds);
            }
        }

        public int getWindowingMode() {
            return mWindowingMode;
        }

        public int getActivityWindowingMode() {
            return mActivityWindowingMode;
        }

        public Configuration getConfiguration() {
            return mConfiguration;
        }

        /** Gets the requested focusable state */
        public boolean getFocusable() {
            if ((mChangeMask & CHANGE_FOCUSABLE) == 0) {
                throw new RuntimeException("Focusable not set. check CHANGE_FOCUSABLE first");
            }
            return mFocusable;
        }

        /** Gets the requested hidden state */
        public boolean getHidden() {
            if ((mChangeMask & CHANGE_HIDDEN) == 0) {
                throw new RuntimeException("Hidden not set. check CHANGE_HIDDEN first");
            }
            return mHidden;
        }

        /** Gets the requested state of whether to ignore orientation request. */
        public boolean getIgnoreOrientationRequest() {
            if ((mChangeMask & CHANGE_IGNORE_ORIENTATION_REQUEST) == 0) {
                throw new RuntimeException("IgnoreOrientationRequest not set. "
                        + "Check CHANGE_IGNORE_ORIENTATION_REQUEST first");
            }
            return mIgnoreOrientationRequest;
        }

        /** Gets the requested force translucent state. */
        public boolean getForceTranslucent() {
            if ((mChangeMask & CHANGE_FORCE_TRANSLUCENT) == 0) {
                throw new RuntimeException("Force translucent not set. "
                        + "Check CHANGE_FORCE_TRANSLUCENT first");
            }
            return mForceTranslucent;
        }

        public int getChangeMask() {
            return mChangeMask;
        }

        @ActivityInfo.Config
        public int getConfigSetMask() {
            return mConfigSetMask;
        }

        @WindowConfiguration.WindowConfig
        public int getWindowSetMask() {
            return mWindowSetMask;
        }

        /**
         * Returns the bounds to be used for scheduling the enter pip callback
         * or null if no callback is to be scheduled.
         */
        public Rect getEnterPipBounds() {
            return mPinnedBounds;
        }

        public SurfaceControl.Transaction getBoundsChangeTransaction() {
            return mBoundsChangeTransaction;
        }

        public Rect getBoundsChangeSurfaceBounds() {
            return mBoundsChangeSurfaceBounds;
        }

        @Override
        public String toString() {
            final boolean changesBounds =
                    (mConfigSetMask & ActivityInfo.CONFIG_WINDOW_CONFIGURATION) != 0
                            && ((mWindowSetMask & WindowConfiguration.WINDOW_CONFIG_BOUNDS)
                                    != 0);
            final boolean changesAppBounds =
                    (mConfigSetMask & ActivityInfo.CONFIG_WINDOW_CONFIGURATION) != 0
                            && ((mWindowSetMask & WindowConfiguration.WINDOW_CONFIG_APP_BOUNDS)
                                    != 0);
            final boolean changesSs = (mConfigSetMask & ActivityInfo.CONFIG_SCREEN_SIZE) != 0;
            final boolean changesSss =
                    (mConfigSetMask & ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE) != 0;
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            if (changesBounds) {
                sb.append("bounds:" + mConfiguration.windowConfiguration.getBounds() + ",");
            }
            if (changesAppBounds) {
                sb.append("appbounds:" + mConfiguration.windowConfiguration.getAppBounds() + ",");
            }
            if (changesSss) {
                sb.append("ssw:" + mConfiguration.smallestScreenWidthDp + ",");
            }
            if (changesSs) {
                sb.append("sw/h:" + mConfiguration.screenWidthDp + "x"
                        + mConfiguration.screenHeightDp + ",");
            }
            if ((mChangeMask & CHANGE_FOCUSABLE) != 0) {
                sb.append("focusable:" + mFocusable + ",");
            }
            if (mBoundsChangeTransaction != null) {
                sb.append("hasBoundsTransaction,");
            }
            if ((mChangeMask & CHANGE_IGNORE_ORIENTATION_REQUEST) != 0) {
                sb.append("ignoreOrientationRequest:" + mIgnoreOrientationRequest + ",");
            }
            sb.append("}");
            return sb.toString();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            mConfiguration.writeToParcel(dest, flags);
            dest.writeBoolean(mFocusable);
            dest.writeBoolean(mHidden);
            dest.writeBoolean(mIgnoreOrientationRequest);
            dest.writeBoolean(mForceTranslucent);
            dest.writeInt(mChangeMask);
            dest.writeInt(mConfigSetMask);
            dest.writeInt(mWindowSetMask);

            if (mPinnedBounds != null) {
                mPinnedBounds.writeToParcel(dest, flags);
            }
            if (mBoundsChangeTransaction != null) {
                mBoundsChangeTransaction.writeToParcel(dest, flags);
            }
            if (mBoundsChangeSurfaceBounds != null) {
                mBoundsChangeSurfaceBounds.writeToParcel(dest, flags);
            }

            dest.writeInt(mWindowingMode);
            dest.writeInt(mActivityWindowingMode);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<Change> CREATOR = new Creator<Change>() {
            @Override
            public Change createFromParcel(Parcel in) {
                return new Change(in);
            }

            @Override
            public Change[] newArray(int size) {
                return new Change[size];
            }
        };
    }

    /**
     * Holds information about a reparent/reorder operation in the hierarchy. This is separate from
     * Changes because they must be executed in the same order that they are added.
     * @hide
     */
    public static final class HierarchyOp implements Parcelable {
        public static final int HIERARCHY_OP_TYPE_REPARENT = 0;
        public static final int HIERARCHY_OP_TYPE_REORDER = 1;
        public static final int HIERARCHY_OP_TYPE_CHILDREN_TASKS_REPARENT = 2;
        public static final int HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT = 3;
        public static final int HIERARCHY_OP_TYPE_SET_ADJACENT_ROOTS = 4;
        public static final int HIERARCHY_OP_TYPE_LAUNCH_TASK = 5;
        public static final int HIERARCHY_OP_TYPE_SET_LAUNCH_ADJACENT_FLAG_ROOT = 6;
        public static final int HIERARCHY_OP_TYPE_CREATE_TASK_FRAGMENT = 7;
        public static final int HIERARCHY_OP_TYPE_DELETE_TASK_FRAGMENT = 8;
        public static final int HIERARCHY_OP_TYPE_START_ACTIVITY_IN_TASK_FRAGMENT = 9;
        public static final int HIERARCHY_OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT = 10;
        public static final int HIERARCHY_OP_TYPE_REPARENT_CHILDREN = 11;
        public static final int HIERARCHY_OP_TYPE_PENDING_INTENT = 12;
        public static final int HIERARCHY_OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS = 13;
        public static final int HIERARCHY_OP_TYPE_START_SHORTCUT = 14;
        public static final int HIERARCHY_OP_TYPE_RESTORE_TRANSIENT_ORDER = 15;
        public static final int HIERARCHY_OP_TYPE_ADD_RECT_INSETS_PROVIDER = 16;
        public static final int HIERARCHY_OP_TYPE_REMOVE_INSETS_PROVIDER = 17;
        public static final int HIERARCHY_OP_TYPE_REQUEST_FOCUS_ON_TASK_FRAGMENT = 18;
        public static final int HIERARCHY_OP_TYPE_SET_ALWAYS_ON_TOP = 19;
        public static final int HIERARCHY_OP_TYPE_REMOVE_TASK = 20;
        public static final int HIERARCHY_OP_TYPE_FINISH_ACTIVITY = 21;

        // The following key(s) are for use with mLaunchOptions:
        // When launching a task (eg. from recents), this is the taskId to be launched.
        public static final String LAUNCH_KEY_TASK_ID = "android:transaction.hop.taskId";

        // When starting from a shortcut, this contains the calling package.
        public static final String LAUNCH_KEY_SHORTCUT_CALLING_PACKAGE =
                "android:transaction.hop.shortcut_calling_package";

        private final int mType;

        // Container we are performing the operation on.
        @Nullable
        private IBinder mContainer;

        // If this is same as mContainer, then only change position, don't reparent.
        @Nullable
        private IBinder mReparent;

        private @InsetsState.InternalInsetsType int[] mInsetsTypes;

        private Rect mInsetsProviderFrame;

        // Moves/reparents to top of parent when {@code true}, otherwise moves/reparents to bottom.
        private boolean mToTop;

        private boolean mReparentTopOnly;

        @Nullable
        private int[]  mWindowingModes;

        @Nullable
        private int[] mActivityTypes;

        @Nullable
        private Bundle mLaunchOptions;

        @Nullable
        private Intent mActivityIntent;

        // Used as options for WindowContainerTransaction#createTaskFragment().
        @Nullable
        private TaskFragmentCreationParams mTaskFragmentCreationOptions;

        @Nullable
        private PendingIntent mPendingIntent;

        @Nullable
        private ShortcutInfo mShortcutInfo;

        private boolean mAlwaysOnTop;

        public static HierarchyOp createForReparent(
                @NonNull IBinder container, @Nullable IBinder reparent, boolean toTop) {
            return new HierarchyOp.Builder(HIERARCHY_OP_TYPE_REPARENT)
                    .setContainer(container)
                    .setReparentContainer(reparent)
                    .setToTop(toTop)
                    .build();
        }

        public static HierarchyOp createForReorder(@NonNull IBinder container, boolean toTop) {
            return new HierarchyOp.Builder(HIERARCHY_OP_TYPE_REORDER)
                    .setContainer(container)
                    .setReparentContainer(container)
                    .setToTop(toTop)
                    .build();
        }

        public static HierarchyOp createForChildrenTasksReparent(IBinder currentParent,
                IBinder newParent, int[] windowingModes, int[] activityTypes, boolean onTop,
                boolean reparentTopOnly) {
            return new HierarchyOp.Builder(HIERARCHY_OP_TYPE_CHILDREN_TASKS_REPARENT)
                    .setContainer(currentParent)
                    .setReparentContainer(newParent)
                    .setWindowingModes(windowingModes)
                    .setActivityTypes(activityTypes)
                    .setToTop(onTop)
                    .setReparentTopOnly(reparentTopOnly)
                    .build();
        }

        public static HierarchyOp createForSetLaunchRoot(IBinder container,
                int[] windowingModes, int[] activityTypes) {
            return new HierarchyOp.Builder(HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT)
                    .setContainer(container)
                    .setWindowingModes(windowingModes)
                    .setActivityTypes(activityTypes)
                    .build();
        }

        /** Create a hierarchy op for setting adjacent root tasks. */
        public static HierarchyOp createForAdjacentRoots(IBinder root1, IBinder root2) {
            return new HierarchyOp.Builder(HIERARCHY_OP_TYPE_SET_ADJACENT_ROOTS)
                    .setContainer(root1)
                    .setReparentContainer(root2)
                    .build();
        }

        /** Create a hierarchy op for launching a task. */
        public static HierarchyOp createForTaskLaunch(int taskId, @Nullable Bundle options) {
            final Bundle fullOptions = options == null ? new Bundle() : options;
            fullOptions.putInt(LAUNCH_KEY_TASK_ID, taskId);
            return new HierarchyOp.Builder(HIERARCHY_OP_TYPE_LAUNCH_TASK)
                    .setToTop(true)
                    .setLaunchOptions(fullOptions)
                    .build();
        }

        /** Create a hierarchy op for starting a shortcut. */
        public static HierarchyOp createForStartShortcut(@NonNull String callingPackage,
                @NonNull ShortcutInfo shortcutInfo, @Nullable Bundle options) {
            final Bundle fullOptions = options == null ? new Bundle() : options;
            fullOptions.putString(LAUNCH_KEY_SHORTCUT_CALLING_PACKAGE, callingPackage);
            return new HierarchyOp.Builder(HIERARCHY_OP_TYPE_START_SHORTCUT)
                    .setShortcutInfo(shortcutInfo)
                    .setLaunchOptions(fullOptions)
                    .build();
        }

        /** Create a hierarchy op for setting launch adjacent flag root. */
        public static HierarchyOp createForSetLaunchAdjacentFlagRoot(IBinder container,
                boolean clearRoot) {
            return new HierarchyOp.Builder(HIERARCHY_OP_TYPE_SET_LAUNCH_ADJACENT_FLAG_ROOT)
                    .setContainer(container)
                    .setToTop(clearRoot)
                    .build();
        }

        /** create a hierarchy op for deleting a task **/
        public static HierarchyOp createForRemoveTask(@NonNull IBinder container) {
            return new HierarchyOp.Builder(HIERARCHY_OP_TYPE_REMOVE_TASK)
                    .setContainer(container)
                    .build();
        }

        /** Only creates through {@link Builder}. */
        private HierarchyOp(int type) {
            mType = type;
        }

        public HierarchyOp(@NonNull HierarchyOp copy) {
            mType = copy.mType;
            mContainer = copy.mContainer;
            mReparent = copy.mReparent;
            mInsetsTypes = copy.mInsetsTypes;
            mInsetsProviderFrame = copy.mInsetsProviderFrame;
            mToTop = copy.mToTop;
            mReparentTopOnly = copy.mReparentTopOnly;
            mWindowingModes = copy.mWindowingModes;
            mActivityTypes = copy.mActivityTypes;
            mLaunchOptions = copy.mLaunchOptions;
            mActivityIntent = copy.mActivityIntent;
            mTaskFragmentCreationOptions = copy.mTaskFragmentCreationOptions;
            mPendingIntent = copy.mPendingIntent;
            mShortcutInfo = copy.mShortcutInfo;
            mAlwaysOnTop = copy.mAlwaysOnTop;
        }

        protected HierarchyOp(Parcel in) {
            mType = in.readInt();
            mContainer = in.readStrongBinder();
            mReparent = in.readStrongBinder();
            mInsetsTypes = in.createIntArray();
            if (in.readInt() != 0) {
                mInsetsProviderFrame = Rect.CREATOR.createFromParcel(in);
            } else {
                mInsetsProviderFrame = null;
            }
            mToTop = in.readBoolean();
            mReparentTopOnly = in.readBoolean();
            mWindowingModes = in.createIntArray();
            mActivityTypes = in.createIntArray();
            mLaunchOptions = in.readBundle();
            mActivityIntent = in.readTypedObject(Intent.CREATOR);
            mTaskFragmentCreationOptions = in.readTypedObject(TaskFragmentCreationParams.CREATOR);
            mPendingIntent = in.readTypedObject(PendingIntent.CREATOR);
            mShortcutInfo = in.readTypedObject(ShortcutInfo.CREATOR);
            mAlwaysOnTop = in.readBoolean();
        }

        public int getType() {
            return mType;
        }

        public boolean isReparent() {
            return mType == HIERARCHY_OP_TYPE_REPARENT;
        }

        @Nullable
        public IBinder getNewParent() {
            return mReparent;
        }

        @Nullable
        public @InsetsState.InternalInsetsType int[] getInsetsTypes() {
            return mInsetsTypes;
        }

        public Rect getInsetsProviderFrame() {
            return mInsetsProviderFrame;
        }

        @NonNull
        public IBinder getContainer() {
            return mContainer;
        }

        @NonNull
        public IBinder getAdjacentRoot() {
            return mReparent;
        }

        @NonNull
        public IBinder getCallingActivity() {
            return mReparent;
        }

        public boolean getToTop() {
            return mToTop;
        }

        public boolean getReparentTopOnly() {
            return mReparentTopOnly;
        }

        public int[] getWindowingModes() {
            return mWindowingModes;
        }

        public int[] getActivityTypes() {
            return mActivityTypes;
        }

        @Nullable
        public Bundle getLaunchOptions() {
            return mLaunchOptions;
        }

        @Nullable
        public Intent getActivityIntent() {
            return mActivityIntent;
        }

        public boolean isAlwaysOnTop() {
            return mAlwaysOnTop;
        }

        @Nullable
        public TaskFragmentCreationParams getTaskFragmentCreationOptions() {
            return mTaskFragmentCreationOptions;
        }

        @Nullable
        public PendingIntent getPendingIntent() {
            return mPendingIntent;
        }

        @Nullable
        public ShortcutInfo getShortcutInfo() {
            return mShortcutInfo;
        }

        @Override
        public String toString() {
            switch (mType) {
                case HIERARCHY_OP_TYPE_CHILDREN_TASKS_REPARENT:
                    return "{ChildrenTasksReparent: from=" + mContainer + " to=" + mReparent
                            + " mToTop=" + mToTop + " mReparentTopOnly=" + mReparentTopOnly
                            + " mWindowingMode=" + Arrays.toString(mWindowingModes)
                            + " mActivityType=" + Arrays.toString(mActivityTypes) + "}";
                case HIERARCHY_OP_TYPE_SET_LAUNCH_ROOT:
                    return "{SetLaunchRoot: container=" + mContainer
                            + " mWindowingMode=" + Arrays.toString(mWindowingModes)
                            + " mActivityType=" + Arrays.toString(mActivityTypes) + "}";
                case HIERARCHY_OP_TYPE_REPARENT:
                    return "{reparent: " + mContainer + " to " + (mToTop ? "top of " : "bottom of ")
                            + mReparent + "}";
                case HIERARCHY_OP_TYPE_REORDER:
                    return "{reorder: " + mContainer + " to " + (mToTop ? "top" : "bottom") + "}";
                case HIERARCHY_OP_TYPE_SET_ADJACENT_ROOTS:
                    return "{SetAdjacentRoot: container=" + mContainer
                            + " adjacentRoot=" + mReparent + "}";
                case HIERARCHY_OP_TYPE_LAUNCH_TASK:
                    return "{LaunchTask: " + mLaunchOptions + "}";
                case HIERARCHY_OP_TYPE_SET_LAUNCH_ADJACENT_FLAG_ROOT:
                    return "{SetAdjacentFlagRoot: container=" + mContainer + " clearRoot=" + mToTop
                            + "}";
                case HIERARCHY_OP_TYPE_CREATE_TASK_FRAGMENT:
                    return "{CreateTaskFragment: options=" + mTaskFragmentCreationOptions + "}";
                case HIERARCHY_OP_TYPE_DELETE_TASK_FRAGMENT:
                    return "{DeleteTaskFragment: taskFragment=" + mContainer + "}";
                case HIERARCHY_OP_TYPE_START_ACTIVITY_IN_TASK_FRAGMENT:
                    return "{StartActivityInTaskFragment: fragmentToken=" + mContainer + " intent="
                            + mActivityIntent + " options=" + mLaunchOptions + "}";
                case HIERARCHY_OP_TYPE_REPARENT_ACTIVITY_TO_TASK_FRAGMENT:
                    return "{ReparentActivityToTaskFragment: fragmentToken=" + mReparent
                            + " activity=" + mContainer + "}";
                case HIERARCHY_OP_TYPE_REPARENT_CHILDREN:
                    return "{ReparentChildren: oldParent=" + mContainer + " newParent=" + mReparent
                            + "}";
                case HIERARCHY_OP_TYPE_SET_ADJACENT_TASK_FRAGMENTS:
                    return "{SetAdjacentTaskFragments: container=" + mContainer
                            + " adjacentContainer=" + mReparent + "}";
                case HIERARCHY_OP_TYPE_START_SHORTCUT:
                    return "{StartShortcut: options=" + mLaunchOptions + " info=" + mShortcutInfo
                            + "}";
                case HIERARCHY_OP_TYPE_ADD_RECT_INSETS_PROVIDER:
                    return "{addRectInsetsProvider: container=" + mContainer
                            + " insetsProvidingFrame=" + mInsetsProviderFrame
                            + " insetsType=" + Arrays.toString(mInsetsTypes) + "}";
                case HIERARCHY_OP_TYPE_REMOVE_INSETS_PROVIDER:
                    return "{removeLocalInsetsProvider: container=" + mContainer
                            + " insetsType=" + Arrays.toString(mInsetsTypes) + "}";
                case HIERARCHY_OP_TYPE_REQUEST_FOCUS_ON_TASK_FRAGMENT:
                    return "{requestFocusOnTaskFragment: container=" + mContainer + "}";
                case HIERARCHY_OP_TYPE_SET_ALWAYS_ON_TOP:
                    return "{setAlwaysOnTop: container=" + mContainer
                            + " alwaysOnTop=" + mAlwaysOnTop + "}";
                case HIERARCHY_OP_TYPE_REMOVE_TASK:
                    return "{RemoveTask: task=" + mContainer + "}";
                case HIERARCHY_OP_TYPE_FINISH_ACTIVITY:
                    return "{finishActivity: activity=" + mContainer + "}";
                default:
                    return "{mType=" + mType + " container=" + mContainer + " reparent=" + mReparent
                            + " mToTop=" + mToTop
                            + " mWindowingMode=" + Arrays.toString(mWindowingModes)
                            + " mActivityType=" + Arrays.toString(mActivityTypes) + "}";
            }
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mType);
            dest.writeStrongBinder(mContainer);
            dest.writeStrongBinder(mReparent);
            dest.writeIntArray(mInsetsTypes);
            if (mInsetsProviderFrame != null) {
                dest.writeInt(1);
                mInsetsProviderFrame.writeToParcel(dest, 0);
            } else {
                dest.writeInt(0);
            }
            dest.writeBoolean(mToTop);
            dest.writeBoolean(mReparentTopOnly);
            dest.writeIntArray(mWindowingModes);
            dest.writeIntArray(mActivityTypes);
            dest.writeBundle(mLaunchOptions);
            dest.writeTypedObject(mActivityIntent, flags);
            dest.writeTypedObject(mTaskFragmentCreationOptions, flags);
            dest.writeTypedObject(mPendingIntent, flags);
            dest.writeTypedObject(mShortcutInfo, flags);
            dest.writeBoolean(mAlwaysOnTop);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<HierarchyOp> CREATOR = new Creator<HierarchyOp>() {
            @Override
            public HierarchyOp createFromParcel(Parcel in) {
                return new HierarchyOp(in);
            }

            @Override
            public HierarchyOp[] newArray(int size) {
                return new HierarchyOp[size];
            }
        };

        private static class Builder {

            private final int mType;

            @Nullable
            private IBinder mContainer;

            @Nullable
            private IBinder mReparent;

            private int[] mInsetsTypes;

            private Rect mInsetsProviderFrame;

            private boolean mToTop;

            private boolean mReparentTopOnly;

            @Nullable
            private int[]  mWindowingModes;

            @Nullable
            private int[] mActivityTypes;

            @Nullable
            private Bundle mLaunchOptions;

            @Nullable
            private Intent mActivityIntent;

            @Nullable
            private TaskFragmentCreationParams mTaskFragmentCreationOptions;

            @Nullable
            private PendingIntent mPendingIntent;

            @Nullable
            private ShortcutInfo mShortcutInfo;

            private boolean mAlwaysOnTop;

            Builder(int type) {
                mType = type;
            }

            Builder setContainer(@Nullable IBinder container) {
                mContainer = container;
                return this;
            }

            Builder setReparentContainer(@Nullable IBinder reparentContainer) {
                mReparent = reparentContainer;
                return this;
            }

            Builder setInsetsTypes(int[] insetsTypes) {
                mInsetsTypes = insetsTypes;
                return this;
            }

            Builder setInsetsProviderFrame(Rect insetsProviderFrame) {
                mInsetsProviderFrame = insetsProviderFrame;
                return this;
            }

            Builder setToTop(boolean toTop) {
                mToTop = toTop;
                return this;
            }

            Builder setReparentTopOnly(boolean reparentTopOnly) {
                mReparentTopOnly = reparentTopOnly;
                return this;
            }

            Builder setWindowingModes(@Nullable int[] windowingModes) {
                mWindowingModes = windowingModes;
                return this;
            }

            Builder setActivityTypes(@Nullable int[] activityTypes) {
                mActivityTypes = activityTypes;
                return this;
            }

            Builder setLaunchOptions(@Nullable Bundle launchOptions) {
                mLaunchOptions = launchOptions;
                return this;
            }

            Builder setActivityIntent(@Nullable Intent activityIntent) {
                mActivityIntent = activityIntent;
                return this;
            }

            Builder setPendingIntent(@Nullable PendingIntent sender) {
                mPendingIntent = sender;
                return this;
            }

            Builder setAlwaysOnTop(boolean alwaysOnTop) {
                mAlwaysOnTop = alwaysOnTop;
                return this;
            }

            Builder setTaskFragmentCreationOptions(
                    @Nullable TaskFragmentCreationParams taskFragmentCreationOptions) {
                mTaskFragmentCreationOptions = taskFragmentCreationOptions;
                return this;
            }

            Builder setShortcutInfo(@Nullable ShortcutInfo shortcutInfo) {
                mShortcutInfo = shortcutInfo;
                return this;
            }

            HierarchyOp build() {
                final HierarchyOp hierarchyOp = new HierarchyOp(mType);
                hierarchyOp.mContainer = mContainer;
                hierarchyOp.mReparent = mReparent;
                hierarchyOp.mWindowingModes = mWindowingModes != null
                        ? Arrays.copyOf(mWindowingModes, mWindowingModes.length)
                        : null;
                hierarchyOp.mActivityTypes = mActivityTypes != null
                        ? Arrays.copyOf(mActivityTypes, mActivityTypes.length)
                        : null;
                hierarchyOp.mInsetsTypes = mInsetsTypes;
                hierarchyOp.mInsetsProviderFrame = mInsetsProviderFrame;
                hierarchyOp.mToTop = mToTop;
                hierarchyOp.mReparentTopOnly = mReparentTopOnly;
                hierarchyOp.mLaunchOptions = mLaunchOptions;
                hierarchyOp.mActivityIntent = mActivityIntent;
                hierarchyOp.mPendingIntent = mPendingIntent;
                hierarchyOp.mAlwaysOnTop = mAlwaysOnTop;
                hierarchyOp.mTaskFragmentCreationOptions = mTaskFragmentCreationOptions;
                hierarchyOp.mShortcutInfo = mShortcutInfo;

                return hierarchyOp;
            }
        }
    }

    /**
     * Helper class for building an options Bundle that can be used to set adjacent rules of
     * TaskFragments.
     */
    public static class TaskFragmentAdjacentParams {
        private static final String DELAY_PRIMARY_LAST_ACTIVITY_REMOVAL =
                "android:transaction.adjacent.option.delay_primary_removal";
        private static final String DELAY_SECONDARY_LAST_ACTIVITY_REMOVAL =
                "android:transaction.adjacent.option.delay_secondary_removal";

        private boolean mDelayPrimaryLastActivityRemoval;
        private boolean mDelaySecondaryLastActivityRemoval;

        public TaskFragmentAdjacentParams() {
        }

        public TaskFragmentAdjacentParams(@NonNull Bundle bundle) {
            mDelayPrimaryLastActivityRemoval = bundle.getBoolean(
                    DELAY_PRIMARY_LAST_ACTIVITY_REMOVAL);
            mDelaySecondaryLastActivityRemoval = bundle.getBoolean(
                    DELAY_SECONDARY_LAST_ACTIVITY_REMOVAL);
        }

        /** @see #shouldDelayPrimaryLastActivityRemoval() */
        public void setShouldDelayPrimaryLastActivityRemoval(boolean delay) {
            mDelayPrimaryLastActivityRemoval = delay;
        }

        /** @see #shouldDelaySecondaryLastActivityRemoval() */
        public void setShouldDelaySecondaryLastActivityRemoval(boolean delay) {
            mDelaySecondaryLastActivityRemoval = delay;
        }

        /**
         * Whether to delay the last activity of the primary adjacent TaskFragment being immediately
         * removed while finishing.
         * <p>
         * It is usually set to {@code true} to give organizer an opportunity to perform other
         * actions or animations. An example is to finish together with the adjacent TaskFragment.
         * </p>
         */
        public boolean shouldDelayPrimaryLastActivityRemoval() {
            return mDelayPrimaryLastActivityRemoval;
        }

        /**
         * Similar to {@link #shouldDelayPrimaryLastActivityRemoval()}, but for the secondary
         * TaskFragment.
         */
        public boolean shouldDelaySecondaryLastActivityRemoval() {
            return mDelaySecondaryLastActivityRemoval;
        }

        Bundle toBundle() {
            final Bundle b = new Bundle();
            b.putBoolean(DELAY_PRIMARY_LAST_ACTIVITY_REMOVAL, mDelayPrimaryLastActivityRemoval);
            b.putBoolean(DELAY_SECONDARY_LAST_ACTIVITY_REMOVAL, mDelaySecondaryLastActivityRemoval);
            return b;
        }
    }
}
