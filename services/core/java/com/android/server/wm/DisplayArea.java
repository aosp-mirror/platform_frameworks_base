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

package com.android.server.wm;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.WindowManagerPolicyConstants.APPLICATION_LAYER;
import static android.window.DisplayAreaOrganizer.FEATURE_UNDEFINED;
import static android.window.DisplayAreaOrganizer.FEATURE_WINDOW_TOKENS;

import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_ORIENTATION;
import static com.android.internal.util.Preconditions.checkState;
import static com.android.server.wm.DisplayAreaProto.FEATURE_ID;
import static com.android.server.wm.DisplayAreaProto.IS_IGNORING_ORIENTATION_REQUEST;
import static com.android.server.wm.DisplayAreaProto.IS_ORGANIZED;
import static com.android.server.wm.DisplayAreaProto.IS_ROOT_DISPLAY_AREA;
import static com.android.server.wm.DisplayAreaProto.IS_TASK_DISPLAY_AREA;
import static com.android.server.wm.DisplayAreaProto.NAME;
import static com.android.server.wm.DisplayAreaProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowContainerChildProto.DISPLAY_AREA;

import android.annotation.Nullable;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.util.proto.ProtoOutputStream;
import android.window.DisplayAreaInfo;
import android.window.IDisplayAreaOrganizer;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.ProtoLog;
import com.android.server.policy.WindowManagerPolicy;

import java.io.PrintWriter;
import java.util.Comparator;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
/**
 * Container for grouping WindowContainer below DisplayContent.
 *
 * DisplayAreas are managed by a {@link DisplayAreaPolicy}, and can override configurations and
 * can be leashed.
 *
 * DisplayAreas can contain nested DisplayAreas.
 *
 * DisplayAreas come in three flavors, to ensure that windows have the right Z-Order:
 * - BELOW_TASKS: Can only contain BELOW_TASK DisplayAreas and WindowTokens that go below tasks.
 * - ABOVE_TASKS: Can only contain ABOVE_TASK DisplayAreas and WindowTokens that go above tasks.
 * - ANY: Can contain any kind of DisplayArea, and any kind of WindowToken or the Task container.
 *
 * @param <T> type of the children of the DisplayArea.
 */
public class DisplayArea<T extends WindowContainer> extends WindowContainer<T> {

    protected final Type mType;
    private final String mName;
    final int mFeatureId;
    private final DisplayAreaOrganizerController mOrganizerController;
    IDisplayAreaOrganizer mOrganizer;
    private final Configuration mTmpConfiguration = new Configuration();

    /**
     * Prevent duplicate calls to onDisplayAreaAppeared, or early call of onDisplayAreaInfoChanged.
     */
    @VisibleForTesting
    boolean mDisplayAreaAppearedSent;

    /**
     * Whether this {@link DisplayArea} should ignore fixed-orientation request. If {@code true}, it
     * can never specify orientation, but shows the fixed-orientation apps below it in the
     * letterbox; otherwise, it rotates based on the fixed-orientation request.
     *
     * <p>Note: use {@link #getIgnoreOrientationRequest} to access outside of {@link
     * #setIgnoreOrientationRequest} since the value can be overridden at runtime on a device level.
     */
    protected boolean mSetIgnoreOrientationRequest;

    DisplayArea(WindowManagerService wms, Type type, String name) {
        this(wms, type, name, FEATURE_UNDEFINED);
    }

    DisplayArea(WindowManagerService wms, Type type, String name, int featureId) {
        super(wms);
        // TODO(display-area): move this up to ConfigurationContainer
        setOverrideOrientation(SCREEN_ORIENTATION_UNSET);
        mType = type;
        mName = name;
        mFeatureId = featureId;
        mRemoteToken = new RemoteToken(this);
        mOrganizerController =
                wms.mAtmService.mWindowOrganizerController.mDisplayAreaOrganizerController;
    }

    @Override
    void onChildPositionChanged(WindowContainer child) {
        super.onChildPositionChanged(child);

        // Verify that we have proper ordering
        Type.checkChild(mType, Type.typeOf(child));

        if (child instanceof Task) {
            // TODO(display-area): ActivityStacks are type ANY, but are allowed to have siblings.
            //                     They might need a separate type.
            return;
        }

        for (int i = 1; i < getChildCount(); i++) {
            final WindowContainer top = getChildAt(i - 1);
            final WindowContainer bottom = getChildAt(i);
            if (child == top || child == bottom) {
                Type.checkSiblings(Type.typeOf(top), Type.typeOf(bottom));
            }
        }
    }

    @Override
    void positionChildAt(int position, T child, boolean includingParents) {
        if (child.asDisplayArea() == null) {
            // Reposition other window containers as normal.
            super.positionChildAt(position, child, includingParents);
            return;
        }

        final int targetPosition = findPositionForChildDisplayArea(position, child.asDisplayArea());
        super.positionChildAt(targetPosition, child, false /* includingParents */);

        final WindowContainer parent = getParent();
        if (includingParents && parent != null
                && (position == POSITION_TOP || position == POSITION_BOTTOM)) {
            parent.positionChildAt(position, this /* child */, true /* includingParents */);
        }
    }

    @Override
    @ScreenOrientation
    int getOrientation(int candidate) {
        final int orientation = super.getOrientation(candidate);
        if (shouldIgnoreOrientationRequest(orientation)) {
            // In all the other case, mLastOrientationSource will be reassigned to a new value
            mLastOrientationSource = null;
            return SCREEN_ORIENTATION_UNSET;
        }
        return orientation;
    }

    @Override
    boolean handlesOrientationChangeFromDescendant(@ScreenOrientation int orientation) {
        return !shouldIgnoreOrientationRequest(orientation)
                && super.handlesOrientationChangeFromDescendant(orientation);
    }

    @Override
    boolean onDescendantOrientationChanged(@Nullable WindowContainer requestingContainer) {
        // If this is set to ignore the orientation request, we don't propagate descendant
        // orientation request.
        final int orientation = requestingContainer != null
                ? requestingContainer.getOverrideOrientation()
                : SCREEN_ORIENTATION_UNSET;
        return !shouldIgnoreOrientationRequest(orientation)
                && super.onDescendantOrientationChanged(requestingContainer);
    }

    /**
     * Sets whether this {@link DisplayArea} should ignore fixed-orientation request from apps and
     * windows below it.
     *
     * @return Whether the display orientation changed after calling this method.
     */
    boolean setIgnoreOrientationRequest(boolean ignoreOrientationRequest) {
        if (mSetIgnoreOrientationRequest == ignoreOrientationRequest) {
            return false;
        }
        mSetIgnoreOrientationRequest = ignoreOrientationRequest;

        // Check whether we should notify Display to update orientation.
        if (mDisplayContent == null) {
            return false;
        }

        if (mDisplayContent.mFocusedApp != null) {
            // We record the last focused TDA that respects orientation request, check if this
            // change may affect it.
            mDisplayContent.onLastFocusedTaskDisplayAreaChanged(
                    mDisplayContent.mFocusedApp.getDisplayArea());
        }

        // The orientation request from this DA may now be respected.
        if (!ignoreOrientationRequest) {
            return mDisplayContent.updateOrientation();
        }

        final int lastOrientation = mDisplayContent.getLastOrientation();
        final WindowContainer lastOrientationSource = mDisplayContent.getLastOrientationSource();
        if (lastOrientation == SCREEN_ORIENTATION_UNSET
                || lastOrientation == SCREEN_ORIENTATION_UNSPECIFIED) {
            // Orientation won't be changed.
            return false;
        }
        if (lastOrientationSource == null || lastOrientationSource.isDescendantOf(this)) {
            // Try update if the orientation may be affected.
            return mDisplayContent.updateOrientation();
        }
        return false;
    }

    @Override
    public void setAlwaysOnTop(boolean alwaysOnTop) {
        if (isAlwaysOnTop() == alwaysOnTop) {
            return;
        }
        super.setAlwaysOnTop(alwaysOnTop);
        // positionChildAtTop() must be called even when always on top gets turned off because
        // we need to make sure that the display area is moved from among always on top containers
        // to below other always on top containers. Since the position the display area should be
        // inserted into is calculated properly in {@link DisplayContent#getTopInsertPosition()}
        // in both cases, we can just request that the root task is put at top here.
        if (getParent().asDisplayArea() != null) {
            getParent().asDisplayArea().positionChildAt(POSITION_TOP, this,
                    false /* includingParents */);
        }
    }

    /**
     * @return {@value true} if we need to ignore the orientation in input.
     */
    boolean shouldIgnoreOrientationRequest(@ScreenOrientation int orientation) {
        // We always respect orientation request for ActivityInfo.SCREEN_ORIENTATION_LOCKED
        // ActivityInfo.SCREEN_ORIENTATION_NOSENSOR.
        // Main use case why this is important is Camera apps that rely on those
        // properties to ensure that they will be able to determine Camera preview
        // orientation correctly
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED
                || orientation == ActivityInfo.SCREEN_ORIENTATION_NOSENSOR) {
            return false;
        }
        return getIgnoreOrientationRequest()
                && !shouldRespectOrientationRequestDueToPerAppOverride();
    }

    private boolean shouldRespectOrientationRequestDueToPerAppOverride() {
        if (mDisplayContent == null) {
            return false;
        }

        // Top running activity can be freeform and ignore orientation request from bottom activity
        // that should be respected, Check all activities in display to make sure any eligible
        // activity should be respected.
        final ActivityRecord activity = mDisplayContent.getActivity((r) ->
                r.mAppCompatController.getAppCompatOrientationOverrides()
                    .shouldRespectRequestedOrientationDueToOverride());
        return activity != null;
    }

    boolean getIgnoreOrientationRequest() {
        // Adding an exception for when ignoreOrientationRequest is overridden at runtime for all
        // DisplayArea-s. For example, this is needed for the Kids Mode since many Kids apps aren't
        // optimised to support both orientations and it will be hard for kids to understand the
        // app compat mode.
        return mSetIgnoreOrientationRequest && !mWmService.isIgnoreOrientationRequestDisabled();
    }

    /**
     * When a {@link DisplayArea} is repositioned, it should only be moved among its siblings of the
     * same {@link Type}.
     * For example, when a {@link DisplayArea} of {@link Type#ANY} is repositioned, it shouldn't be
     * moved above any {@link Type#ABOVE_TASKS} siblings, or below any {@link Type#BELOW_TASKS}
     * siblings.
     */
    private int findPositionForChildDisplayArea(int requestPosition, DisplayArea child) {
        if (child.getParent() != this) {
            throw new IllegalArgumentException("positionChildAt: container=" + child.getName()
                    + " is not a child of container=" + getName()
                    + " current parent=" + child.getParent());
        }

        // The max possible position we can insert the child at.
        int maxPosition = findMaxPositionForChildDisplayArea(child);
        // The min possible position we can insert the child at.
        int minPosition = findMinPositionForChildDisplayArea(child);

        // Place all non-always-on-top containers below always-on-top ones.
        int alwaysOnTopCount = 0;
        for (int i = minPosition; i <= maxPosition; i++) {
            if (mChildren.get(i).isAlwaysOnTop()) {
                alwaysOnTopCount++;
            }
        }
        if (child.isAlwaysOnTop()) {
            minPosition = maxPosition - alwaysOnTopCount + 1;
        } else {
            maxPosition -= alwaysOnTopCount;
        }
        return Math.max(Math.min(requestPosition, maxPosition), minPosition);
    }

    private int findMaxPositionForChildDisplayArea(DisplayArea child) {
        final Type childType = Type.typeOf(child);
        for (int i = mChildren.size() - 1; i > 0; i--) {
            if (Type.typeOf(getChildAt(i)) == childType) {
                return i;
            }
        }
        return 0;
    }

    private int findMinPositionForChildDisplayArea(DisplayArea child) {
        final Type childType = Type.typeOf(child);
        for (int i = 0; i < mChildren.size(); i++) {
            if (Type.typeOf(getChildAt(i)) == childType) {
                return i;
            }
        }
        return mChildren.size() - 1;
    }

    @Override
    boolean needsZBoost() {
        // Z Boost should only happen at or below the ActivityStack level.
        return false;
    }

    @Override
    boolean fillsParent() {
        return true;
    }

    @Override
    String getName() {
        return mName;
    }

    @Override
    public String toString() {
        return mName + "@" + System.identityHashCode(this);
    }

    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId, int logLevel) {
        if (logLevel == WindowTracingLogLevel.CRITICAL && !isVisible()) {
            return;
        }

        final long token = proto.start(fieldId);
        super.dumpDebug(proto, WINDOW_CONTAINER, logLevel);
        proto.write(NAME, mName);
        proto.write(IS_TASK_DISPLAY_AREA, isTaskDisplayArea());
        proto.write(IS_ROOT_DISPLAY_AREA, asRootDisplayArea() != null);
        proto.write(FEATURE_ID, mFeatureId);
        proto.write(IS_ORGANIZED, isOrganized());
        proto.write(IS_IGNORING_ORIENTATION_REQUEST, getIgnoreOrientationRequest());
        proto.end(token);
    }

    @Override
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        super.dump(pw, prefix, dumpAll);
        if (mSetIgnoreOrientationRequest) {
            pw.println(prefix + "mSetIgnoreOrientationRequest=true");
        }
        if (hasRequestedOverrideConfiguration()) {
            pw.println(prefix + "overrideConfig=" + getRequestedOverrideConfiguration());
        }
    }

    void dumpChildDisplayArea(PrintWriter pw, String prefix, boolean dumpAll) {
        final String doublePrefix = prefix + "  ";
        for (int i = getChildCount() - 1; i >= 0; i--) {
            final DisplayArea<?> childArea = getChildAt(i).asDisplayArea();
            if (childArea == null) {
                continue;
            }
            pw.print(prefix + "* " + childArea.getName());
            if (childArea.isOrganized()) {
                pw.print(" (organized)");
            }
            pw.println();
            if (childArea.isTaskDisplayArea()) {
                // TaskDisplayArea can only contain task. And it is already printed by display.
                continue;
            }
            childArea.dump(pw, doublePrefix, dumpAll);
            childArea.dumpChildDisplayArea(pw, doublePrefix, dumpAll);
        }
    }

    @Override
    long getProtoFieldId() {
        return DISPLAY_AREA;
    }

    @Override
    final DisplayArea asDisplayArea() {
        return this;
    }

    /** Cheap way of doing cast and instanceof. */
    DisplayArea.Tokens asTokens() {
        return null;
    }

    @Override
    ActivityRecord getActivity(Predicate<ActivityRecord> callback, boolean traverseTopToBottom,
            ActivityRecord boundary) {
        if (mType == Type.ABOVE_TASKS) {
            return null;
        }
        return super.getActivity(callback, traverseTopToBottom, boundary);
    }

    @Override
    Task getTask(Predicate<Task> callback, boolean traverseTopToBottom) {
        if (mType == Type.ABOVE_TASKS) {
            return null;
        }
        return super.getTask(callback, traverseTopToBottom);
    }

    @Override
    Task getRootTask(Predicate<Task> callback, boolean traverseTopToBottom) {
        if (mType == Type.ABOVE_TASKS) {
            return null;
        }
        return super.getRootTask(callback, traverseTopToBottom);
    }

    @Override
    boolean forAllActivities(Predicate<ActivityRecord> callback, boolean traverseTopToBottom) {
        if (mType == Type.ABOVE_TASKS) {
            return false;
        }
        return super.forAllActivities(callback, traverseTopToBottom);
    }

    @Override
    void forAllActivities(Consumer<ActivityRecord> callback, boolean traverseTopToBottom) {
        if (mType == Type.ABOVE_TASKS) {
            return;
        }
        super.forAllActivities(callback, traverseTopToBottom);
    }

    @Override
    boolean forAllRootTasks(Predicate<Task> callback, boolean traverseTopToBottom) {
        if (mType == Type.ABOVE_TASKS) {
            return false;
        }
        return super.forAllRootTasks(callback, traverseTopToBottom);
    }

    @Override
    boolean forAllTasks(Predicate<Task> callback) {
        if (mType == Type.ABOVE_TASKS) {
            return false;
        }
        return super.forAllTasks(callback);
    }

    @Override
    boolean forAllLeafTasks(Predicate<Task> callback) {
        if (mType == Type.ABOVE_TASKS) {
            return false;
        }
        return super.forAllLeafTasks(callback);
    }

    @Override
    void forAllLeafTasks(Consumer<Task> callback, boolean traverseTopToBottom) {
        if (mType == Type.ABOVE_TASKS) {
            return;
        }
        super.forAllLeafTasks(callback, traverseTopToBottom);
    }

    @Override
    boolean forAllLeafTaskFragments(Predicate<TaskFragment> callback) {
        if (mType == Type.ABOVE_TASKS) {
            return false;
        }
        return super.forAllLeafTaskFragments(callback);
    }

    @Override
    void forAllDisplayAreas(Consumer<DisplayArea> callback) {
        super.forAllDisplayAreas(callback);
        callback.accept(this);
    }

    @Override
    boolean forAllTaskDisplayAreas(Predicate<TaskDisplayArea> callback,
            boolean traverseTopToBottom) {
        // Only DisplayArea of Type.ANY may contain TaskDisplayArea as children.
        if (mType != DisplayArea.Type.ANY) {
            return false;
        }

        int childCount = mChildren.size();
        int i = traverseTopToBottom ? childCount - 1 : 0;
        while (i >= 0 && i < childCount) {
            T child = mChildren.get(i);
            // Only traverse if the child is a DisplayArea.
            if (child.asDisplayArea() != null && child.asDisplayArea()
                    .forAllTaskDisplayAreas(callback, traverseTopToBottom)) {
                return true;
            }
            i += traverseTopToBottom ? -1 : 1;
        }
        return false;
    }

    @Override
    void forAllTaskDisplayAreas(Consumer<TaskDisplayArea> callback, boolean traverseTopToBottom) {
        // Only DisplayArea of Type.ANY may contain TaskDisplayArea as children.
        if (mType != DisplayArea.Type.ANY) {
            return;
        }

        int childCount = mChildren.size();
        int i = traverseTopToBottom ? childCount - 1 : 0;
        while (i >= 0 && i < childCount) {
            T child = mChildren.get(i);
            // Only traverse if the child is a DisplayArea.
            if (child.asDisplayArea() != null) {
                child.asDisplayArea().forAllTaskDisplayAreas(callback, traverseTopToBottom);
            }
            i += traverseTopToBottom ? -1 : 1;
        }
    }

    @Nullable
    @Override
    <R> R reduceOnAllTaskDisplayAreas(BiFunction<TaskDisplayArea, R, R> accumulator,
            @Nullable R initValue, boolean traverseTopToBottom) {
        // Only DisplayArea of Type.ANY may contain TaskDisplayArea as children.
        if (mType != DisplayArea.Type.ANY) {
            return initValue;
        }

        int childCount = mChildren.size();
        int i = traverseTopToBottom ? childCount - 1 : 0;
        R result = initValue;
        while (i >= 0 && i < childCount) {
            T child = mChildren.get(i);
            // Only traverse if the child is a DisplayArea.
            if (child.asDisplayArea() != null) {
                result = (R) child.asDisplayArea()
                        .reduceOnAllTaskDisplayAreas(accumulator, result, traverseTopToBottom);
            }
            i += traverseTopToBottom ? -1 : 1;
        }
        return result;
    }

    @Nullable
    @Override
    <R> R getItemFromDisplayAreas(Function<DisplayArea, R> callback) {
        final R item = super.getItemFromDisplayAreas(callback);
        return item != null ? item : callback.apply(this);
    }

    @Nullable
    @Override
    <R> R getItemFromTaskDisplayAreas(Function<TaskDisplayArea, R> callback,
            boolean traverseTopToBottom) {
        // Only DisplayArea of Type.ANY may contain TaskDisplayArea as children.
        if (mType != DisplayArea.Type.ANY) {
            return null;
        }

        int childCount = mChildren.size();
        int i = traverseTopToBottom ? childCount - 1 : 0;
        while (i >= 0 && i < childCount) {
            T child = mChildren.get(i);
            // Only traverse if the child is a DisplayArea.
            if (child.asDisplayArea() != null) {
                R result = (R) child.asDisplayArea()
                        .getItemFromTaskDisplayAreas(callback, traverseTopToBottom);
                if (result != null) {
                    return result;
                }
            }
            i += traverseTopToBottom ? -1 : 1;
        }
        return null;
    }

    void setOrganizer(IDisplayAreaOrganizer organizer) {
        setOrganizer(organizer, false /* skipDisplayAreaAppeared */);
    }

    void setOrganizer(IDisplayAreaOrganizer organizer, boolean skipDisplayAreaAppeared) {
        if (mOrganizer == organizer) return;
        if (mDisplayContent == null || !mDisplayContent.isTrusted()) {
            throw new IllegalStateException(
                    "Don't organize or trigger events for unavailable or untrusted display.");
        }
        IDisplayAreaOrganizer lastOrganizer = mOrganizer;
        // Update the new display area organizer before calling sendDisplayAreaVanished since it
        // could result in a new SurfaceControl getting created that would notify the old organizer
        // about it.
        mOrganizer = organizer;
        sendDisplayAreaVanished(lastOrganizer);
        if (!skipDisplayAreaAppeared) {
            sendDisplayAreaAppeared();
        } else if (organizer != null) {
            // Set as sent since the DisplayAreaAppearedInfo will be sent back when registered.
            mDisplayAreaAppearedSent = true;
        }
    }

    @VisibleForTesting
    void sendDisplayAreaAppeared() {
        if (mOrganizer == null || mDisplayAreaAppearedSent) return;
        mOrganizerController.onDisplayAreaAppeared(mOrganizer, this);
        mDisplayAreaAppearedSent = true;
    }

    @VisibleForTesting
    void sendDisplayAreaInfoChanged() {
        if (mOrganizer == null || !mDisplayAreaAppearedSent) return;
        mOrganizerController.onDisplayAreaInfoChanged(mOrganizer, this);
    }

    @VisibleForTesting
    void sendDisplayAreaVanished(IDisplayAreaOrganizer organizer) {
        if (organizer == null || !mDisplayAreaAppearedSent) return;
        migrateToNewSurfaceControl(getSyncTransaction());
        mOrganizerController.onDisplayAreaVanished(organizer, this);
        mDisplayAreaAppearedSent = false;
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        mTransitionController.collectForDisplayAreaChange(this);
        mTmpConfiguration.setTo(getConfiguration());
        super.onConfigurationChanged(newParentConfig);

        if (mOrganizer != null && getConfiguration().diff(mTmpConfiguration) != 0) {
            sendDisplayAreaInfoChanged();
        }
    }

    @Override
    void resolveOverrideConfiguration(Configuration newParentConfiguration) {
        super.resolveOverrideConfiguration(newParentConfiguration);
        final Configuration resolvedConfig = getResolvedOverrideConfiguration();
        final Rect overrideBounds = resolvedConfig.windowConfiguration.getBounds();
        final Rect overrideAppBounds = resolvedConfig.windowConfiguration.getAppBounds();
        final Rect parentAppBounds = newParentConfiguration.windowConfiguration.getAppBounds();

        // If there is no override of appBounds, restrict appBounds to the override bounds.
        if (!overrideBounds.isEmpty() && (overrideAppBounds == null || overrideAppBounds.isEmpty())
                && parentAppBounds != null && !parentAppBounds.isEmpty()) {
            final Rect appBounds = new Rect(overrideBounds);
            appBounds.intersect(parentAppBounds);
            resolvedConfig.windowConfiguration.setAppBounds(appBounds);
        }
    }

    @Override
    boolean isOrganized() {
        return mOrganizer != null;
    }


    DisplayAreaInfo getDisplayAreaInfo() {
        final DisplayAreaInfo info = new DisplayAreaInfo(mRemoteToken.toWindowContainerToken(),
                getDisplayContent().getDisplayId(), mFeatureId);
        final RootDisplayArea root = getRootDisplayArea();
        info.rootDisplayAreaId = root == null ? getDisplayContent().mFeatureId : root.mFeatureId;
        info.configuration.setTo(getConfiguration());
        return info;
    }

    /**
     * Gets the stable bounds of the DisplayArea, which is the bounds excluding insets for
     * navigation bar, cutout, and status bar.
     */
    void getStableRect(Rect out) {
        if (mDisplayContent == null) {
            getBounds(out);
            return;
        }

        // Intersect with the display stable bounds to get the DisplayArea stable bounds.
        mDisplayContent.getStableRect(out);
        out.intersect(getBounds());
    }

    @Override
    public boolean providesMaxBounds() {
        return true;
    }

    boolean isTaskDisplayArea() {
        return false;
    }

    @Override
    void removeImmediately() {
        setOrganizer(null);
        super.removeImmediately();
    }

    @Override
    DisplayArea getDisplayArea() {
        return this;
    }

    /**
     * DisplayArea that contains WindowTokens, and orders them according to their type.
     */
    public static class Tokens extends DisplayArea<WindowToken> {
        int mLastKeyguardForcedOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

        private final Comparator<WindowToken> mWindowComparator =
                Comparator.comparingInt(WindowToken::getWindowLayerFromType);

        private final Predicate<WindowState> mGetOrientingWindow = w -> {
            if (!w.isVisible() || !w.mLegacyPolicyVisibilityAfterAnim) {
                return false;
            }
            final WindowManagerPolicy policy = mWmService.mPolicy;
            if (policy.isKeyguardHostWindow(w.mAttrs)) {
                // Ignore the orientation of keyguard if it is going away or is not showing while
                // the device is fully awake. In other words, use the orientation of keyguard if
                // its window is visible while the device is going to sleep or is sleeping.
                if (!mDisplayContent.isKeyguardLocked()
                        && mDisplayContent.getDisplayPolicy().isAwake()
                        // Device is not going to sleep.
                        && policy.okToAnimate(true /* ignoreScreenOn */)) {
                    return false;
                }
                // Consider unoccluding only when all unknown visibilities have been
                // resolved, as otherwise we just may be starting another occluding activity.
                final boolean isUnoccluding =
                        mDisplayContent.mAppTransition.isUnoccluding()
                                && mDisplayContent.mUnknownAppVisibilityController.allResolved();
                // If keyguard is showing, or we're unoccluding, force the keyguard's orientation,
                // even if SystemUI hasn't updated the attrs yet.
                if (policy.isKeyguardShowingAndNotOccluded() || isUnoccluding) {
                    return true;
                }
            }
            final int req = w.mAttrs.screenOrientation;
            if (req == SCREEN_ORIENTATION_UNSPECIFIED || req == SCREEN_ORIENTATION_BEHIND
                    || req == SCREEN_ORIENTATION_UNSET) {
                return false;
            }
            return true;
        };

        Tokens(WindowManagerService wms, Type type, String name) {
            this(wms, type, name, FEATURE_WINDOW_TOKENS);
        }

        Tokens(WindowManagerService wms, Type type, String name, int featureId) {
            super(wms, type, name, featureId);
        }

        void addChild(WindowToken token) {
            addChild(token, mWindowComparator);
        }

        @Override
        @ScreenOrientation
        int getOrientation(int candidate) {
            mLastOrientationSource = null;

            // Find a window requesting orientation.
            final WindowState win = getWindow(mGetOrientingWindow);

            if (win == null) {
                return candidate;
            }
            int req = win.mAttrs.screenOrientation;
            ProtoLog.v(WM_DEBUG_ORIENTATION, "%s forcing orientation to %d for display id=%d",
                    win, req, mDisplayContent.getDisplayId());
            if (mWmService.mPolicy.isKeyguardHostWindow(win.mAttrs)) {
                // SystemUI controls the Keyguard orientation asynchronously, and mAttrs may be
                // stale. We record / use the last known override.
                if (req != SCREEN_ORIENTATION_UNSET && req != SCREEN_ORIENTATION_UNSPECIFIED) {
                    mLastKeyguardForcedOrientation = req;
                } else {
                    req = mLastKeyguardForcedOrientation;
                }
            }
            mLastOrientationSource = win;
            return req;
        }

        @Override
        final DisplayArea.Tokens asTokens() {
            return this;
        }
    }

    /**
     * DisplayArea that can be dimmed.
     */
    static class Dimmable extends DisplayArea<DisplayArea> {
        private final Dimmer mDimmer = new Dimmer(this);

        Dimmable(WindowManagerService wms, Type type, String name, int featureId) {
            super(wms, type, name, featureId);
        }

        @Override
        Dimmer getDimmer() {
            return mDimmer;
        }

        @Override
        void prepareSurfaces() {
            mDimmer.resetDimStates();
            super.prepareSurfaces();
            final Rect dimBounds = mDimmer.getDimBounds();
            if (dimBounds != null) {
                // Bounds need to be relative, as the dim layer is a child.
                getBounds(dimBounds);
                dimBounds.offsetTo(0 /* newLeft */, 0 /* newTop */);
            }

            // If SystemUI is dragging for recents, we want to reset the dim state so any dim layer
            // on the display level fades out.
            if (!mTransitionController.isShellTransitionsEnabled()
                    && forAllTasks(task -> !task.canAffectSystemUiFlags())) {
                mDimmer.resetDimStates();
            }

            if (dimBounds != null) {
                if (mDimmer.updateDims(getSyncTransaction())) {
                    scheduleAnimation();
                }
            }
        }
    }

    enum Type {
        /** Can only contain WindowTokens above the APPLICATION_LAYER. */
        ABOVE_TASKS,
        /** Can only contain WindowTokens below the APPLICATION_LAYER. */
        BELOW_TASKS,
        /** Can contain anything. */
        ANY;

        static void checkSiblings(Type bottom, Type top) {
            checkState(!(bottom != BELOW_TASKS && top == BELOW_TASKS),
                    bottom + " must be above BELOW_TASKS");
            checkState(!(bottom == ABOVE_TASKS && top != ABOVE_TASKS),
                    top + " must be below ABOVE_TASKS");
        }

        static void checkChild(Type parent, Type child) {
            switch (parent) {
                case ABOVE_TASKS:
                    checkState(child == ABOVE_TASKS, "ABOVE_TASKS can only contain ABOVE_TASKS");
                    break;
                case BELOW_TASKS:
                    checkState(child == BELOW_TASKS, "BELOW_TASKS can only contain BELOW_TASKS");
                    break;
            }
        }

        static Type typeOf(WindowContainer c) {
            if (c.asDisplayArea() != null) {
                return ((DisplayArea) c).mType;
            } else if (c instanceof WindowToken && !(c instanceof ActivityRecord)) {
                return typeOf((WindowToken) c);
            } else if (c instanceof Task) {
                return ANY;
            } else {
                throw new IllegalArgumentException("Unknown container: " + c);
            }
        }

        private static Type typeOf(WindowToken c) {
            return c.getWindowLayerFromType() < APPLICATION_LAYER ? BELOW_TASKS : ABOVE_TASKS;
        }
    }
}
