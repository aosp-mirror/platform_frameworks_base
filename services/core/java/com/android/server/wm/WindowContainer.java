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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.ActivityInfo.isFixedOrientationLandscape;
import static android.content.pm.ActivityInfo.isFixedOrientationPortrait;
import static android.content.pm.ActivityInfo.reverseOrientation;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.ORIENTATION_UNDEFINED;
import static android.os.Trace.TRACE_TAG_WINDOW_MANAGER;
import static android.os.UserHandle.USER_NULL;
import static android.view.SurfaceControl.Transaction;
import static android.view.WindowInsets.Type.InsetsType;
import static android.view.WindowManager.LayoutParams.INVALID_WINDOW_TYPE;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.window.TaskFragmentAnimationParams.DEFAULT_ANIMATION_BACKGROUND_COLOR;
import static android.window.DesktopModeFlags.ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION;

import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_ANIM;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_APP_TRANSITIONS;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_APP_TRANSITIONS_ANIM;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_ORIENTATION;
import static com.android.internal.protolog.WmProtoLogGroups.WM_DEBUG_SYNC_ENGINE;
import static com.android.server.policy.WindowManagerPolicy.FINISH_LAYOUT_REDO_WALLPAPER;
import static com.android.server.wm.AppTransition.MAX_APP_TRANSITION_DURATION;
import static com.android.server.wm.AppTransition.isActivityTransitOld;
import static com.android.server.wm.AppTransition.isTaskFragmentTransitOld;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.IdentifierProto.HASH_CODE;
import static com.android.server.wm.IdentifierProto.TITLE;
import static com.android.server.wm.IdentifierProto.USER_ID;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_ALL;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainerChildProto.WINDOW_CONTAINER;
import static com.android.server.wm.WindowContainerProto.CONFIGURATION_CONTAINER;
import static com.android.server.wm.WindowContainerProto.IDENTIFIER;
import static com.android.server.wm.WindowContainerProto.ORIENTATION;
import static com.android.server.wm.WindowContainerProto.SURFACE_ANIMATOR;
import static com.android.server.wm.WindowContainerProto.SURFACE_CONTROL;
import static com.android.server.wm.WindowContainerProto.VISIBLE;
import static com.android.server.wm.WindowManagerDebugConfig.DEBUG;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowStateAnimator.ROOT_TASK_CLIP_AFTER_ANIM;

import android.annotation.CallSuper;
import android.annotation.ColorInt;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo;
import android.content.pm.ActivityInfo.ScreenOrientation;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Debug;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Pair;
import android.util.Pools;
import android.util.RotationUtils;
import android.util.Slog;
import android.util.SparseArray;
import android.util.proto.ProtoOutputStream;
import android.view.DisplayInfo;
import android.view.InsetsFrameProvider;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.MagnificationSpec;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationTarget;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Builder;
import android.view.SurfaceControlViewHost;
import android.view.WindowManager;
import android.view.WindowManager.TransitionOldType;
import android.view.animation.Animation;
import android.window.IWindowContainerToken;
import android.window.WindowContainerToken;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.ColorUtils;
import com.android.internal.protolog.ProtoLog;
import com.android.internal.protolog.common.LogLevel;
import com.android.internal.util.ToBooleanFunction;
import com.android.server.wm.SurfaceAnimator.Animatable;
import com.android.server.wm.SurfaceAnimator.AnimationType;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;
import com.android.server.wm.utils.AlwaysTruePredicate;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Defines common functionality for classes that can hold windows directly or through their
 * children in a hierarchy form.
 * The test class is {@link WindowContainerTests} which must be kept up-to-date and ran anytime
 * changes are made to this class.
 */
class WindowContainer<E extends WindowContainer> extends ConfigurationContainer<E>
        implements Comparable<WindowContainer>, Animatable, SurfaceFreezer.Freezable,
        InsetsControlTarget {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "WindowContainer" : TAG_WM;

    static final int POSITION_TOP = Integer.MAX_VALUE;
    static final int POSITION_BOTTOM = Integer.MIN_VALUE;

    /**
     * The parent of this window container.
     * For removing or setting new parent {@link #setParent} should be used, because it also
     * performs configuration updates based on new parent's settings.
     */
    private WindowContainer<WindowContainer> mParent = null;

    // Set to true when we are performing a reparenting operation so we only send one
    // onParentChanged() notification.
    boolean mReparenting;

    /**
     * Map of the source ID to the {@link InsetsSource} for all children of the current
     * {@link WindowContainer}.
     *
     * Note that these sources are not part of the {@link InsetsStateController} and live here.
     * These are supposed to provide insets only to the subtree of this {@link WindowContainer}.
     */
    @Nullable
    SparseArray<InsetsSource> mLocalInsetsSources = null;

    @Nullable
    protected InsetsSourceProvider mControllableInsetProvider;

    /**
     * The {@link InsetsSourceProvider}s provided by this window container.
     */
    protected SparseArray<InsetsSourceProvider> mInsetsSourceProviders = null;

    /**
     * The combined excluded insets types (combined mExcludeInsetsTypes and the
     * mMergedExcludeInsetsTypes from its parent)
     */
    protected @InsetsType int mMergedExcludeInsetsTypes = 0;
    private @InsetsType int mExcludeInsetsTypes = 0;

    @Nullable
    private ArrayMap<IBinder, DeathRecipient> mInsetsOwnerDeathRecipientMap;

    // List of children for this window container. List is in z-order as the children appear on
    // screen with the top-most window container at the tail of the list.
    protected final ArrayList<E> mChildren = new ArrayList<E>();

    // The specified orientation for this window container.
    // Shouldn't be accessed directly since subclasses can override getOverrideOrientation.
    @ScreenOrientation
    private int mOverrideOrientation = SCREEN_ORIENTATION_UNSET;

    /**
     * The window container which decides its orientation since the last time
     * {@link #getOrientation(int) was called.
     */
    protected WindowContainer mLastOrientationSource;

    private final Pools.SynchronizedPool<ForAllWindowsConsumerWrapper> mConsumerWrapperPool =
            new Pools.SynchronizedPool<>(3);

    // The display this window container is on.
    protected DisplayContent mDisplayContent;

    protected SurfaceControl mSurfaceControl;
    private int mLastLayer = 0;
    private SurfaceControl mLastRelativeToLayer = null;

    private Transaction mPendingTransaction;

    /**
     * Windows that clients are waiting to have drawn.
     */
    final ArrayList<WindowState> mWaitingForDrawn = new ArrayList<>();

    /**
     * Applied as part of the animation pass in "prepareSurfaces".
     */
    protected final SurfaceAnimator mSurfaceAnimator;

    /** The parent leash added for animation. */
    @Nullable
    private SurfaceControl mAnimationLeash;

    final SurfaceFreezer mSurfaceFreezer;
    protected final WindowManagerService mWmService;
    final TransitionController mTransitionController;

    /**
     * Sources which triggered a surface animation on this container. An animation target can be
     * promoted to higher level, for example, from a set of {@link ActivityRecord}s to
     * {@link Task}. In this case, {@link ActivityRecord}s are set on this variable while
     * the animation is running, and reset after finishing it.
     */
    private final ArraySet<WindowContainer> mSurfaceAnimationSources = new ArraySet<>();

    private final Point mTmpPos = new Point();
    protected final Point mLastSurfacePosition = new Point();
    protected @Surface.Rotation int mLastDeltaRotation = Surface.ROTATION_0;

    /** Total number of elements in this subtree, including our own hierarchy element. */
    private int mTreeWeight = 1;

    private int mSyncTransactionCommitCallbackDepth = 0;

    /** Interface for {@link #isAnimating} to check which cases for the container is animating. */
    public interface AnimationFlags {
        /**
         * A bit flag indicates that {@link #isAnimating} should also return {@code true}
         * even though the container is not yet animating, but the window container or its
         * relatives as specified by PARENTS or CHILDREN are part of an {@link AppTransition}
         * that is pending so an animation starts soon.
         */
        int TRANSITION = 1;

        /**
         * A bit flag indicates that {@link #isAnimating} should also check if one of the
         * ancestors of the container are animating in addition to the container itself.
         */
        int PARENTS = 2;

        /**
         * A bit flag indicates that {@link #isAnimating} should also check if one of the
         * descendants of the container are animating in addition to the container itself.
         */
        int CHILDREN = 4;
    }

    /**
     * True if this an AppWindowToken and the activity which created this was launched with
     * ActivityOptions.setLaunchTaskBehind.
     *
     * TODO(b/142617871): We run a special animation when the activity was launched with that
     * flag, but it's not necessary anymore. Keep the window invisible until the task is explicitly
     * selected to suppress an animation, and remove this flag.
     */
    boolean mLaunchTaskBehind;

    /**
     * If we are running an animation, this determines the transition type.
     */
    @TransitionOldType int mTransit;

    /**
     * If we are running an animation, this determines the flags during this animation. Must be a
     * bitwise combination of AppTransition.TRANSIT_FLAG_* constants.
     */
    int mTransitFlags;

    /** Whether this container should be boosted at the top of all its siblings. */
    @VisibleForTesting boolean mNeedsZBoost;

    /** Layer used to constrain the animation to a container's stack bounds. */
    SurfaceControl mAnimationBoundsLayer;

    /** Whether this container needs to create mAnimationBoundsLayer for cropping animations. */
    boolean mNeedsAnimationBoundsLayer;

    /**
     * This gets used during some open/close transitions as well as during a change transition
     * where it represents the starting-state snapshot.
     */
    WindowContainerThumbnail mThumbnail;
    final Point mTmpPoint = new Point();
    protected final Rect mTmpRect = new Rect();
    final Rect mTmpPrevBounds = new Rect();

    private MagnificationSpec mLastMagnificationSpec;

    private boolean mIsFocusable = true;

    /**
     * This indicates whether this window is visible by policy. This can precede physical
     * visibility ({@link #isVisible} - whether it has a surface showing on the screen) in
     * cases where an animation is on-going.
     */
    protected boolean mVisibleRequested;

    /**
     * Used as a unique, cross-process identifier for this Container. It also serves a minimal
     * interface to other processes.
     */
    RemoteToken mRemoteToken = null;

    /** This isn't participating in a sync. */
    public static final int SYNC_STATE_NONE = 0;

    /** This is currently waiting for itself to finish drawing. */
    public static final int SYNC_STATE_WAITING_FOR_DRAW = 1;

    /** This container is ready, but it might still have unfinished children. */
    public static final int SYNC_STATE_READY = 2;

    @IntDef(prefix = { "SYNC_STATE_" }, value = {
            SYNC_STATE_NONE,
            SYNC_STATE_WAITING_FOR_DRAW,
            SYNC_STATE_READY,
    })
    @interface SyncState {}

    /**
     * If non-null, references the sync-group directly waiting on this container. Otherwise, this
     * container is only being waited-on by its parents (if in a sync-group). This has implications
     * on how this container is handled during parent changes.
     */
    BLASTSyncEngine.SyncGroup mSyncGroup = null;
    final SurfaceControl.Transaction mSyncTransaction;
    @SyncState int mSyncState = SYNC_STATE_NONE;
    int mSyncMethodOverride = BLASTSyncEngine.METHOD_UNDEFINED;

    private final List<WindowContainerListener> mListeners = new ArrayList<>();

    protected TrustedOverlayHost mOverlayHost;

    WindowContainer(WindowManagerService wms) {
        mWmService = wms;
        mTransitionController = mWmService.mAtmService.getTransitionController();
        mSyncTransaction = wms.mTransactionFactory.get();
        mSurfaceAnimator = new SurfaceAnimator(this, this::onAnimationFinished, wms);
        mSurfaceFreezer = new SurfaceFreezer(this, wms);
    }

    /**
     * Updates the {@link WindowState#mAboveInsetsState} and
     * {@link WindowState#mMergedLocalInsetsSources} by visiting the entire hierarchy.
     *
     * {@link WindowState#mAboveInsetsState} is updated by visiting all the windows in z-order
     * top-to-bottom manner and considering the {@link WindowContainer#mInsetsSourceProviders}
     * provided by the {@link WindowState}s at the top.
     * {@link WindowState#updateAboveInsetsState(InsetsState, SparseArray, ArraySet)} visits the
     * IME container in the correct order to make sure the IME insets are passed correctly to the
     * {@link WindowState}s below it.
     *
     * {@link WindowState#mMergedLocalInsetsSources} is updated by considering
     * {@link WindowContainer#mLocalInsetsSources} provided by all the parents of the window.
     *
     * Examples: Please take a look at
     * {@link WindowContainerTests#testAddLocalInsetsSourceProvider()}
     * {@link WindowContainerTests#testRemoveLocalInsetsSourceProvider()}.
     *
     * @param aboveInsetsState             The InsetsState of all the Windows above the current
     *                                     container.
     * @param localInsetsSourcesFromParent The local InsetsSourceProviders provided by all
     *                                     the parents in the hierarchy of the current
     *                                     container.
     * @param insetsChangedWindows         The windows which the insets changed have changed for.
     */
    void updateAboveInsetsState(InsetsState aboveInsetsState,
            SparseArray<InsetsSource> localInsetsSourcesFromParent,
            ArraySet<WindowState> insetsChangedWindows) {
        final SparseArray<InsetsSource> mergedLocalInsetsSources =
                createMergedSparseArray(localInsetsSourcesFromParent, mLocalInsetsSources);

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).updateAboveInsetsState(aboveInsetsState, mergedLocalInsetsSources,
                    insetsChangedWindows);
        }
    }

    static <T> SparseArray<T> createMergedSparseArray(SparseArray<T> sa1, SparseArray<T> sa2) {
        final int size1 = sa1 != null ? sa1.size() : 0;
        final int size2 = sa2 != null ? sa2.size() : 0;
        final SparseArray<T> mergedArray = new SparseArray<>(size1 + size2);
        if (size1 > 0) {
            for (int i = 0; i < size1; i++) {
                mergedArray.append(sa1.keyAt(i), sa1.valueAt(i));
            }
        }
        if (size2 > 0) {
            for (int i = 0; i < size2; i++) {
                mergedArray.put(sa2.keyAt(i), sa2.valueAt(i));
            }
        }
        return mergedArray;
    }

    /**
     * Adds an {@link InsetsFrameProvider} which describes what insets should be provided to
     * this {@link WindowContainer} and its children.
     *
     * @param provider describes the insets type and the frame.
     * @param owner owns the insets source which only exists when the owner is alive.
     */
    void addLocalInsetsFrameProvider(InsetsFrameProvider provider, IBinder owner) {
        if (provider == null || owner == null) {
            throw new IllegalArgumentException("Insets provider or owner not specified.");
        }
        if (mDisplayContent == null) {
            // This is possible this container is detached when WM shell is responding to a previous
            // request. WM shell will be updated when this container is attached again and the
            // insets need to be updated.
            Slog.w(TAG, "Can't add insets frame provider when detached. " + this);
            return;
        }

        if (mInsetsOwnerDeathRecipientMap == null) {
            mInsetsOwnerDeathRecipientMap = new ArrayMap<>();
        }
        DeathRecipient deathRecipient = mInsetsOwnerDeathRecipientMap.get(owner);
        if (deathRecipient == null) {
            deathRecipient = new DeathRecipient(owner);
            try {
                owner.linkToDeath(deathRecipient, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "Failed to add source for " + provider + " since the owner has died.");
                return;
            }
            mInsetsOwnerDeathRecipientMap.put(owner, deathRecipient);
        }
        final int id = provider.getId();
        deathRecipient.addSourceId(id);
        if (mLocalInsetsSources == null) {
            mLocalInsetsSources = new SparseArray<>();
        }
        if (mLocalInsetsSources.get(id) != null) {
            if (DEBUG) {
                Slog.d(TAG, "The local insets source for this " + provider
                        + " already exists. Overwriting.");
            }
        }
        final InsetsSource source = new InsetsSource(id, provider.getType());
        source.setFrame(provider.getArbitraryRectangle())
                .updateSideHint(getBounds())
                .setBoundingRects(provider.getBoundingRects());
        if (ENABLE_CAPTION_COMPAT_INSET_FORCE_CONSUMPTION.isTrue()) {
            source.setFlags(provider.getFlags());
        }
        mLocalInsetsSources.put(id, source);
        mDisplayContent.getInsetsStateController().updateAboveInsetsState(true);
    }

    private class DeathRecipient implements IBinder.DeathRecipient {

        private final IBinder mOwner;
        private final ArraySet<Integer> mSourceIds = new ArraySet<>();

        DeathRecipient(IBinder owner) {
            mOwner = owner;
        }

        void addSourceId(int id) {
            mSourceIds.add(id);
        }

        void removeSourceId(int id) {
            mSourceIds.remove(id);
        }

        boolean hasSource() {
            return !mSourceIds.isEmpty();
        }

        @Override
        public void binderDied() {
            synchronized (mWmService.mGlobalLock) {
                boolean changed = false;
                for (int i = mSourceIds.size() - 1; i >= 0; i--) {
                    changed |= removeLocalInsetsSource(mSourceIds.valueAt(i));
                }
                mSourceIds.clear();
                mOwner.unlinkToDeath(this, 0);
                mInsetsOwnerDeathRecipientMap.remove(mOwner);
                if (changed && mDisplayContent != null) {
                    mDisplayContent.getInsetsStateController().updateAboveInsetsState(true);
                }
            }
        }
    }

    void removeLocalInsetsFrameProvider(InsetsFrameProvider provider, IBinder owner) {
        if (provider == null || owner == null) {
            throw new IllegalArgumentException("Insets provider or owner not specified.");
        }
        final int id = provider.getId();
        if (removeLocalInsetsSource(id) && mDisplayContent != null) {
            mDisplayContent.getInsetsStateController().updateAboveInsetsState(true);
        }
        if (mInsetsOwnerDeathRecipientMap == null) {
            return;
        }
        final DeathRecipient deathRecipient = mInsetsOwnerDeathRecipientMap.get(owner);
        if (deathRecipient == null) {
            return;
        }
        deathRecipient.removeSourceId(id);
        if (!deathRecipient.hasSource()) {
            owner.unlinkToDeath(deathRecipient, 0);
            mInsetsOwnerDeathRecipientMap.remove(owner);
        }
    }

    private boolean removeLocalInsetsSource(int id) {
        if (mLocalInsetsSources == null) {
            return false;
        }
        if (mLocalInsetsSources.removeReturnOld(id) == null) {
            if (DEBUG) {
                Slog.d(TAG, "Given id " + Integer.toHexString(id) + " doesn't exist.");
            }
            return false;
        }
        return true;
    }

    /**
     * Sets an {@link InsetsSourceProvider} to be associated with this {@code WindowContainer},
     * but only if the provider itself is controllable, as one window can be the provider of more
     * than one inset type (i.e. gesture insets). If this {code WindowContainer} is controllable,
     * all its animations must be controlled by its control target, and the visibility of this
     * {code WindowContainer} should be taken account into the state of the control target.
     *
     * @param insetProvider the provider which should not be visible to the client.
     * @see WindowState#getInsetsState()
     */
    void setControllableInsetProvider(InsetsSourceProvider insetProvider) {
        mControllableInsetProvider = insetProvider;
    }

    InsetsSourceProvider getControllableInsetProvider() {
        return mControllableInsetProvider;
    }

    /**
     * Sets the excludeInsetsTypes of this window and updates the mMergedExcludeInsetsTypes of
     * all child nodes in the hierarchy.
     *
     * @param excludeInsetsTypes the excluded {@link InsetsType} that should be set on this
     *                           WindowContainer
     */
    void setExcludeInsetsTypes(@InsetsType int excludeInsetsTypes) {
        if (excludeInsetsTypes == mExcludeInsetsTypes) {
            return;
        }
        mExcludeInsetsTypes = excludeInsetsTypes;
        mergeExcludeInsetsTypesAndNotifyInsetsChanged(
                mParent != null ? mParent.mMergedExcludeInsetsTypes : 0);
    }

    private void mergeExcludeInsetsTypesAndNotifyInsetsChanged(
            @InsetsType int excludeInsetsTypesFromParent) {
        final ArraySet<WindowState> changedWindows = new ArraySet<>();
        updateMergedExcludeInsetsTypes(excludeInsetsTypesFromParent, changedWindows);
        if (getDisplayContent() != null) {
            getDisplayContent().getInsetsStateController().notifyInsetsChanged(changedWindows);
        }
    }

    private void updateMergedExcludeInsetsTypes(
            @InsetsType int excludeInsetsTypesFromParent, ArraySet<WindowState> changedWindows) {
        final int newMergedExcludeInsetsTypes = mExcludeInsetsTypes | excludeInsetsTypesFromParent;
        if (newMergedExcludeInsetsTypes == mMergedExcludeInsetsTypes) {
            return;
        }
        mMergedExcludeInsetsTypes = newMergedExcludeInsetsTypes;

        final WindowState win = asWindowState();
        if (win != null) {
            changedWindows.add(win);
        }
        // Apply to all children
        for (int i = mChildren.size() - 1; i >= 0; i--) {
            final WindowContainer<?> child = mChildren.get(i);
            child.updateMergedExcludeInsetsTypes(mMergedExcludeInsetsTypes, changedWindows);
        }
    }

    @Override
    final protected WindowContainer getParent() {
        return mParent;
    }

    @Override
    protected int getChildCount() {
        return mChildren.size();
    }

    @Override
    protected E getChildAt(int index) {
        return mChildren.get(index);
    }

    @Override
    public void onConfigurationChanged(Configuration newParentConfig) {
        super.onConfigurationChanged(newParentConfig);
        if (mParent == null) {
            // Avoid unnecessary surface operation before attaching to a parent.
            return;
        }
        updateSurfacePositionNonOrganized();
        scheduleAnimation();
        if (mOverlayHost != null) {
            mOverlayHost.dispatchConfigurationChanged(getConfiguration());
        }
    }

    void reparent(WindowContainer newParent, int position) {
        if (newParent == null) {
            throw new IllegalArgumentException("reparent: can't reparent to null " + this);
        }

        if (newParent == this) {
            throw new IllegalArgumentException("Can not reparent to itself " + this);
        }

        final WindowContainer oldParent = mParent;
        if (mParent == newParent) {
            throw new IllegalArgumentException("WC=" + this + " already child of " + mParent);
        }

        // Collect before removing child from old parent, because the old parent may be removed if
        // this is the last child in it.
        mTransitionController.collectReparentChange(this, newParent);

        // The display object before reparenting as that might lead to old parent getting removed
        // from the display if it no longer has any child.
        final DisplayContent prevDc = oldParent.getDisplayContent();
        final DisplayContent dc = newParent.getDisplayContent();

        mReparenting = true;
        oldParent.removeChild(this);
        newParent.addChild(this, position);
        mReparenting = false;

        // Relayout display(s)
        dc.setLayoutNeeded();
        if (prevDc != dc) {
            onDisplayChanged(dc);
            prevDc.setLayoutNeeded();
        }

        // Send onParentChanged notification here is we disabled sending it in setParent for
        // reparenting case.
        onParentChanged(newParent, oldParent);
        onSyncReparent(oldParent, newParent);
    }

    final protected void setParent(WindowContainer<WindowContainer> parent) {
        final WindowContainer oldParent = mParent;
        mParent = parent;

        if (mParent != null) {
            mParent.onChildAdded(this);
        } else if (mSurfaceAnimator.hasLeash()) {
            mSurfaceAnimator.cancelAnimation();
        }
        if (!mReparenting) {
            onSyncReparent(oldParent, mParent);
            if (mParent != null && mParent.mDisplayContent != null
                    && mDisplayContent != mParent.mDisplayContent) {
                onDisplayChanged(mParent.mDisplayContent);
            }
            onParentChanged(mParent, oldParent);
        }
    }

    /**
     * Callback that is triggered when @link WindowContainer#setParent(WindowContainer)} was called.
     * Supposed to be overridden and contain actions that should be executed after parent was set.
     */
    @Override
    void onParentChanged(ConfigurationContainer newParent, ConfigurationContainer oldParent) {
        super.onParentChanged(newParent, oldParent);
        if (mParent == null) {
            mergeExcludeInsetsTypesAndNotifyInsetsChanged(0);
            return;
        }

        if (mSurfaceControl == null) {
            // If we don't yet have a surface, but we now have a parent, we should
            // build a surface.
            createSurfaceControl(false /*force*/);
        } else {
            // If we have a surface but a new parent, we just need to perform a reparent. Go through
            // surface animator such that hierarchy is preserved when animating, i.e.
            // mSurfaceControl stays attached to the leash and we just reparent the leash to the
            // new parent.
            reparentSurfaceControl(getSyncTransaction(), mParent.mSurfaceControl);
        }
        mergeExcludeInsetsTypesAndNotifyInsetsChanged(mParent.mMergedExcludeInsetsTypes);

        // Either way we need to ask the parent to assign us a Z-order.
        mParent.assignChildLayers();
    }

    void createSurfaceControl(boolean force) {
        setInitialSurfaceControlProperties(makeSurface());
    }

    void setInitialSurfaceControlProperties(Builder b) {
        setSurfaceControl(b.setCallsite("WindowContainer.setInitialSurfaceControlProperties").build());
        if (showSurfaceOnCreation()) {
            getSyncTransaction().show(mSurfaceControl);
        }
        updateSurfacePositionNonOrganized();
        if (mLastMagnificationSpec != null) {
            applyMagnificationSpec(getSyncTransaction(), mLastMagnificationSpec);
        }
    }

    /**
     * Create a new SurfaceControl for this WindowContainer and migrate all properties to the new
     * SurfaceControl. Properties include:
     * 1. Children
     * 2. Position
     * 3. Z order
     *
     * Remove the old SurfaceControl since it's no longer needed.
     *
     * This is used to revoke control of the SurfaceControl from a client process that was
     * previously organizing this WindowContainer.
     */
    void migrateToNewSurfaceControl(SurfaceControl.Transaction t) {
        t.remove(mSurfaceControl);
        // Clear the last position so the new SurfaceControl will get correct position
        mLastSurfacePosition.set(0, 0);
        mLastDeltaRotation = Surface.ROTATION_0;

        final Builder b = mWmService.makeSurfaceBuilder()
                .setContainerLayer()
                .setName(getName());

        setInitialSurfaceControlProperties(b);

        // If parent is null, the layer should be placed offscreen so reparent to null. Otherwise,
        // set to the available parent.
        t.reparent(mSurfaceControl, mParent == null ? null : mParent.mSurfaceControl);

        if (mLastRelativeToLayer != null) {
            t.setRelativeLayer(mSurfaceControl, mLastRelativeToLayer, mLastLayer);
        } else {
            t.setLayer(mSurfaceControl, mLastLayer);
        }

        for (int i = 0; i < mChildren.size(); i++)  {
            SurfaceControl sc = mChildren.get(i).getSurfaceControl();
            if (sc != null) {
                t.reparent(sc, mSurfaceControl);
            }
        }

        if (mOverlayHost != null) {
            mOverlayHost.setParent(t, mSurfaceControl);
        }

        scheduleAnimation();
    }

    // Temp. holders for a chain of containers we are currently processing.
    private final LinkedList<WindowContainer> mTmpChain1 = new LinkedList<>();
    private final LinkedList<WindowContainer> mTmpChain2 = new LinkedList<>();

    /**
     * Adds the input window container has a child of this container in order based on the input
     * comparator.
     * @param child The window container to add as a child of this window container.
     * @param comparator Comparator to use in determining the position the child should be added to.
     *                   If null, the child will be added to the top.
     */
    @CallSuper
    protected void addChild(E child, Comparator<E> comparator) {
        if (!child.mReparenting && child.getParent() != null) {
            throw new IllegalArgumentException("addChild: container=" + child.getName()
                    + " is already a child of container=" + child.getParent().getName()
                    + " can't add to container=" + getName());
        }

        int positionToAdd = -1;
        if (comparator != null) {
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                if (comparator.compare(child, mChildren.get(i)) < 0) {
                    positionToAdd = i;
                    break;
                }
            }
        }

        if (positionToAdd == -1) {
            mChildren.add(child);
        } else {
            mChildren.add(positionToAdd, child);
        }

        // Set the parent after we've actually added a child in case a subclass depends on this.
        child.setParent(this);
    }

    /** Adds the input window container has a child of this container at the input index. */
    @CallSuper
    void addChild(E child, int index) {
        if (!child.mReparenting && child.getParent() != null) {
            throw new IllegalArgumentException("addChild: container=" + child.getName()
                    + " is already a child of container=" + child.getParent().getName()
                    + " can't add to container=" + getName()
                    + "\n callers=" + Debug.getCallers(15, "\n"));
        }

        if ((index < 0 && index != POSITION_BOTTOM)
                || (index > mChildren.size() && index != POSITION_TOP)) {
            throw new IllegalArgumentException("addChild: invalid position=" + index
                    + ", children number=" + mChildren.size());
        }

        if (index == POSITION_TOP) {
            index = mChildren.size();
        } else if (index == POSITION_BOTTOM) {
            index = 0;
        }

        mChildren.add(index, child);

        // Set the parent after we've actually added a child in case a subclass depends on this.
        child.setParent(this);
    }

    private void onChildAdded(WindowContainer child) {
        mTreeWeight += child.mTreeWeight;
        WindowContainer parent = getParent();
        while (parent != null) {
            parent.mTreeWeight += child.mTreeWeight;
            parent = parent.getParent();
        }
        onChildVisibleRequestedChanged(child);
        onChildPositionChanged(child);
    }

    /**
     * Removes the input child container from this container which is its parent.
     *
     * @return True if the container did contain the input child and it was detached.
     */
    @CallSuper
    void removeChild(E child) {
        if (mChildren.remove(child)) {
            onChildRemoved(child);
            if (!child.mReparenting) {
                child.setParent(null);
            }
        } else {
            throw new IllegalArgumentException("removeChild: container=" + child.getName()
                    + " is not a child of container=" + getName());
        }
    }

    private void onChildRemoved(WindowContainer child) {
        mTreeWeight -= child.mTreeWeight;
        WindowContainer parent = getParent();
        while (parent != null) {
            parent.mTreeWeight -= child.mTreeWeight;
            parent = parent.getParent();
        }
        onChildVisibleRequestedChanged(null);
        onChildPositionChanged(child);
    }

    /**
     * Removes this window container and its children with no regard for what else might be going on
     * in the system. For example, the container will be removed during animation if this method is
     * called which isn't desirable. For most cases you want to call {@link #removeIfPossible()}
     * which allows the system to defer removal until a suitable time.
     */
    @CallSuper
    void removeImmediately() {
        final DisplayContent dc = getDisplayContent();
        if (dc != null) {
            dc.mClosingChangingContainers.remove(this);
            mSurfaceFreezer.unfreeze(getSyncTransaction());
        }
        while (!mChildren.isEmpty()) {
            final E child = mChildren.getLast();
            child.removeImmediately();
            // Need to do this after calling remove on the child because the child might try to
            // remove/detach itself from its parent which will cause an exception if we remove
            // it before calling remove on the child.
            if (mChildren.remove(child)) {
                onChildRemoved(child);
            }
        }

        if (mSurfaceControl != null) {
            getSyncTransaction().remove(mSurfaceControl);
            setSurfaceControl(null);
            mLastSurfacePosition.set(0, 0);
            mLastDeltaRotation = Surface.ROTATION_0;
            scheduleAnimation();
        }
        if (mOverlayHost != null) {
            mOverlayHost.release();
            mOverlayHost = null;
        }

        // This must happen after updating the surface so that sync transactions can be handled
        // properly.
        if (mParent != null) {
            mParent.removeChild(this);
        }

        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onRemoved();
        }
    }

    /** Returns the total number of descendants, including self. */
    int getTreeWeight() {
        return mTreeWeight;
    }

    /**
     * @return The index of this element in the hierarchy tree in prefix order.
     */
    int getPrefixOrderIndex() {
        if (mParent == null) {
            return 0;
        }
        return mParent.getPrefixOrderIndex(this);
    }

    private int getPrefixOrderIndex(WindowContainer child) {
        int order = 0;
        for (int i = 0; i < mChildren.size(); i++) {
            final WindowContainer childI = mChildren.get(i);
            if (child == childI) {
                break;
            }
            order += childI.mTreeWeight;
        }
        if (mParent != null) {
            order += mParent.getPrefixOrderIndex(this);
        }

        // We also need to count ourselves.
        order++;
        return order;
    }

    /**
     * Removes this window container and its children taking care not to remove them during a
     * critical stage in the system. For example, some containers will not be removed during
     * animation if this method is called.
     */
    // TODO: figure-out implementation that works best for this.
    // E.g. when do we remove from parent list? maybe not...
    void removeIfPossible() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.removeIfPossible();
        }
    }

    /** Returns true if this window container has the input child. */
    boolean hasChild(E child) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final E current = mChildren.get(i);
            if (current == child || current.hasChild(child)) {
                return true;
            }
        }
        return false;
    }

    /** @return true if this window container is a descendant of the input container. */
    boolean isDescendantOf(WindowContainer ancestor) {
        final WindowContainer parent = getParent();
        if (parent == ancestor) return true;
        return (parent != null) && parent.isDescendantOf(ancestor);
    }

    /**
     * Move a child from it's current place in siblings list to the specified position,
     * with an option to move all its parents to top.
     * @param position Target position to move the child to.
     * @param child Child to move to selected position.
     * @param includingParents Flag indicating whether we need to move the entire branch of the
     *                         hierarchy when we're moving a child to {@link #POSITION_TOP} or
     *                         {@link #POSITION_BOTTOM}. When moving to other intermediate positions
     *                         this flag will do nothing.
     */
    @CallSuper
    void positionChildAt(int position, E child, boolean includingParents) {
        if (child.getParent() != this) {
            throw new IllegalArgumentException("positionChildAt: container=" + child.getName()
                    + " is not a child of container=" + getName()
                    + " current parent=" + child.getParent());
        }

        if (position >= mChildren.size() - 1) {
            position = POSITION_TOP;
        } else if (position <= 0) {
            position = POSITION_BOTTOM;
        }

        switch (position) {
            case POSITION_TOP:
                if (getTopChild() != child) {
                    mChildren.remove(child);
                    mChildren.add(child);
                    onChildPositionChanged(child);
                }
                if (includingParents && getParent() != null) {
                    getParent().positionChildAt(POSITION_TOP, this /* child */,
                            true /* includingParents */);
                }
                break;
            case POSITION_BOTTOM:
                if (getBottomChild() != child) {
                    mChildren.remove(child);
                    mChildren.addFirst(child);
                    onChildPositionChanged(child);
                }
                if (includingParents && getParent() != null) {
                    getParent().positionChildAt(POSITION_BOTTOM, this /* child */,
                            true /* includingParents */);
                }
                break;
            default:
                // TODO: Removing the child before reinserting requires the caller to provide a
                //       position that takes into account the removed child (if the index of the
                //       child < position, then the position should be adjusted). We should consider
                //       doing this adjustment here and remove any adjustments in the callers.
                if (mChildren.indexOf(child) != position) {
                    mChildren.remove(child);
                    mChildren.add(position, child);
                    onChildPositionChanged(child);
                }
        }
    }

    /**
     * Notify that a child's position has changed. Possible changes are adding or removing a child.
     */
    void onChildPositionChanged(WindowContainer child) { }

    /**
     * Update override configuration and recalculate full config.
     * @see #mRequestedOverrideConfiguration
     * @see #mFullConfiguration
     */
    @Override
    public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
        // We must diff before the configuration is applied so that we can capture the change
        // against the existing bounds.
        final int diff = diffRequestedOverrideBounds(
                overrideConfiguration.windowConfiguration.getBounds());
        super.onRequestedOverrideConfigurationChanged(overrideConfiguration);
        if (mParent != null) {
            mParent.onDescendantOverrideConfigurationChanged();
        }

        if (diff == BOUNDS_CHANGE_NONE) {
            return;
        }

        if ((diff & BOUNDS_CHANGE_SIZE) == BOUNDS_CHANGE_SIZE) {
            onResize();
        } else {
            onMovedByResize();
        }
    }

    /**
     * Notify that a descendant's overrideConfiguration has changed.
     */
    void onDescendantOverrideConfigurationChanged() {
        if (mParent != null) {
            mParent.onDescendantOverrideConfigurationChanged();
        }
    }

    /**
     * Notify that the display this container is on has changed. This could be either this container
     * is moved to a new display, or some configurations on the display it is on changes.
     *
     * @param dc The display this container is on after changes.
     */
    void onDisplayChanged(DisplayContent dc) {
        if (mDisplayContent != null && mDisplayContent != dc) {
            // Cancel any change transition queued-up for this container on the old display when
            // this container is moved from the old display.
            mDisplayContent.mClosingChangingContainers.remove(this);
            if (mDisplayContent.mChangingContainers.remove(this)) {
                mSurfaceFreezer.unfreeze(getSyncTransaction());
            }
        }
        mDisplayContent = dc;
        if (dc != null && dc != this && mPendingTransaction != null) {
            dc.getPendingTransaction().merge(mPendingTransaction);
            mPendingTransaction = null;
        }
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            child.onDisplayChanged(dc);
        }
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onDisplayChanged(dc);
        }
    }

    /**
     * Returns {@code true} if this node provides insets.
     */
    public boolean hasInsetsSourceProvider() {
        return mInsetsSourceProviders != null;
    }

    /**
     * Returns {@link InsetsSourceProvider}s provided by this node.
     */
    public SparseArray<InsetsSourceProvider> getInsetsSourceProviders() {
        if (mInsetsSourceProviders == null) {
            mInsetsSourceProviders = new SparseArray<>();
        }
        return mInsetsSourceProviders;
    }

    public final DisplayContent getDisplayContent() {
        return mDisplayContent;
    }

    /** Returns the first node of type {@link DisplayArea} above or at this node. */
    @Nullable
    DisplayArea getDisplayArea() {
        WindowContainer parent = getParent();
        return parent != null ? parent.getDisplayArea() : null;
    }

    /** Returns the first node of type {@link RootDisplayArea} above or at this node. */
    @Nullable
    RootDisplayArea getRootDisplayArea() {
        WindowContainer parent = getParent();
        return parent != null ? parent.getRootDisplayArea() : null;
    }

    @Nullable
    TaskDisplayArea getTaskDisplayArea() {
        WindowContainer parent = getParent();
        return parent != null ? parent.getTaskDisplayArea() : null;
    }

    boolean isAttached() {
        WindowContainer parent = getParent();
        return parent != null && parent.isAttached();
    }

    void onResize() {
        if (mControllableInsetProvider != null) {
            mControllableInsetProvider.onWindowContainerBoundsChanged();
        }
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.onParentResize();
        }
    }

    void onParentResize() {
        // In the case this container has specified its own bounds, a parent resize will not
        // affect its bounds. Any relevant changes will be propagated through changes to the
        // Configuration override.
        if (hasOverrideBounds()) {
            return;
        }

        // Default implementation is to treat as resize on self.
        onResize();
    }

    void onMovedByResize() {
        if (mControllableInsetProvider != null) {
            mControllableInsetProvider.onWindowContainerBoundsChanged();
        }
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.onMovedByResize();
        }
    }

    void resetDragResizingChangeReported() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.resetDragResizingChangeReported();
        }
    }

    /**
     * @return {@code true} when an application can override an app transition animation on this
     * container.
     */
    boolean canCustomizeAppTransition() {
        return false;
    }

    /**
     * @return {@code true} when this container or its related containers are running an
     * animation, {@code false} otherwise.
     *
     * By default this predicate only checks if this container itself is actually running an
     * animation, but you can extend the check target over its relatives, or relax the condition
     * so that this can return {@code true} if an animation starts soon by giving a combination
     * of {@link AnimationFlags}.
     *
     * Note that you can give a combination of bitmask flags to specify targets and condition for
     * checking animating status.
     * e.g. {@code isAnimating(TRANSITION | PARENT)} returns {@code true} if either this
     * container itself or one of its parents is running an animation or waiting for an app
     * transition.
     *
     * Note that TRANSITION propagates to parents and children as well.
     *
     * @param flags The combination of bitmask flags to specify targets and condition for
     *              checking animating status.
     * @param typesToCheck The combination of bitmask {@link AnimationType} to compare when
     *                     determining if animating.
     *
     * @see AnimationFlags#TRANSITION
     * @see AnimationFlags#PARENTS
     * @see AnimationFlags#CHILDREN
     */
    final boolean isAnimating(int flags, int typesToCheck) {
        return getAnimatingContainer(flags, typesToCheck) != null;
    }

    /**
     * @deprecated Use {@link #isAnimating(int, int)}
     * TODO (b/152333373): Migrate calls to use isAnimating with specified animation type
     */
    @Deprecated
    final boolean isAnimating(int flags) {
        return isAnimating(flags, ANIMATION_TYPE_ALL);
    }

    /**
     * @return {@code true} when the container is waiting the app transition start, {@code false}
     *         otherwise.
     */
    boolean isWaitingForTransitionStart() {
        return false;
    }

    /**
     * @return {@code true} if in this subtree of the hierarchy we have an
     *         {@code ActivityRecord#isAnimating(TRANSITION)}, {@code false} otherwise.
     */
    boolean isAppTransitioning() {
        return getActivity(app -> app.isAnimating(PARENTS | TRANSITION)) != null;
    }

    /**
     * Returns {@code true} if self or the parent container of the window is in transition, e.g.
     * the app or recents transition. This method is only used when legacy and shell transition
     * have the same condition to check the animation state.
     */
    boolean inTransitionSelfOrParent() {
        if (!mTransitionController.isShellTransitionsEnabled()) {
            return isAnimating(PARENTS | TRANSITION, ANIMATION_TYPE_APP_TRANSITION);
        }
        return inTransition();
    }

    /**
     * @return Whether our own container running an animation at the moment.
     */
    final boolean isAnimating() {
        return isAnimating(0 /* self only */);
    }

    /**
     * @return {@code true} if the container is in changing app transition.
     */
    boolean isChangingAppTransition() {
        return mDisplayContent != null && mDisplayContent.mChangingContainers.contains(this);
    }

    boolean inTransition() {
        return mTransitionController.inTransition(this);
    }

    boolean isExitAnimationRunningSelfOrChild() {
        if (!mTransitionController.isShellTransitionsEnabled()) {
            return isAnimating(TRANSITION | CHILDREN, WindowState.EXIT_ANIMATING_TYPES);
        }
        // Only check leaf containers because inTransition() includes parent.
        if (mChildren.isEmpty() && inTransition()) {
            return true;
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            WindowContainer child = mChildren.get(i);
            if (child.isExitAnimationRunningSelfOrChild()) {
                return true;
            }
        }
        return false;
    }

    void sendAppVisibilityToClients() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.sendAppVisibilityToClients();
        }
    }

    /**
     * Returns true if the container or one of its children as some content it can display or wants
     * to display (e.g. app views or saved surface).
     *
     * NOTE: While this method will return true if the there is some content to display, it doesn't
     * mean the container is visible. Use {@link #isVisible()} to determine if the container is
     * visible.
     */
    boolean hasContentToDisplay() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            if (wc.hasContentToDisplay()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the container or one of its children is considered visible from the
     * WindowManager perspective which usually means valid surface and some other internal state
     * are true.
     *
     * NOTE: While this method will return true if the surface is visible, it doesn't mean the
     * client has actually displayed any content. Use {@link #hasContentToDisplay()} to determine if
     * the container has any content to display.
     */
    boolean isVisible() {
        // TODO: Will this be more correct if it checks the visibility of its parents?
        // It depends...For example, Tasks and Stacks are only visible if there children are visible
        // but, WindowState are not visible if there parent are not visible. Maybe have the
        // container specify which direction to traverse for visibility?
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            if (wc.isVisible()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Is this window's surface needed?  This is almost like isVisible, except when participating
     * in a transition, this will reflect the final visibility while isVisible won't change until
     * the transition is finished.
     */
    boolean isVisibleRequested() {
        return mVisibleRequested;
    }

    /** @return `true` if visibleRequested changed. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
    boolean setVisibleRequested(boolean visible) {
        if (mVisibleRequested == visible) return false;
        mVisibleRequested = visible;
        final WindowContainer parent = getParent();
        if (parent != null) {
            parent.onChildVisibleRequestedChanged(this);
        }

        // Notify listeners about visibility change.
        for (int i = mListeners.size() - 1; i >= 0; --i) {
            mListeners.get(i).onVisibleRequestedChanged(mVisibleRequested);
        }
        return true;
    }

    /**
     * @param child The changed or added child. `null` if a child was removed.
     * @return `true` if visibleRequested changed.
     */
    protected boolean onChildVisibleRequestedChanged(@Nullable WindowContainer child) {
        final boolean childVisReq = child != null && child.isVisibleRequested();
        boolean newVisReq = mVisibleRequested;
        if (childVisReq && !mVisibleRequested) {
            newVisReq = true;
        } else if (!childVisReq && mVisibleRequested) {
            newVisReq = false;
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final WindowContainer wc = mChildren.get(i);
                if (wc != child && wc.isVisibleRequested()) {
                    newVisReq = true;
                    break;
                }
            }
        }
        return setVisibleRequested(newVisReq);
    }

    /**
     * Called when the visibility of a child is asked to change. This is before visibility actually
     * changes (eg. a transition animation might play out first).
     */
    void onChildVisibilityRequested(boolean visible) {
        // If we are losing visibility, then a snapshot isn't necessary and we are no-longer
        // part of a change transition.
        if (!visible) {
            boolean skipUnfreeze = false;
            if (asTaskFragment() != null) {
                // If the organized TaskFragment is closing while resizing, we want to keep track of
                // its starting bounds to make sure the animation starts at the correct position.
                // This should be called before unfreeze() because we record the starting bounds
                // in SurfaceFreezer.
                skipUnfreeze = asTaskFragment().setClosingChangingStartBoundsIfNeeded();
            }

            if (!skipUnfreeze) {
                mSurfaceFreezer.unfreeze(getSyncTransaction());
            }
        }
        WindowContainer parent = getParent();
        if (parent != null) {
            parent.onChildVisibilityRequested(visible);
        }
    }

    /** Whether this window is closing while resizing. */
    boolean isClosingWhenResizing() {
        return mDisplayContent != null
                && mDisplayContent.mClosingChangingContainers.containsKey(this);
    }

    void writeIdentifierToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);
        proto.write(HASH_CODE, System.identityHashCode(this));
        proto.write(USER_ID, USER_NULL);
        proto.write(TITLE, "WindowContainer");
        proto.end(token);
    }

    /**
     * Returns {@code true} if this container is focusable. Generally, if a parent is not focusable,
     * this will not be focusable either.
     */
    boolean isFocusable() {
        final WindowContainer parent = getParent();
        return (parent == null || parent.isFocusable()) && mIsFocusable;
    }

    /** Set whether this container or its children can be focusable */
    boolean setFocusable(boolean focusable) {
        if (mIsFocusable == focusable) {
            return false;
        }
        mIsFocusable = focusable;
        return true;
    }

    /**
     * @return Whether this child is on top of the window hierarchy.
     */
    boolean isOnTop() {
        final WindowContainer parent = getParent();
        return parent != null && parent.getTopChild() == this && parent.isOnTop();
    }

    /** Returns the top child container. */
    E getTopChild() {
        final int n = mChildren.size();
        return n == 0 ? null : mChildren.get(n - 1);
    }

    E getBottomChild() {
        final int n = mChildren.size();
        return n == 0 ? null : mChildren.get(0);
    }

    /**
     * Removes the containers which were deferred.
     *
     * @return {@code true} if there is still a removal being deferred.
     */
    boolean handleCompleteDeferredRemoval() {
        boolean stillDeferringRemoval = false;

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            stillDeferringRemoval |= wc.handleCompleteDeferredRemoval();
            if (!hasChild()) {
                // All child containers of current level could be removed from a removal of
                // descendant. E.g. if a display is pending to be removed because it contains an
                // activity with {@link ActivityRecord#mIsExiting} is true, the display may be
                // removed when completing the removal of the last activity from
                // {@link ActivityRecord#handleCompleteDeferredRemoval}.
                return false;
            }
        }

        return stillDeferringRemoval;
    }

    /** Checks if all windows in an app are all drawn and shows them if needed. */
    void checkAppWindowsReadyToShow() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.checkAppWindowsReadyToShow();
        }
    }

    void onAppTransitionDone() {
        if (mSurfaceFreezer.hasLeash()) {
            mSurfaceFreezer.unfreeze(getSyncTransaction());
        }
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);
            wc.onAppTransitionDone();
        }
    }

    /**
     * Called when this container or one of its descendants changed its requested orientation, and
     * wants this container to handle it or pass it to its parent.
     *
     * @param requestingContainer the container which orientation request has changed
     * @return {@code true} if handled; {@code false} otherwise.
     */
    boolean onDescendantOrientationChanged(@Nullable WindowContainer requestingContainer) {
        final WindowContainer parent = getParent();
        if (parent == null) {
            return false;
        }
        return parent.onDescendantOrientationChanged(requestingContainer);
    }

    /**
     * Check if this container or its parent will handle orientation changes from descendants. It's
     * different from the return value of {@link #onDescendantOrientationChanged(WindowContainer)}
     * in the sense that the return value of this method tells if this container or its parent will
     * handle the request eventually, while the return value of the other method is if it handled
     * the request synchronously.
     *
     * @return {@code true} if it handles or will handle orientation change in the future; {@code
     *         false} if it won't handle the change at anytime.
     */
    boolean handlesOrientationChangeFromDescendant(int orientation) {
        final WindowContainer parent = getParent();
        return parent != null && parent.handlesOrientationChangeFromDescendant(orientation);
    }

    /**
     * Gets the configuration orientation by the requested screen orientation
     * ({@link ScreenOrientation}) of this activity.
     *
     * @return orientation in ({@link Configuration#ORIENTATION_LANDSCAPE},
     *         {@link Configuration#ORIENTATION_PORTRAIT},
     *         {@link Configuration#ORIENTATION_UNDEFINED}).
     */
    @Configuration.Orientation
    int getRequestedConfigurationOrientation() {
        return getRequestedConfigurationOrientation(false /* forDisplay */);
    }

    /**
     * Gets the configuration orientation by the requested screen orientation
     * ({@link ScreenOrientation}) of this activity.
     *
     * @param forDisplay whether it is the requested config orientation for display.
     *                   If {@code true}, we may reverse the requested orientation if the root is
     *                   different from the display, so that when the display rotates to the
     *                   reversed orientation, the requested app will be in the requested
     *                   orientation.
     * @return orientation in ({@link Configuration#ORIENTATION_LANDSCAPE},
     *         {@link Configuration#ORIENTATION_PORTRAIT},
     *         {@link Configuration#ORIENTATION_UNDEFINED}).
     */
    @Configuration.Orientation
    int getRequestedConfigurationOrientation(boolean forDisplay) {
        return getRequestedConfigurationOrientation(forDisplay, getOverrideOrientation());
    }

    /**
     * Gets the configuration orientation by the requested screen orientation
     *
     * @param forDisplay whether it is the requested config orientation for display.
     *                   If {@code true}, we may reverse the requested orientation if the root is
     *                   different from the display, so that when the display rotates to the
     *                   reversed orientation, the requested app will be in the requested
     *                   orientation.
     * @param requestedOrientation the screen orientation({@link ScreenOrientation}) that is
     *                   requested
     * @return orientation in ({@link Configuration#ORIENTATION_LANDSCAPE},
     *         {@link Configuration#ORIENTATION_PORTRAIT},
     *         {@link Configuration#ORIENTATION_UNDEFINED}).
     */
    @Configuration.Orientation
    int getRequestedConfigurationOrientation(boolean forDisplay,
            @ScreenOrientation int requestedOrientation) {
        final RootDisplayArea root = getRootDisplayArea();
        if (forDisplay && root != null && root.isOrientationDifferentFromDisplay()) {
            // Reverse the requested orientation if the orientation of its root is different from
            // the display, so that when the display rotates to the reversed orientation, the
            // requested app will be in the requested orientation.
            // For example, if the display is 1200x900 (landscape), and the DAG is 600x900
            // (portrait).
            // When an app below the DAG is requesting landscape, it should actually request the
            // display to be portrait, so that the DAG and the app will be in landscape.
            requestedOrientation = reverseOrientation(requestedOrientation);
        }

        if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_NOSENSOR) {
            // NOSENSOR means the display's "natural" orientation, so return that.
            if (mDisplayContent != null) {
                return mDisplayContent.getNaturalConfigurationOrientation();
            }
        } else if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LOCKED) {
            // LOCKED means the activity's orientation remains unchanged, so return existing value.
            return getConfiguration().orientation;
        } else if (isFixedOrientationLandscape(requestedOrientation)) {
            return ORIENTATION_LANDSCAPE;
        } else if (isFixedOrientationPortrait(requestedOrientation)) {
            return ORIENTATION_PORTRAIT;
        }
        return ORIENTATION_UNDEFINED;
    }

    /**
     * Calls {@link #setOrientation(int, WindowContainer)} with {@code null} to the last 2
     * parameters.
     *
     * @param orientation the specified orientation.
     */
    protected void setOrientation(@ScreenOrientation int orientation) {
        setOrientation(orientation, null /* requesting */);
    }

    /**
     * Sets the specified orientation of this container. It percolates this change upward along the
     * hierarchy to let each level of the hierarchy a chance to respond to it.
     *
     * @param orientation the specified orientation. Needs to be one of {@link ScreenOrientation}.
     * @param requestingContainer the container which orientation request has changed. Mostly used
     *                            to ensure it gets correct configuration.
     */
    void setOrientation(@ScreenOrientation int orientation,
            @Nullable WindowContainer requestingContainer) {
        if (getOverrideOrientation() == orientation) {
            return;
        }

        setOverrideOrientation(orientation);
        final WindowContainer parent = getParent();
        if (parent != null) {
            if (getConfiguration().orientation != getRequestedConfigurationOrientation()
                    // Update configuration directly only if the change won't be dispatched from
                    // ancestor. This prevents from computing intermediate configuration when the
                    // parent also needs to be updated from the ancestor. E.g. the app requests
                    // portrait but the task is still in landscape. While updating from display,
                    // the task can be updated to portrait first so the configuration can be
                    // computed in a consistent environment.
                    && (inMultiWindowMode()
                        || !handlesOrientationChangeFromDescendant(orientation))) {
                // Resolve the requested orientation.
                onConfigurationChanged(parent.getConfiguration());
            }
            onDescendantOrientationChanged(requestingContainer);
        }
    }

    @ScreenOrientation
    int getOrientation() {
        return getOrientation(getOverrideOrientation());
    }

    /**
     * Returns the specified orientation for this window container or one of its children is there
     * is one set, or {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_UNSET} if no
     * specification is set.
     * NOTE: {@link android.content.pm.ActivityInfo#SCREEN_ORIENTATION_UNSPECIFIED} is a
     * specification...
     *
     * @param candidate The current orientation candidate that will be returned if we don't find a
     *                  better match.
     * @return The orientation as specified by this branch or the window hierarchy.
     */
    @ScreenOrientation
    int getOrientation(@ScreenOrientation int candidate) {
        mLastOrientationSource = null;
        if (!providesOrientation()) {
            return SCREEN_ORIENTATION_UNSET;
        }

        // The container fills its parent so we can use it orientation if it has one
        // specified; otherwise we prefer to use the orientation of its topmost child that has one
        // specified and fall back on this container's unset or unspecified value as a candidate
        // if none of the children have a better candidate for the orientation.
        if (getOverrideOrientation() != SCREEN_ORIENTATION_UNSET
                && getOverrideOrientation() != SCREEN_ORIENTATION_UNSPECIFIED) {
            mLastOrientationSource = this;
            return getOverrideOrientation();
        }

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer wc = mChildren.get(i);

            final int orientation = wc.getOrientation(candidate == SCREEN_ORIENTATION_BEHIND
                    ? SCREEN_ORIENTATION_BEHIND : SCREEN_ORIENTATION_UNSET);
            if (orientation == SCREEN_ORIENTATION_BEHIND) {
                // container wants us to use the orientation of the container behind it. See if we
                // can find one. Else return SCREEN_ORIENTATION_BEHIND so the caller can choose to
                // look behind this container.
                candidate = orientation;
                mLastOrientationSource = wc;
                continue;
            }

            if (orientation == SCREEN_ORIENTATION_UNSET) {
                continue;
            }

            if (orientation != SCREEN_ORIENTATION_UNSPECIFIED || wc.providesOrientation()) {
                // Use the orientation if the container can provide or requested an explicit
                // orientation that isn't SCREEN_ORIENTATION_UNSPECIFIED.
                ProtoLog.v(WM_DEBUG_ORIENTATION, "%s is requesting orientation %d (%s)",
                        wc.toString(), orientation,
                        ActivityInfo.screenOrientationToString(orientation));
                mLastOrientationSource = wc;
                return orientation;
            }
        }

        return candidate;
    }

    /**
     * Returns orientation specified on this level of hierarchy without taking children into
     * account, like {@link #getOrientation} does, allowing subclasses to override. See {@link
     * ActivityRecord#getOverrideOrientation} for an example.
     */
    @ScreenOrientation
    protected int getOverrideOrientation() {
        return mOverrideOrientation;
    }

    protected void setOverrideOrientation(@ScreenOrientation int orientation) {
        mOverrideOrientation = orientation;
    }

    /**
     * @return The deepest source which decides the orientation of this window container since the
     *         last time {@link #getOrientation(int) was called.
     */
    @Nullable
    final WindowContainer<?> getLastOrientationSource() {
        if (mLastOrientationSource == null) {
            return null;
        }
        WindowContainer<?> source = this;
        while (source != source.mLastOrientationSource && source.mLastOrientationSource != null) {
            source = source.mLastOrientationSource;
        }
        return source;
    }

    boolean providesOrientation() {
        return fillsParent();
    }

    /**
     * Returns true if this container is opaque and fills all the space made available by its parent
     * container.
     *
     * NOTE: It is possible for this container to occupy more space than the parent has (or less),
     * this is just a signal from the client to window manager stating its intent, but not what it
     * actually does.
     */
    boolean fillsParent() {
        return false;
    }

    /** Computes LONG, SIZE and COMPAT parts of {@link Configuration#screenLayout}. */
    static int computeScreenLayout(int sourceScreenLayout, int screenWidthDp,
            int screenHeightDp) {
        sourceScreenLayout = sourceScreenLayout
                & (Configuration.SCREENLAYOUT_LONG_MASK | Configuration.SCREENLAYOUT_SIZE_MASK);
        final int longSize = Math.max(screenWidthDp, screenHeightDp);
        final int shortSize = Math.min(screenWidthDp, screenHeightDp);
        return Configuration.reduceScreenLayout(sourceScreenLayout, longSize, shortSize);
    }

    // TODO: Users would have their own window containers under the display container?
    void switchUser(int userId) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).switchUser(userId);
        }
    }

    /** Returns whether the window should be shown for current user. */
    boolean showToCurrentUser() {
        return true;
    }

    void forAllWindowContainers(Consumer<WindowContainer> callback) {
        callback.accept(this);
        final int count = mChildren.size();
        for (int i = 0; i < count; i++) {
            mChildren.get(i).forAllWindowContainers(callback);
        }
    }

    /**
     * For all windows at or below this container call the callback.
     * @param   callback Calls the {@link ToBooleanFunction#apply} method for each window found and
     *                   stops the search if {@link ToBooleanFunction#apply} returns true.
     * @param   traverseTopToBottom If true traverses the hierarchy from top-to-bottom in terms of
     *                              z-order, else from bottom-to-top.
     * @return  True if the search ended before we reached the end of the hierarchy due to
     *          {@link ToBooleanFunction#apply} returning true.
     */
    boolean forAllWindows(ToBooleanFunction<WindowState> callback, boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                if (mChildren.get(i).forAllWindows(callback, traverseTopToBottom)) {
                    return true;
                }
            }
        } else {
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                if (mChildren.get(i).forAllWindows(callback, traverseTopToBottom)) {
                    return true;
                }
            }
        }
        return false;
    }

    void forAllWindows(Consumer<WindowState> callback, boolean traverseTopToBottom) {
        ForAllWindowsConsumerWrapper wrapper = obtainConsumerWrapper(callback);
        forAllWindows(wrapper, traverseTopToBottom);
        wrapper.release();
    }

    boolean forAllActivities(Predicate<ActivityRecord> callback) {
        return forAllActivities(callback, true /*traverseTopToBottom*/);
    }

    boolean forAllActivities(Predicate<ActivityRecord> callback, boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                if (mChildren.get(i).forAllActivities(callback, traverseTopToBottom)) return true;
            }
        } else {
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                if (mChildren.get(i).forAllActivities(callback, traverseTopToBottom)) return true;
            }
        }

        return false;
    }

    void forAllActivities(Consumer<ActivityRecord> callback) {
        forAllActivities(callback, true /*traverseTopToBottom*/);
    }

    void forAllActivities(Consumer<ActivityRecord> callback, boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                mChildren.get(i).forAllActivities(callback, traverseTopToBottom);
            }
        } else {
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                mChildren.get(i).forAllActivities(callback, traverseTopToBottom);
            }
        }
    }

    /**
     * Process all activities in this branch of the tree.
     *
     * @param callback Called for each activity found.
     * @param boundary We don't return activities via {@param callback} until we get to this node in
     *                 the tree.
     * @param includeBoundary If the boundary from be processed to return activities.
     * @param traverseTopToBottom direction to traverse the tree.
     * @return {@code true} if we ended the search before reaching the end of the tree.
     */
    final boolean forAllActivities(Predicate<ActivityRecord> callback,
            WindowContainer boundary, boolean includeBoundary, boolean traverseTopToBottom) {
        return forAllActivities(
                callback, boundary, includeBoundary, traverseTopToBottom, new boolean[1]);
    }

    private boolean forAllActivities(Predicate<ActivityRecord> callback,
            WindowContainer boundary, boolean includeBoundary, boolean traverseTopToBottom,
            boolean[] boundaryFound) {
        if (traverseTopToBottom) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                if (processForAllActivitiesWithBoundary(callback, boundary, includeBoundary,
                        traverseTopToBottom, boundaryFound, mChildren.get(i))) {
                    return true;
                }
            }
        } else {
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                if (processForAllActivitiesWithBoundary(callback, boundary, includeBoundary,
                        traverseTopToBottom, boundaryFound, mChildren.get(i))) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean processForAllActivitiesWithBoundary(Predicate<ActivityRecord> callback,
            WindowContainer boundary, boolean includeBoundary, boolean traverseTopToBottom,
            boolean[] boundaryFound, WindowContainer wc) {
        if (wc == boundary) {
            boundaryFound[0] = true;
            if (!includeBoundary) return false;
        }

        if (boundaryFound[0]) {
            return wc.forAllActivities(callback, traverseTopToBottom);
        }

        return wc.forAllActivities(
                callback, boundary, includeBoundary, traverseTopToBottom, boundaryFound);
    }

    /** @return {@code true} if this node or any of its children contains an activity. */
    boolean hasActivity() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            if (mChildren.get(i).hasActivity()) {
                return true;
            }
        }
        return false;
    }

    ActivityRecord getActivity(Predicate<ActivityRecord> callback) {
        return getActivity(callback, true /*traverseTopToBottom*/);
    }

    ActivityRecord getActivity(Predicate<ActivityRecord> callback, boolean traverseTopToBottom) {
        return getActivity(callback, traverseTopToBottom, null /*boundary*/);
    }

    ActivityRecord getActivity(Predicate<ActivityRecord> callback, boolean traverseTopToBottom,
            ActivityRecord boundary) {
        if (traverseTopToBottom) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final WindowContainer wc = mChildren.get(i);
                // TODO(b/156986561): Improve the correctness of the boundary check.
                if (wc == boundary) return boundary;

                final ActivityRecord r = wc.getActivity(callback, traverseTopToBottom, boundary);
                if (r != null) {
                    return r;
                }
            }
        } else {
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                final WindowContainer wc = mChildren.get(i);
                // TODO(b/156986561): Improve the correctness of the boundary check.
                if (wc == boundary) return boundary;

                final ActivityRecord r = wc.getActivity(callback, traverseTopToBottom, boundary);
                if (r != null) {
                    return r;
                }
            }
        }

        return null;
    }

    /**
     * Gets an activity in a branch of the tree.
     *
     * @param callback called to test if this is the activity that should be returned.
     * @param boundary We don't return activities via {@param callback} until we get to this node in
     *                 the tree.
     * @param includeBoundary If the boundary from be processed to return activities.
     * @param traverseTopToBottom direction to traverse the tree.
     * @return The activity if found or null.
     */
    final ActivityRecord getActivity(Predicate<ActivityRecord> callback,
            WindowContainer boundary, boolean includeBoundary, boolean traverseTopToBottom) {
        return getActivity(
                callback, boundary, includeBoundary, traverseTopToBottom, new boolean[1]);
    }

    private ActivityRecord getActivity(Predicate<ActivityRecord> callback,
            WindowContainer boundary, boolean includeBoundary, boolean traverseTopToBottom,
            boolean[] boundaryFound) {
        if (traverseTopToBottom) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final ActivityRecord r = processGetActivityWithBoundary(callback, boundary,
                        includeBoundary, traverseTopToBottom, boundaryFound, mChildren.get(i));
                if (r != null) {
                    return r;
                }
            }
        } else {
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                final ActivityRecord r = processGetActivityWithBoundary(callback, boundary,
                        includeBoundary, traverseTopToBottom, boundaryFound, mChildren.get(i));
                if (r != null) {
                    return r;
                }
            }
        }

        return null;
    }

    int getDistanceFromTop(WindowContainer child) {
        int idx = mChildren.indexOf(child);
        return idx < 0 ? -1 : mChildren.size() - 1 - idx;
    }

    private ActivityRecord processGetActivityWithBoundary(Predicate<ActivityRecord> callback,
            WindowContainer boundary, boolean includeBoundary, boolean traverseTopToBottom,
            boolean[] boundaryFound, WindowContainer wc) {
        if (wc == boundary || boundary == null) {
            boundaryFound[0] = true;
            if (!includeBoundary) return null;
        }

        if (boundaryFound[0]) {
            return wc.getActivity(callback, traverseTopToBottom);
        }

        return wc.getActivity(
                callback, boundary, includeBoundary, traverseTopToBottom, boundaryFound);
    }

    @SuppressWarnings("unchecked")
    static <T> Predicate<T> alwaysTruePredicate() {
        return (Predicate<T>) AlwaysTruePredicate.INSTANCE;
    }

    ActivityRecord getActivityAbove(ActivityRecord r) {
        return getActivity(alwaysTruePredicate(), r /* boundary */,
                false /*includeBoundary*/, false /*traverseTopToBottom*/);
    }

    ActivityRecord getActivityBelow(ActivityRecord r) {
        return getActivity(alwaysTruePredicate(), r /* boundary */,
                false /*includeBoundary*/, true /*traverseTopToBottom*/);
    }

    ActivityRecord getBottomMostActivity() {
        return getActivity(alwaysTruePredicate(), false /* traverseTopToBottom */);
    }

    ActivityRecord getTopMostActivity() {
        return getActivity(alwaysTruePredicate(), true /* traverseTopToBottom */);
    }

    ActivityRecord getTopActivity(boolean includeFinishing, boolean includeOverlays) {
        // Break down into 4 calls to avoid object creation due to capturing input params.
        if (includeFinishing) {
            if (includeOverlays) {
                return getActivity(alwaysTruePredicate());
            }
            return getActivity((r) -> !r.isTaskOverlay());
        } else if (includeOverlays) {
            return getActivity((r) -> !r.finishing);
        }

        return getActivity((r) -> !r.finishing && !r.isTaskOverlay());
    }

    void forAllWallpaperWindows(Consumer<WallpaperWindowToken> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).forAllWallpaperWindows(callback);
        }
    }

    /**
     * For all tasks at or below this container call the callback.
     *
     * @param callback Calls the {@link ToBooleanFunction#apply} method for each task found and
     *                 stops the search if {@link ToBooleanFunction#apply} returns {@code true}.
     */
    boolean forAllTasks(Predicate<Task> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            if (mChildren.get(i).forAllTasks(callback)) {
                return true;
            }
        }
        return false;
    }

    boolean forAllLeafTasks(Predicate<Task> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            if (mChildren.get(i).forAllLeafTasks(callback)) {
                return true;
            }
        }
        return false;
    }

    boolean forAllLeafTaskFragments(Predicate<TaskFragment> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            if (mChildren.get(i).forAllLeafTaskFragments(callback)) {
                return true;
            }
        }
        return false;
    }

    /**
     * For all root tasks at or below this container call the callback.
     *
     * @param callback Calls the {@link ToBooleanFunction#apply} method for each root task found and
     *                 stops the search if {@link ToBooleanFunction#apply} returns {@code true}.
     */
    boolean forAllRootTasks(Predicate<Task> callback) {
        return forAllRootTasks(callback, true /* traverseTopToBottom */);
    }

    boolean forAllRootTasks(Predicate<Task> callback, boolean traverseTopToBottom) {
        int count = mChildren.size();
        if (traverseTopToBottom) {
            for (int i = count - 1; i >= 0; --i) {
                if (mChildren.get(i).forAllRootTasks(callback, traverseTopToBottom)) {
                    return true;
                }
            }
        } else {
            for (int i = 0; i < count; i++) {
                if (mChildren.get(i).forAllRootTasks(callback, traverseTopToBottom)) {
                    return true;
                }
                // Root tasks may be removed from this display. Ensure each task will be processed
                // and the loop will end.
                int newCount = mChildren.size();
                i -= count - newCount;
                count = newCount;
            }
        }
        return false;
    }

    /**
     * For all tasks at or below this container call the callback.
     *
     * @param callback Callback to be called for every task.
     */
    void forAllTasks(Consumer<Task> callback) {
        forAllTasks(callback, true /*traverseTopToBottom*/);
    }

    void forAllTasks(Consumer<Task> callback, boolean traverseTopToBottom) {
        final int count = mChildren.size();
        if (traverseTopToBottom) {
            for (int i = count - 1; i >= 0; --i) {
                mChildren.get(i).forAllTasks(callback, traverseTopToBottom);
            }
        } else {
            for (int i = 0; i < count; i++) {
                mChildren.get(i).forAllTasks(callback, traverseTopToBottom);
            }
        }
    }

    /**
     * For all task fragments at or below this container call the callback.
     *
     * @param callback Callback to be called for every task.
     */
    void forAllTaskFragments(Consumer<TaskFragment> callback) {
        forAllTaskFragments(callback, true /*traverseTopToBottom*/);
    }

    void forAllTaskFragments(Consumer<TaskFragment> callback, boolean traverseTopToBottom) {
        final int count = mChildren.size();
        if (traverseTopToBottom) {
            for (int i = count - 1; i >= 0; --i) {
                mChildren.get(i).forAllTaskFragments(callback, traverseTopToBottom);
            }
        } else {
            for (int i = 0; i < count; i++) {
                mChildren.get(i).forAllTaskFragments(callback, traverseTopToBottom);
            }
        }
    }

    void forAllLeafTasks(Consumer<Task> callback, boolean traverseTopToBottom) {
        final int count = mChildren.size();
        if (traverseTopToBottom) {
            for (int i = count - 1; i >= 0; --i) {
                mChildren.get(i).forAllLeafTasks(callback, traverseTopToBottom);
            }
        } else {
            for (int i = 0; i < count; i++) {
                mChildren.get(i).forAllLeafTasks(callback, traverseTopToBottom);
            }
        }
    }

    void forAllLeafTaskFragments(Consumer<TaskFragment> callback, boolean traverseTopToBottom) {
        final int count = mChildren.size();
        if (traverseTopToBottom) {
            for (int i = count - 1; i >= 0; --i) {
                mChildren.get(i).forAllLeafTaskFragments(callback, traverseTopToBottom);
            }
        } else {
            for (int i = 0; i < count; i++) {
                mChildren.get(i).forAllLeafTaskFragments(callback, traverseTopToBottom);
            }
        }
    }

    /**
     * For all root tasks at or below this container call the callback.
     *
     * @param callback Callback to be called for every root task.
     */
    void forAllRootTasks(Consumer<Task> callback) {
        forAllRootTasks(callback, true /* traverseTopToBottom */);
    }

    void forAllRootTasks(Consumer<Task> callback, boolean traverseTopToBottom) {
        int count = mChildren.size();
        if (traverseTopToBottom) {
            for (int i = count - 1; i >= 0; --i) {
                mChildren.get(i).forAllRootTasks(callback, traverseTopToBottom);
            }
        } else {
            for (int i = 0; i < count; i++) {
                mChildren.get(i).forAllRootTasks(callback, traverseTopToBottom);
                // Root tasks may be removed from this display. Ensure each task will be processed
                // and the loop will end.
                int newCount = mChildren.size();
                i -= count - newCount;
                count = newCount;
            }
        }
    }

    Task getTaskBelow(Task t) {
        return getTask(alwaysTruePredicate(), t /* boundary */,
                false /* includeBoundary */, true /* traverseTopToBottom */);
    }

    Task getBottomMostTask() {
        return getTask(alwaysTruePredicate(), false /* traverseTopToBottom */);
    }

    Task getTopMostTask() {
        return getTask(alwaysTruePredicate(), true /* traverseTopToBottom */);
    }

    Task getTask(Predicate<Task> callback) {
        return getTask(callback, true /*traverseTopToBottom*/);
    }

    Task getTask(Predicate<Task> callback, boolean traverseTopToBottom) {
        if (traverseTopToBottom) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final Task t = mChildren.get(i).getTask(callback, traverseTopToBottom);
                if (t != null) {
                    return t;
                }
            }
        } else {
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                final Task t = mChildren.get(i).getTask(callback, traverseTopToBottom);
                if (t != null) {
                    return t;
                }
            }
        }

        return null;
    }

    /**
     * Gets an task in a branch of the tree.
     *
     * @param callback called to test if this is the task that should be returned.
     * @param boundary We don't return tasks via {@param callback} until we get to this node in
     *                 the tree.
     * @param includeBoundary If the boundary from be processed to return tasks.
     * @param traverseTopToBottom direction to traverse the tree.
     * @return The task if found or null.
     */
    final Task getTask(Predicate<Task> callback, WindowContainer boundary, boolean includeBoundary,
            boolean traverseTopToBottom) {
        return getTask(callback, boundary, includeBoundary, traverseTopToBottom, new boolean[1]);
    }

    private Task getTask(Predicate<Task> callback,
            WindowContainer boundary, boolean includeBoundary, boolean traverseTopToBottom,
            boolean[] boundaryFound) {
        if (traverseTopToBottom) {
            for (int i = mChildren.size() - 1; i >= 0; --i) {
                final Task t = processGetTaskWithBoundary(callback, boundary,
                        includeBoundary, traverseTopToBottom, boundaryFound, mChildren.get(i));
                if (t != null) {
                    return t;
                }
            }
        } else {
            final int count = mChildren.size();
            for (int i = 0; i < count; i++) {
                final Task t = processGetTaskWithBoundary(callback, boundary,
                        includeBoundary, traverseTopToBottom, boundaryFound, mChildren.get(i));
                if (t != null) {
                    return t;
                }
            }
        }

        return null;
    }

    /**
     * Gets a root task in a branch of the tree.
     *
     * @param callback called to test if this is the task that should be returned.
     * @return The root task if found or null.
     */
    @Nullable
    Task getRootTask(Predicate<Task> callback) {
        return getRootTask(callback, true /*traverseTopToBottom*/);
    }

    @Nullable
    Task getRootTask(Predicate<Task> callback, boolean traverseTopToBottom) {
        int count = mChildren.size();
        if (traverseTopToBottom) {
            for (int i = count - 1; i >= 0; --i) {
                final Task t = mChildren.get(i).getRootTask(callback, traverseTopToBottom);
                if (t != null) {
                    return t;
                }
            }
        } else {
            for (int i = 0; i < count; i++) {
                final Task t = mChildren.get(i).getRootTask(callback, traverseTopToBottom);
                if (t != null) {
                    return t;
                }
                // Root tasks may be removed from this display. Ensure each task will be processed
                // and the loop will end.
                int newCount = mChildren.size();
                i -= count - newCount;
                count = newCount;
            }
        }

        return null;
    }

    private Task processGetTaskWithBoundary(Predicate<Task> callback,
            WindowContainer boundary, boolean includeBoundary, boolean traverseTopToBottom,
            boolean[] boundaryFound, WindowContainer wc) {
        if (wc == boundary || boundary == null) {
            boundaryFound[0] = true;
            if (!includeBoundary) return null;
        }

        if (boundaryFound[0]) {
            return wc.getTask(callback, traverseTopToBottom);
        }

        return wc.getTask(
                callback, boundary, includeBoundary, traverseTopToBottom, boundaryFound);
    }

    @Nullable
    TaskFragment getTaskFragment(Predicate<TaskFragment> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final TaskFragment tf = mChildren.get(i).getTaskFragment(callback);
            if (tf != null) {
                return tf;
            }
        }
        return null;
    }

    WindowState getWindow(Predicate<WindowState> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowState w = mChildren.get(i).getWindow(callback);
            if (w != null) {
                return w;
            }
        }

        return null;
    }

    void forAllDisplayAreas(Consumer<DisplayArea> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).forAllDisplayAreas(callback);
        }
    }

    /**
     * For all {@link TaskDisplayArea} at or below this container call the callback.
     * @param callback Applies on each {@link TaskDisplayArea} found and stops the search if it
     *                 returns {@code true}.
     * @param traverseTopToBottom If {@code true}, traverses the hierarchy from top-to-bottom in
     *                            terms of z-order, else from bottom-to-top.
     * @return {@code true} if the search ended before we reached the end of the hierarchy due to
     *         callback returning {@code true}.
     */
    boolean forAllTaskDisplayAreas(Predicate<TaskDisplayArea> callback,
            boolean traverseTopToBottom) {
        int childCount = mChildren.size();
        int i = traverseTopToBottom ? childCount - 1 : 0;
        while (i >= 0 && i < childCount) {
            if (mChildren.get(i).forAllTaskDisplayAreas(callback, traverseTopToBottom)) {
                return true;
            }
            i += traverseTopToBottom ? -1 : 1;
        }
        return false;
    }

    /**
     * For all {@link TaskDisplayArea} at or below this container call the callback. Traverses from
     * top to bottom in terms of z-order.
     * @param callback Applies on each {@link TaskDisplayArea} found and stops the search if it
     *                 returns {@code true}.
     * @return {@code true} if the search ended before we reached the end of the hierarchy due to
     *         callback returning {@code true}.
     */
    boolean forAllTaskDisplayAreas(Predicate<TaskDisplayArea> callback) {
        return forAllTaskDisplayAreas(callback, true /* traverseTopToBottom */);
    }

    /**
     * For all {@link TaskDisplayArea} at or below this container call the callback.
     * @param callback Applies on each {@link TaskDisplayArea} found.
     * @param traverseTopToBottom If {@code true}, traverses the hierarchy from top-to-bottom in
     *                            terms of z-order, else from bottom-to-top.
     */
    void forAllTaskDisplayAreas(Consumer<TaskDisplayArea> callback, boolean traverseTopToBottom) {
        int childCount = mChildren.size();
        int i = traverseTopToBottom ? childCount - 1 : 0;
        while (i >= 0 && i < childCount) {
            mChildren.get(i).forAllTaskDisplayAreas(callback, traverseTopToBottom);
            i += traverseTopToBottom ? -1 : 1;
        }
    }

    /**
     * For all {@link TaskDisplayArea} at or below this container call the callback. Traverses from
     * top to bottom in terms of z-order.
     * @param callback Applies on each {@link TaskDisplayArea} found.
     */
    void forAllTaskDisplayAreas(Consumer<TaskDisplayArea> callback) {
        forAllTaskDisplayAreas(callback, true /* traverseTopToBottom */);
    }

    /**
     * Performs a reduction on all {@link TaskDisplayArea} at or below this container, using the
     * provided initial value and an accumulation function, and returns the reduced value.
     * @param accumulator Applies on each {@link TaskDisplayArea} found with the accumulative result
     *                    from the previous call.
     * @param initValue The initial value to pass to the accumulating function with the first
     *                  {@link TaskDisplayArea}.
     * @param traverseTopToBottom If {@code true}, traverses the hierarchy from top-to-bottom in
     *                            terms of z-order, else from bottom-to-top.
     * @return the accumulative result.
     */
    @Nullable
    <R> R reduceOnAllTaskDisplayAreas(BiFunction<TaskDisplayArea, R, R> accumulator,
            @Nullable R initValue, boolean traverseTopToBottom) {
        int childCount = mChildren.size();
        int i = traverseTopToBottom ? childCount - 1 : 0;
        R result = initValue;
        while (i >= 0 && i < childCount) {
            result = (R) mChildren.get(i)
                    .reduceOnAllTaskDisplayAreas(accumulator, result, traverseTopToBottom);
            i += traverseTopToBottom ? -1 : 1;
        }
        return result;
    }

    /**
     * Performs a reduction on all {@link TaskDisplayArea} at or below this container, using the
     * provided initial value and an accumulation function, and returns the reduced value. Traverses
     * from top to bottom in terms of z-order.
     * @param accumulator Applies on each {@link TaskDisplayArea} found with the accumulative result
     *                    from the previous call.
     * @param initValue The initial value to pass to the accumulating function with the first
     *                  {@link TaskDisplayArea}.
     * @return the accumulative result.
     */
    @Nullable
    <R> R reduceOnAllTaskDisplayAreas(BiFunction<TaskDisplayArea, R, R> accumulator,
            @Nullable R initValue) {
        return reduceOnAllTaskDisplayAreas(accumulator, initValue, true /* traverseTopToBottom */);
    }

    /**
     * Finds the first non {@code null} return value from calling the callback on all
     * {@link DisplayArea} at or below this container. Traverses from top to bottom in terms of
     * z-order.
     * @param callback Applies on each {@link DisplayArea} found and stops the search if it
     *                 returns non {@code null}.
     * @return the first returned object that is not {@code null}. Returns {@code null} if not
     *         found.
     */
    @Nullable
    <R> R getItemFromDisplayAreas(Function<DisplayArea, R> callback) {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            R result = (R) mChildren.get(i).getItemFromDisplayAreas(callback);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /**
     * Finds the first non {@code null} return value from calling the callback on all
     * {@link TaskDisplayArea} at or below this container.
     * @param callback Applies on each {@link TaskDisplayArea} found and stops the search if it
     *                 returns non {@code null}.
     * @param traverseTopToBottom If {@code true}, traverses the hierarchy from top-to-bottom in
     *                            terms of z-order, else from bottom-to-top.
     * @return the first returned object that is not {@code null}. Returns {@code null} if not
     *         found.
     */
    @Nullable
    <R> R getItemFromTaskDisplayAreas(Function<TaskDisplayArea, R> callback,
            boolean traverseTopToBottom) {
        int childCount = mChildren.size();
        int i = traverseTopToBottom ? childCount - 1 : 0;
        while (i >= 0 && i < childCount) {
            R result = (R) mChildren.get(i)
                    .getItemFromTaskDisplayAreas(callback, traverseTopToBottom);
            if (result != null) {
                return result;
            }
            i += traverseTopToBottom ? -1 : 1;
        }
        return null;
    }

    /**
     * Finds the first non {@code null} return value from calling the callback on all
     * {@link TaskDisplayArea} at or below this container. Traverses from top to bottom in terms of
     * z-order.
     * @param callback Applies on each {@link TaskDisplayArea} found and stops the search if it
     *                 returns non {@code null}.
     * @return the first returned object that is not {@code null}. Returns {@code null} if not
     *         found.
     */
    @Nullable
    <R> R getItemFromTaskDisplayAreas(Function<TaskDisplayArea, R> callback) {
        return getItemFromTaskDisplayAreas(callback, true /* traverseTopToBottom */);
    }

    /**
     * Returns 1, 0, or -1 depending on if this container is greater than, equal to, or lesser than
     * the input container in terms of z-order.
     */
    @Override
    public int compareTo(WindowContainer other) {
        if (this == other) {
            return 0;
        }

        if (mParent != null && mParent == other.mParent) {
            final ArrayList<WindowContainer> list = mParent.mChildren;
            return list.indexOf(this) > list.indexOf(other) ? 1 : -1;
        }

        final LinkedList<WindowContainer> thisParentChain = mTmpChain1;
        final LinkedList<WindowContainer> otherParentChain = mTmpChain2;
        try {
            getParents(thisParentChain);
            other.getParents(otherParentChain);

            // Find the common ancestor of both containers.
            WindowContainer commonAncestor = null;
            WindowContainer thisTop = thisParentChain.peekLast();
            WindowContainer otherTop = otherParentChain.peekLast();
            while (thisTop != null && otherTop != null && thisTop == otherTop) {
                commonAncestor = thisParentChain.removeLast();
                otherParentChain.removeLast();
                thisTop = thisParentChain.peekLast();
                otherTop = otherParentChain.peekLast();
            }

            // Containers don't belong to the same hierarchy???
            if (commonAncestor == null) {
                final int thisZ = getPrefixOrderIndex();
                final int otherZ = other.getPrefixOrderIndex();
                Slog.w(TAG, "Compare not in the same hierarchy this="
                        + thisParentChain + " thisZ=" + thisZ + " other="
                        + otherParentChain + " otherZ=" + otherZ);
                return Integer.compare(thisZ, otherZ);
            }

            // Children are always considered greater than their parents, so if one of the containers
            // we are comparing it the parent of the other then whichever is the child is greater.
            if (commonAncestor == this) {
                return -1;
            } else if (commonAncestor == other) {
                return 1;
            }

            // The position of the first non-common ancestor in the common ancestor list determines
            // which is greater the which.
            final ArrayList<WindowContainer> list = commonAncestor.mChildren;
            return list.indexOf(thisParentChain.peekLast()) > list.indexOf(otherParentChain.peekLast())
                    ? 1 : -1;
        } finally {
            mTmpChain1.clear();
            mTmpChain2.clear();
        }
    }

    private void getParents(LinkedList<WindowContainer> parents) {
        parents.clear();
        WindowContainer current = this;
        do {
            parents.addLast(current);
            current = current.mParent;
        } while (current != null);
    }

    Builder makeSurface() {
        final WindowContainer p = getParent();
        return p.makeChildSurface(this);
    }

    /**
     * @param child The WindowContainer this child surface is for, or null if the Surface
     *              is not assosciated with a WindowContainer (e.g. a surface used for Dimming).
     */
    Builder makeChildSurface(WindowContainer child) {
        final WindowContainer p = getParent();
        // Give the parent a chance to set properties. In hierarchy v1 we rely
        // on this to set full-screen dimensions on all our Surface-less Layers.
        return p.makeChildSurface(child)
                .setParent(mSurfaceControl);
    }
    /*
     * @return The SurfaceControl parent for this containers SurfaceControl.
     *         The SurfaceControl must be valid if non-null.
     */
    @Override
    public SurfaceControl getParentSurfaceControl() {
        final WindowContainer parent = getParent();
        if (parent == null) {
            return null;
        }
        return parent.getSurfaceControl();
    }

    /**
     * @return Whether this WindowContainer should be magnified by the accessibility magnifier.
     */
    boolean shouldMagnify() {
        if (mSurfaceControl == null) {
            return false;
        }

        for (int i = 0; i < mChildren.size(); i++) {
            if (!mChildren.get(i).shouldMagnify()) {
                return false;
            }
        }
        return true;
    }

    void assignLayer(Transaction t, int layer) {
        // Don't assign layers while a transition animation is playing
        // TODO(b/173528115): establish robust best-practices around z-order fighting.
        if (!mTransitionController.canAssignLayers(this)) return;
        final boolean changed = layer != mLastLayer || mLastRelativeToLayer != null;
        if (mSurfaceControl != null && changed) {
            setLayer(t, layer);
            mLastLayer = layer;
            mLastRelativeToLayer = null;
        }
    }

    void assignRelativeLayer(Transaction t, SurfaceControl relativeTo, int layer,
            boolean forceUpdate) {
        final boolean changed = layer != mLastLayer || mLastRelativeToLayer != relativeTo;
        if (mSurfaceControl != null && (changed || forceUpdate)) {
            setRelativeLayer(t, relativeTo, layer);
            mLastLayer = layer;
            mLastRelativeToLayer = relativeTo;
        }
    }

    void assignRelativeLayer(Transaction t, SurfaceControl relativeTo, int layer) {
        assignRelativeLayer(t, relativeTo, layer, false /* forceUpdate */);
    }

    protected void setLayer(Transaction t, int layer) {
        if (mSurfaceFreezer.hasLeash()) {
            // When the freezer has created animation leash parent for the window, set the layer
            // there instead.
            mSurfaceFreezer.setLayer(t, layer);
        } else {
            // Route through surface animator to accommodate that our surface control might be
            // attached to the leash, and leash is attached to parent container.
            mSurfaceAnimator.setLayer(t, layer);
        }
    }

    int getLastLayer() {
        return mLastLayer;
    }

    SurfaceControl getLastRelativeLayer() {
        return mLastRelativeToLayer;
    }

    protected void setRelativeLayer(Transaction t, SurfaceControl relativeTo, int layer) {
        if (mSurfaceFreezer.hasLeash()) {
            // When the freezer has created animation leash parent for the window, set the layer
            // there instead.
            mSurfaceFreezer.setRelativeLayer(t, relativeTo, layer);
        } else {
            // Route through surface animator to accommodate that our surface control might be
            // attached to the leash, and leash is attached to parent container.
            mSurfaceAnimator.setRelativeLayer(t, relativeTo, layer);
        }
    }

    protected void reparentSurfaceControl(Transaction t, SurfaceControl newParent) {
        // Don't reparent active leashes since the animator won't know about the change.
        if (mSurfaceFreezer.hasLeash() || mSurfaceAnimator.hasLeash()) return;
        t.reparent(getSurfaceControl(), newParent);
    }

    void assignChildLayers(Transaction t) {
        int layer = 0;

        // We use two passes as a way to promote children which
        // need Z-boosting to the end of the list.
        for (int j = 0; j < mChildren.size(); ++j) {
            final WindowContainer wc = mChildren.get(j);
            wc.assignChildLayers(t);
            if (!wc.needsZBoost()) {
                wc.assignLayer(t, layer++);
            }
        }
        for (int j = 0; j < mChildren.size(); ++j) {
            final WindowContainer wc = mChildren.get(j);
            if (wc.needsZBoost()) {
                wc.assignLayer(t, layer++);
            }
        }
        if (mOverlayHost != null) {
            mOverlayHost.setLayer(t, layer++);
        }
    }

    void assignChildLayers() {
        assignChildLayers(getSyncTransaction());
        scheduleAnimation();
    }

    boolean needsZBoost() {
        if (mNeedsZBoost) return true;
        for (int i = 0; i < mChildren.size(); i++) {
            if (mChildren.get(i).needsZBoost()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Write to a protocol buffer output stream. Protocol buffer message definition is at
     * {@link com.android.server.wm.WindowContainerProto}.
     *
     * @param proto     Stream to write the WindowContainer object to.
     * @param fieldId   Field Id of the WindowContainer as defined in the parent message.
     * @param logLevel  Determines the amount of data to be written to the Protobuf.
     * @hide
     */
    @CallSuper
    @Override
    public void dumpDebug(ProtoOutputStream proto, long fieldId,
            @WindowTracingLogLevel int logLevel) {
        boolean isVisible = isVisible();
        if (logLevel == WindowTracingLogLevel.CRITICAL && !isVisible) {
            return;
        }

        final long token = proto.start(fieldId);
        super.dumpDebug(proto, CONFIGURATION_CONTAINER, logLevel);
        proto.write(ORIENTATION, mOverrideOrientation);
        proto.write(VISIBLE, isVisible);
        writeIdentifierToProto(proto, IDENTIFIER);
        if (mSurfaceAnimator.isAnimating()) {
            mSurfaceAnimator.dumpDebug(proto, SURFACE_ANIMATOR);
        }
        if (mSurfaceControl != null) {
            mSurfaceControl.dumpDebug(proto, SURFACE_CONTROL);
        }

        // add children to proto
        for (int i = 0; i < getChildCount(); i++) {
            final long childToken = proto.start(WindowContainerProto.CHILDREN);
            final E child = getChildAt(i);
            child.dumpDebug(proto, child.getProtoFieldId(), logLevel);
            proto.end(childToken);
        }
        proto.end(token);
    }

    /**
     * @return a proto field id to identify where to add the derived class to the generic window
     * container proto.
     */
    long getProtoFieldId() {
        return WINDOW_CONTAINER;
    }

    private ForAllWindowsConsumerWrapper obtainConsumerWrapper(Consumer<WindowState> consumer) {
        ForAllWindowsConsumerWrapper wrapper = mConsumerWrapperPool.acquire();
        if (wrapper == null) {
            wrapper = new ForAllWindowsConsumerWrapper();
        }
        wrapper.setConsumer(consumer);
        return wrapper;
    }

    private final class ForAllWindowsConsumerWrapper implements ToBooleanFunction<WindowState> {

        private Consumer<WindowState> mConsumer;

        void setConsumer(Consumer<WindowState> consumer) {
            mConsumer = consumer;
        }

        @Override
        public boolean apply(WindowState w) {
            mConsumer.accept(w);
            return false;
        }

        void release() {
            mConsumer = null;
            mConsumerWrapperPool.release(this);
        }
    }

    // TODO(b/68336570): Should this really be on WindowContainer since it
    // can only be used on the top-level nodes that aren't animated?
    // (otherwise we would be fighting other callers of setMatrix).
    void applyMagnificationSpec(Transaction t, MagnificationSpec spec) {
        if (shouldMagnify()) {
            t.setMatrix(mSurfaceControl, spec.scale, 0, 0, spec.scale)
                    .setPosition(mSurfaceControl, spec.offsetX + mLastSurfacePosition.x,
                            spec.offsetY + mLastSurfacePosition.y);
            mLastMagnificationSpec = spec;
        } else {
            clearMagnificationSpec(t);
            for (int i = 0; i < mChildren.size(); i++) {
                mChildren.get(i).applyMagnificationSpec(t, spec);
            }
        }
    }

    void clearMagnificationSpec(Transaction t) {
        if (mLastMagnificationSpec != null) {
            t.setMatrix(mSurfaceControl, 1, 0, 0, 1)
                .setPosition(mSurfaceControl, mLastSurfacePosition.x, mLastSurfacePosition.y);
        }
        mLastMagnificationSpec = null;
        for (int i = 0; i < mChildren.size(); i++) {
            mChildren.get(i).clearMagnificationSpec(t);
        }
    }

    void prepareSurfaces() {
        for (int i = 0; i < mChildren.size(); i++) {
            mChildren.get(i).prepareSurfaces();
        }
    }

    /**
     * Go through the hierarchy to allow windows to request a dim if needed
     */
    void adjustDims() {
        for (int i = 0; i < mChildren.size(); i++) {
            mChildren.get(i).adjustDims();
        }
    }

    /**
     * Trigger a call to prepareSurfaces from the animation thread, such that pending transactions
     * will be applied.
     */
    void scheduleAnimation() {
        mWmService.scheduleAnimationLocked();
    }

    /**
     * @return The SurfaceControl for this container.
     *         The SurfaceControl must be valid if non-null.
     */
    @Override
    public SurfaceControl getSurfaceControl() {
        return mSurfaceControl;
    }

    /**
     * Use this method instead of {@link #getPendingTransaction()} if the Transaction should be
     * synchronized with the client.
     *
     * @return {@link #mBLASTSyncTransaction} if available. Otherwise, returns
     * {@link #getPendingTransaction()}
     */
    @Override
    public Transaction getSyncTransaction() {
        if (mSyncTransactionCommitCallbackDepth > 0) {
            return mSyncTransaction;
        }
        if (mSyncState != SYNC_STATE_NONE) {
            return mSyncTransaction;
        }

        return getPendingTransaction();
    }

    @Override
    public Transaction getPendingTransaction() {
        final WindowContainer<?> dc = mDisplayContent;
        if (dc != null && dc.mPendingTransaction != null) {
            return dc.mPendingTransaction;
        }
        // This WindowContainer has not attached to a display yet or this is a DisplayContent, so we
        // let the caller to save the surface operations within the local mPendingTransaction.
        // If this is not a DisplayContent, we will merge it to the pending transaction of its
        // display once it attaches to it.
        if (mPendingTransaction == null) {
            mPendingTransaction = mWmService.mTransactionFactory.get();
        }
        return mPendingTransaction;
    }

    /**
     * Starts an animation on the container.
     *
     * @param anim The animation to run.
     * @param hidden Whether our container is currently hidden. TODO This should use isVisible at
     *               some point but the meaning is too weird to work for all containers.
     * @param type The type of animation defined as {@link AnimationType}.
     * @param animationFinishedCallback The callback being triggered when the animation finishes.
     * @param animationCancelledCallback The callback is triggered after the SurfaceAnimator sends a
     *                                   cancel call to the underlying AnimationAdapter.
     * @param snapshotAnim  The animation to run for the snapshot. {@code null} if there is no
     *                      snapshot.
     */
    void startAnimation(Transaction t, AnimationAdapter anim, boolean hidden,
            @AnimationType int type,
            @Nullable OnAnimationFinishedCallback animationFinishedCallback,
            @Nullable Runnable animationCancelledCallback,
            @Nullable AnimationAdapter snapshotAnim) {
        ProtoLog.v(WM_DEBUG_ANIM, "Starting animation on %s: type=%d, anim=%s",
                this, type, anim);

        // TODO: This should use isVisible() but because isVisible has a really weird meaning at
        // the moment this doesn't work for all animatable window containers.
        mSurfaceAnimator.startAnimation(t, anim, hidden, type, animationFinishedCallback,
                animationCancelledCallback, snapshotAnim, mSurfaceFreezer);
    }

    void startAnimation(Transaction t, AnimationAdapter anim, boolean hidden,
            @AnimationType int type,
            @Nullable OnAnimationFinishedCallback animationFinishedCallback) {
        startAnimation(t, anim, hidden, type, animationFinishedCallback,
                null /* adapterAnimationCancelledCallback */, null /* snapshotAnim */);
    }

    void startAnimation(Transaction t, AnimationAdapter anim, boolean hidden,
            @AnimationType int type) {
        startAnimation(t, anim, hidden, type, null /* animationFinishedCallback */);
    }

    void transferAnimation(WindowContainer from) {
        mSurfaceAnimator.transferAnimation(from.mSurfaceAnimator);
    }

    void cancelAnimation() {
        doAnimationFinished(mSurfaceAnimator.getAnimationType(), mSurfaceAnimator.getAnimation());
        mSurfaceAnimator.cancelAnimation();
        mSurfaceFreezer.unfreeze(getSyncTransaction());
    }

    /** Whether we can start change transition with this window and current display status. */
    boolean canStartChangeTransition() {
        if (mWmService.mDisableTransitionAnimation || !okToAnimate()) return false;

        // Change transition only make sense as we go from "visible" to "visible".
        if (mDisplayContent == null || getSurfaceControl() == null
                || !isVisible() || !isVisibleRequested()) {
            return false;
        }

        // Make sure display isn't a part of the transition already - needed for legacy transitions.
        if (mDisplayContent.inTransition()) return false;

        // Screenshots are turned off when PiP is undergoing changes.
        return ActivityTaskManagerService.isPip2ExperimentEnabled() || !isPipChange();
    }

    /** Returns true if WC is pinned and undergoing changes. */
    private boolean isPipChange() {
        final boolean isExitingPip = this.asTaskFragment() != null
                && mTransitionController.getWindowingModeAtStart(this) == WINDOWING_MODE_PINNED
                && !inPinnedWindowingMode();

        return isExitingPip || inPinnedWindowingMode()
                || (getParent() != null && getParent().inPinnedWindowingMode());
    }

    /**
     * Initializes a change transition. See {@link SurfaceFreezer} for more information.
     *
     * For now, this will only be called for the following cases:
     * 1. {@link Task} is changing windowing mode between fullscreen and freeform.
     * 2. {@link TaskFragment} is organized and is changing window bounds.
     * 3. {@link ActivityRecord} is reparented into an organized {@link TaskFragment}. (The
     *    transition will happen on the {@link TaskFragment} for this case).
     *
     * This shouldn't be called on other {@link WindowContainer} unless there is a valid
     * use case.
     *
     * @param startBounds The original bounds (on screen) of the surface we are snapshotting.
     * @param freezeTarget The surface to take snapshot from. If {@code null}, we will take a
     *                     snapshot from {@link #getFreezeSnapshotTarget()}.
     */
    void initializeChangeTransition(Rect startBounds, @Nullable SurfaceControl freezeTarget) {
        if (mDisplayContent.mTransitionController.isShellTransitionsEnabled()) {
            mDisplayContent.mTransitionController.collectVisibleChange(this);
            return;
        }
        mDisplayContent.prepareAppTransition(TRANSIT_CHANGE);
        mDisplayContent.mChangingContainers.add(this);
        // Calculate the relative position in parent container.
        final Rect parentBounds = getParent().getBounds();
        mTmpPoint.set(startBounds.left - parentBounds.left, startBounds.top - parentBounds.top);
        mSurfaceFreezer.freeze(getSyncTransaction(), startBounds, mTmpPoint, freezeTarget);
    }

    void initializeChangeTransition(Rect startBounds) {
        initializeChangeTransition(startBounds, null /* freezeTarget */);
    }

    ArraySet<WindowContainer> getAnimationSources() {
        return mSurfaceAnimationSources;
    }

    @Override
    public SurfaceControl getFreezeSnapshotTarget() {
        // Only allow freezing if this window is in a TRANSIT_CHANGE
        if (!mDisplayContent.mAppTransition.containsTransitRequest(TRANSIT_CHANGE)
                || !mDisplayContent.mChangingContainers.contains(this)) {
            return null;
        }
        return getSurfaceControl();
    }

    @Override
    public void onUnfrozen() {
        if (mDisplayContent != null) {
            mDisplayContent.mChangingContainers.remove(this);
        }
    }

    @Override
    public Builder makeAnimationLeash() {
        return makeSurface().setContainerLayer();
    }

    @Override
    public SurfaceControl getAnimationLeashParent() {
        return getParentSurfaceControl();
    }

    // TODO: Remove this and use #getBounds() instead once we set an app transition animation
    // on TaskStack.
    Rect getAnimationBounds(int appRootTaskClipMode) {
        return getBounds();
    }

    /** Gets the position relative to parent for animation. */
    void getAnimationPosition(Point outPosition) {
        getRelativePosition(outPosition);
    }

    /**
     * Applies the app transition animation according the given the layout properties in the
     * window hierarchy.
     *
     * @param lp The layout parameters of the window.
     * @param transit The app transition type indicates what kind of transition to be applied.
     * @param enter Whether the app transition is entering transition or not.
     * @param isVoiceInteraction Whether the container is participating in voice interaction or not.
     * @param sources {@link ActivityRecord}s which causes this app transition animation.
     *
     * @return {@code true} when the container applied the app transition, {@code false} if the
     *         app transition is disabled or skipped.
     *
     * @see #getAnimationAdapter
     */
    boolean applyAnimation(WindowManager.LayoutParams lp, @TransitionOldType int transit,
            boolean enter, boolean isVoiceInteraction,
            @Nullable ArrayList<WindowContainer> sources) {
        if (mWmService.mDisableTransitionAnimation) {
            ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                    "applyAnimation: transition animation is disabled or skipped. "
                            + "container=%s", this);
            cancelAnimation();
            return false;
        }

        // Only apply an animation if the display isn't frozen. If it is frozen, there is no reason
        // to animate and it can cause strange artifacts when we unfreeze the display if some
        // different animation is running.
        try {
            Trace.traceBegin(TRACE_TAG_WINDOW_MANAGER, "WC#applyAnimation");
            if (okToAnimate()) {
                ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM,
                        "applyAnimation: transit=%s, enter=%b, wc=%s",
                        AppTransition.appTransitionOldToString(transit), enter, this);
                applyAnimationUnchecked(lp, enter, transit, isVoiceInteraction, sources);
            } else {
                cancelAnimation();
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_WINDOW_MANAGER);
        }

        return isAnimating();
    }

    /**
     * Gets the {@link AnimationAdapter} according the given window layout properties in the window
     * hierarchy.
     *
     * @return The return value will always contain two elements, one for normal animations and the
     *         other for thumbnail animation, both can be {@code null}.
     *
     * @See com.android.server.wm.RemoteAnimationController.RemoteAnimationRecord
     * @See LocalAnimationAdapter
     */
    Pair<AnimationAdapter, AnimationAdapter> getAnimationAdapter(WindowManager.LayoutParams lp,
            @TransitionOldType int transit, boolean enter, boolean isVoiceInteraction) {
        final Pair<AnimationAdapter, AnimationAdapter> resultAdapters;
        final int appRootTaskClipMode = getDisplayContent().mAppTransition.getAppRootTaskClipMode();

        // Separate position and size for use in animators.
        final Rect screenBounds = getAnimationBounds(appRootTaskClipMode);
        mTmpRect.set(screenBounds);
        getAnimationPosition(mTmpPoint);
        mTmpRect.offsetTo(0, 0);

        final AppTransition appTransition = getDisplayContent().mAppTransition;
        final RemoteAnimationController controller = appTransition.getRemoteAnimationController();
        final boolean isChanging = AppTransition.isChangeTransitOld(transit) && enter
                && isChangingAppTransition();

        // Delaying animation start isn't compatible with remote animations at all.
        if (controller != null && !mSurfaceAnimator.isAnimationStartDelayed()) {
            // Here we load App XML in order to read com.android.R.styleable#Animation_showBackdrop.
            boolean showBackdrop = false;
            // Optionally set backdrop color if App explicitly provides it through
            // {@link Activity#overridePendingTransition(int, int, int)}.
            @ColorInt int backdropColor = 0;
            if (controller.isFromActivityEmbedding()) {
                if (isChanging) {
                    // When there are more than one changing containers, it may leave part of the
                    // screen empty. Show background color to cover that.
                    showBackdrop = getDisplayContent().mChangingContainers.size() > 1;
                    backdropColor = appTransition.getNextAppTransitionBackgroundColor();
                } else {
                    // Check whether the app has requested to show backdrop for open/close
                    // transition.
                    final Animation a = appTransition.getNextAppRequestedAnimation(enter);
                    if (a != null) {
                        showBackdrop = a.getShowBackdrop();
                        backdropColor = a.getBackdropColor();
                    }
                }
            }
            final Rect localBounds = new Rect(mTmpRect);
            localBounds.offsetTo(mTmpPoint.x, mTmpPoint.y);
            final RemoteAnimationController.RemoteAnimationRecord adapters;
            if (!isChanging && !enter && isClosingWhenResizing()) {
                // Container that is closing while resizing. Pass in the closing start bounds, so
                // the animation can start with the correct bounds, there won't be a snapshot.
                // Cleanup the mClosingChangingContainers so that when the animation is finished, it
                // will reset the surface.
                final Rect closingStartBounds = getDisplayContent().mClosingChangingContainers
                        .remove(this);
                adapters = controller.createRemoteAnimationRecord(
                        this, mTmpPoint, localBounds, screenBounds, closingStartBounds,
                        showBackdrop, false /* shouldCreateSnapshot */);
            } else {
                final Rect startBounds = isChanging ? mSurfaceFreezer.mFreezeBounds : null;
                adapters = controller.createRemoteAnimationRecord(
                        this, mTmpPoint, localBounds, screenBounds, startBounds, showBackdrop);
            }
            if (backdropColor != 0) {
                adapters.setBackDropColor(backdropColor);
            }
            if (!isChanging) {
                adapters.setMode(enter
                        ? RemoteAnimationTarget.MODE_OPENING
                        : RemoteAnimationTarget.MODE_CLOSING);
            }
            resultAdapters = new Pair<>(adapters.mAdapter, adapters.mThumbnailAdapter);
        } else if (isChanging) {
            final float durationScale = mWmService.getTransitionAnimationScaleLocked();
            final DisplayInfo displayInfo = getDisplayContent().getDisplayInfo();
            mTmpRect.offsetTo(mTmpPoint.x, mTmpPoint.y);

            final AnimationAdapter adapter = new LocalAnimationAdapter(
                    new WindowChangeAnimationSpec(mSurfaceFreezer.mFreezeBounds, mTmpRect,
                            displayInfo, durationScale, true /* isAppAnimation */,
                            false /* isThumbnail */),
                    getSurfaceAnimationRunner());

            final AnimationAdapter thumbnailAdapter = mSurfaceFreezer.mSnapshot != null
                    ? new LocalAnimationAdapter(new WindowChangeAnimationSpec(
                    mSurfaceFreezer.mFreezeBounds, mTmpRect, displayInfo, durationScale,
                    true /* isAppAnimation */, true /* isThumbnail */), getSurfaceAnimationRunner())
                    : null;
            resultAdapters = new Pair<>(adapter, thumbnailAdapter);
            mTransit = transit;
            mTransitFlags = getDisplayContent().mAppTransition.getTransitFlags();
        } else {
            mNeedsAnimationBoundsLayer = (appRootTaskClipMode == ROOT_TASK_CLIP_AFTER_ANIM);
            final Animation a = loadAnimation(lp, transit, enter, isVoiceInteraction);

            if (a != null) {
                // Only apply corner radius to animation if we're not in multi window mode.
                // We don't want rounded corners when in pip or split screen.
                final float windowCornerRadius = !inMultiWindowMode()
                        ? getDisplayContent().getWindowCornerRadius()
                        : 0;
                if (asActivityRecord() != null
                        && asActivityRecord().isNeedsLetterboxedAnimation()) {
                    asActivityRecord().getLetterboxInnerBounds(mTmpRect);
                }
                AnimationAdapter adapter = new LocalAnimationAdapter(
                        new WindowAnimationSpec(a, mTmpPoint, mTmpRect,
                                getDisplayContent().mAppTransition.canSkipFirstFrame(),
                                appRootTaskClipMode, true /* isAppAnimation */, windowCornerRadius),
                        getSurfaceAnimationRunner());

                resultAdapters = new Pair<>(adapter, null);
                mNeedsZBoost = a.getZAdjustment() == Animation.ZORDER_TOP
                        || AppTransition.isClosingTransitOld(transit);
                mTransit = transit;
                mTransitFlags = getDisplayContent().mAppTransition.getTransitFlags();
            } else {
                resultAdapters = new Pair<>(null, null);
            }
        }
        return resultAdapters;
    }

    protected void applyAnimationUnchecked(WindowManager.LayoutParams lp, boolean enter,
            @TransitionOldType int transit, boolean isVoiceInteraction,
            @Nullable ArrayList<WindowContainer> sources) {
        final Task task = asTask();
        if (task != null && !enter && !task.isActivityTypeHomeOrRecents()) {
            final InsetsControlTarget imeTarget = mDisplayContent.getImeTarget(IME_TARGET_LAYERING);
            final boolean isImeLayeringTarget = imeTarget != null && imeTarget.getWindow() != null
                    && imeTarget.getWindow().getTask() == task;
            // Attach and show the IME screenshot when the task is the IME target and performing
            // task closing transition to the next task.
            if (isImeLayeringTarget && AppTransition.isTaskCloseTransitOld(transit)) {
                mDisplayContent.showImeScreenshot();
            }
        }
        final Pair<AnimationAdapter, AnimationAdapter> adapters = getAnimationAdapter(lp,
                transit, enter, isVoiceInteraction);
        AnimationAdapter adapter = adapters.first;
        AnimationAdapter thumbnailAdapter = adapters.second;
        if (adapter != null) {
            if (sources != null) {
                mSurfaceAnimationSources.addAll(sources);
            }

            AnimationRunnerBuilder animationRunnerBuilder = new AnimationRunnerBuilder();

            // Check if the animation requests to show background color for Activity and embedded
            // TaskFragment.
            final ActivityRecord activityRecord = asActivityRecord();
            final TaskFragment taskFragment = asTaskFragment();
            if (adapter.getShowBackground()
                    // Check if it is Activity transition.
                    && ((activityRecord != null && isActivityTransitOld(transit))
                    // Check if it is embedded TaskFragment transition.
                    || (taskFragment != null && taskFragment.isEmbedded()
                    && isTaskFragmentTransitOld(transit)))) {
                final @ColorInt int backgroundColorForTransition;
                if (adapter.getBackgroundColor() != 0) {
                    // If available use the background color provided through getBackgroundColor
                    // which if set originates from a call to overridePendingAppTransition.
                    backgroundColorForTransition = adapter.getBackgroundColor();
                } else {
                    final TaskFragment organizedTf = activityRecord != null
                            ? activityRecord.getOrganizedTaskFragment()
                            : taskFragment.getOrganizedTaskFragment();
                    if (organizedTf != null && organizedTf.getAnimationParams()
                            .getAnimationBackgroundColor() != DEFAULT_ANIMATION_BACKGROUND_COLOR) {
                        // This window is embedded and has an animation background color set on the
                        // TaskFragment. Pass this color with this window, so the handler can use it
                        // as the animation background color if needed,
                        backgroundColorForTransition = organizedTf.getAnimationParams()
                                .getAnimationBackgroundColor();
                    } else {
                        // Otherwise default to the window's background color if provided through
                        // the theme as the background color for the animation - the top most window
                        // with a valid background color and showBackground set takes precedence.
                        final Task parentTask = activityRecord != null
                                ? activityRecord.getTask()
                                : taskFragment.getTask();
                        backgroundColorForTransition = parentTask.getTaskDescription()
                                .getBackgroundColor();
                    }
                }
                // Set to opaque for animation background to prevent it from exposing the blank
                // background or content below.
                animationRunnerBuilder.setTaskBackgroundColor(ColorUtils.setAlphaComponent(
                        backgroundColorForTransition, 255));
            }

            animationRunnerBuilder.build()
                    .startAnimation(getPendingTransaction(), adapter, !isVisible(),
                            ANIMATION_TYPE_APP_TRANSITION, thumbnailAdapter);

            if (adapter.getShowWallpaper()) {
                getDisplayContent().pendingLayoutChanges |= FINISH_LAYOUT_REDO_WALLPAPER;
            }
        }
    }

    final SurfaceAnimationRunner getSurfaceAnimationRunner() {
        return mWmService.mSurfaceAnimationRunner;
    }

    private Animation loadAnimation(WindowManager.LayoutParams lp, int transit, boolean enter,
                                    boolean isVoiceInteraction) {
        if (AppTransitionController.isTaskViewTask(this) || (isOrganized()
                // TODO(b/161711458): Clean-up when moved to shell.
                && getWindowingMode() != WINDOWING_MODE_FULLSCREEN
                && getWindowingMode() != WINDOWING_MODE_FREEFORM
                && getWindowingMode() != WINDOWING_MODE_MULTI_WINDOW)) {
            return null;
        }

        final DisplayContent displayContent = getDisplayContent();
        final DisplayInfo displayInfo = displayContent.getDisplayInfo();
        final int width = displayInfo.appWidth;
        final int height = displayInfo.appHeight;
        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS_ANIM, "applyAnimation: container=%s", this);

        // Determine the visible rect to calculate the thumbnail clip with
        // getAnimationFrames.
        final Rect frame = new Rect(0, 0, width, height);
        final Rect displayFrame = new Rect(0, 0,
                displayInfo.logicalWidth, displayInfo.logicalHeight);
        final Rect insets = new Rect();
        final Rect stableInsets = new Rect();
        final Rect surfaceInsets = new Rect();
        getAnimationFrames(frame, insets, stableInsets, surfaceInsets);

        if (mLaunchTaskBehind) {
            // Differentiate the two animations. This one which is briefly on the screen
            // gets the !enter animation, and the other one which remains on the
            // screen gets the enter animation. Both appear in the mOpeningApps set.
            enter = false;
        }
        ProtoLog.d(WM_DEBUG_APP_TRANSITIONS,
                "Loading animation for app transition. transit=%s enter=%b frame=%s insets=%s "
                        + "surfaceInsets=%s",
                AppTransition.appTransitionOldToString(transit), enter, frame, insets,
                surfaceInsets);
        final Configuration displayConfig = displayContent.getConfiguration();
        final Animation a = getDisplayContent().mAppTransition.loadAnimation(lp, transit, enter,
                displayConfig.uiMode, displayConfig.orientation, frame, displayFrame, insets,
                surfaceInsets, stableInsets, isVoiceInteraction, inFreeformWindowingMode(), this);
        if (a != null) {
            if (a != null) {
                // Setup the maximum app transition duration to prevent malicious app may set a long
                // animation duration or infinite repeat counts for the app transition through
                // ActivityOption#makeCustomAnimation or WindowManager#overridePendingTransition.
                a.restrictDuration(MAX_APP_TRANSITION_DURATION);
            }
            if (ProtoLog.isEnabled(WM_DEBUG_ANIM, LogLevel.DEBUG)) {
                ProtoLog.i(WM_DEBUG_ANIM, "Loaded animation %s for %s, duration: %d, stack=%s",
                        a, this, ((a != null) ? a.getDuration() : 0), Debug.getCallers(20));
            }
            final int containingWidth = frame.width();
            final int containingHeight = frame.height();
            a.initialize(containingWidth, containingHeight, width, height);
            a.scaleCurrentDuration(mWmService.getTransitionAnimationScaleLocked());
        }
        return a;
    }

    RemoteAnimationTarget createRemoteAnimationTarget(
            RemoteAnimationController.RemoteAnimationRecord record) {
        return null;
    }

    boolean canCreateRemoteAnimationTarget() {
        return false;
    }

    boolean okToDisplay() {
        final DisplayContent dc = getDisplayContent();
        return dc != null && dc.okToDisplay();
    }

    boolean okToAnimate() {
        return okToAnimate(false /* ignoreFrozen */, false /* ignoreScreenOn */);
    }

    boolean okToAnimate(boolean ignoreFrozen, boolean ignoreScreenOn) {
        final DisplayContent dc = getDisplayContent();
        return dc != null && dc.okToAnimate(ignoreFrozen, ignoreScreenOn);
    }

    @Override
    public void commitPendingTransaction() {
        scheduleAnimation();
    }

    void transformFrameToSurfacePosition(int left, int top, Point outPoint) {
        outPoint.set(left, top);
        final WindowContainer parentWindowContainer = getParent();
        if (parentWindowContainer == null) {
            return;
        }
        final Rect parentBounds = parentWindowContainer.getBounds();
        outPoint.offset(-parentBounds.left, -parentBounds.top);
    }

    void reassignLayer(Transaction t) {
        final WindowContainer parent = getParent();
        if (parent != null) {
            parent.assignChildLayers(t);
        }
    }

    void resetSurfacePositionForAnimationLeash(Transaction t) {
        t.setPosition(mSurfaceControl, 0, 0);
        final SurfaceControl.Transaction syncTransaction = getSyncTransaction();
        if (t != syncTransaction) {
            // Avoid restoring to old position if the sync transaction is applied later.
            syncTransaction.setPosition(mSurfaceControl, 0, 0);
        }
        mLastSurfacePosition.set(0, 0);
    }

    @Override
    public void onAnimationLeashCreated(Transaction t, SurfaceControl leash) {
        mLastLayer = -1;
        mAnimationLeash = leash;
        reassignLayer(t);

        // Leash is now responsible for position, so set our position to 0.
        resetSurfacePositionForAnimationLeash(t);
    }

    @Override
    public void onAnimationLeashLost(Transaction t) {
        mLastLayer = -1;
        mWmService.mSurfaceAnimationRunner.onAnimationLeashLost(mAnimationLeash, t);
        mAnimationLeash = null;
        mNeedsZBoost = false;
        reassignLayer(t);
        updateSurfacePosition(t);
    }

    @Override
    public SurfaceControl getAnimationLeash() {
        return mAnimationLeash;
    }

    private void doAnimationFinished(@AnimationType int type, AnimationAdapter anim) {
        for (int i = 0; i < mSurfaceAnimationSources.size(); ++i) {
            mSurfaceAnimationSources.valueAt(i).onAnimationFinished(type, anim);
        }
        mSurfaceAnimationSources.clear();
        if (mDisplayContent != null) {
            mDisplayContent.onWindowAnimationFinished(this, type);
        }
    }

    /**
     * Called when an animation has finished running.
     */
    protected void onAnimationFinished(@AnimationType int type, AnimationAdapter anim) {
        doAnimationFinished(type, anim);
        mWmService.onAnimationFinished();
        mNeedsZBoost = false;
    }

    /**
     * @return The currently running animation, if any, or {@code null} otherwise.
     */
    AnimationAdapter getAnimation() {
        return mSurfaceAnimator.getAnimation();
    }

    /**
     * @return The {@link WindowContainer} which is running an animation.
     *
     * By default this only checks if this container itself is actually running an animation, but
     * you can extend the check target over its relatives, or relax the condition so that this can
     * return {@code WindowContainer} if an animation starts soon by giving a combination
     * of {@link AnimationFlags}.
     *
     * Note that you can give a combination of bitmask flags to specify targets and condition for
     * checking animating status.
     * e.g. {@code isAnimating(TRANSITION | PARENT)} returns {@code true} if either this
     * container itself or one of its parents is running an animation or waiting for an app
     * transition.
     *
     * Note that TRANSITION propagates to parents and children as well.
     *
     * @param flags The combination of bitmask flags to specify targets and condition for
     *              checking animating status.
     * @param typesToCheck The combination of bitmask {@link AnimationType} to compare when
     *                     determining if animating.
     *
     * @see AnimationFlags#TRANSITION
     * @see AnimationFlags#PARENTS
     * @see AnimationFlags#CHILDREN
     */
    @Nullable
    WindowContainer getAnimatingContainer(int flags, int typesToCheck) {
        if (isSelfAnimating(flags, typesToCheck)) {
            return this;
        }
        if ((flags & PARENTS) != 0) {
            WindowContainer parent = getParent();
            while (parent != null) {
                if (parent.isSelfAnimating(flags, typesToCheck)) {
                    return parent;
                }
                parent = parent.getParent();
            }
        }
        if ((flags & CHILDREN) != 0) {
            for (int i = 0; i < mChildren.size(); ++i) {
                final WindowContainer wc = mChildren.get(i).getAnimatingContainer(
                        flags & ~PARENTS, typesToCheck);
                if (wc != null) {
                    return wc;
                }
            }
        }
        return null;
    }

    /**
     * Internal method only to be used during {@link #getAnimatingContainer(int, int)}.DO NOT CALL
     * FROM OUTSIDE.
     */
    protected boolean isSelfAnimating(int flags, int typesToCheck) {
        if (mSurfaceAnimator.isAnimating()
                && (mSurfaceAnimator.getAnimationType() & typesToCheck) > 0) {
            return true;
        }
        if ((flags & TRANSITION) != 0 && isWaitingForTransitionStart()) {
            return true;
        }
        return false;
    }

    /**
     * @deprecated Use {@link #getAnimatingContainer(int, int)} instead.
     */
    @Nullable
    @Deprecated
    final WindowContainer getAnimatingContainer() {
        return getAnimatingContainer(PARENTS, ANIMATION_TYPE_ALL);
    }

    /**
     * @see SurfaceAnimator#startDelayingAnimationStart
     */
    void startDelayingAnimationStart() {
        mSurfaceAnimator.startDelayingAnimationStart();
    }

    /**
     * @see SurfaceAnimator#endDelayingAnimationStart
     */
    void endDelayingAnimationStart() {
        mSurfaceAnimator.endDelayingAnimationStart();
    }

    @Override
    public int getSurfaceWidth() {
        return mSurfaceControl.getWidth();
    }

    @Override
    public int getSurfaceHeight() {
        return mSurfaceControl.getHeight();
    }

    static void enforceSurfaceVisible(@NonNull WindowContainer<?> wc) {
        if (wc.mSurfaceControl == null) {
            return;
        }
        wc.getSyncTransaction().show(wc.mSurfaceControl);
        final ActivityRecord ar = wc.asActivityRecord();
        if (ar != null) {
            ar.mLastSurfaceShowing = true;
        }
        // Force showing the parents because they may be hidden by previous transition.
        for (WindowContainer<?> p = wc.getParent(); p != null && p != wc.mDisplayContent;
                p = p.getParent()) {
            if (p.mSurfaceControl != null) {
                p.getSyncTransaction().show(p.mSurfaceControl);
                final Task task = p.asTask();
                if (task != null) {
                    task.mLastSurfaceShowing = true;
                }
            }
        }
        wc.scheduleAnimation();
    }

    @CallSuper
    void dump(PrintWriter pw, String prefix, boolean dumpAll) {
        if (mSurfaceAnimator.isAnimating()) {
            pw.print(prefix); pw.println("ContainerAnimator:");
            mSurfaceAnimator.dump(pw, prefix + "  ");
        }
        if (mLastOrientationSource != null && this == mDisplayContent) {
            pw.println(prefix + "mLastOrientationSource=" + mLastOrientationSource);
            pw.println(prefix + "deepestLastOrientationSource=" + getLastOrientationSource());
        }
        if (mLocalInsetsSources != null && mLocalInsetsSources.size() != 0) {
            pw.println(prefix + mLocalInsetsSources.size() + " LocalInsetsSources");
            final String childPrefix = prefix + "  ";
            for (int i = 0; i < mLocalInsetsSources.size(); ++i) {
                mLocalInsetsSources.valueAt(i).dump(childPrefix, pw);
            }
        }
    }

    final void updateSurfacePositionNonOrganized() {
        // Avoid fighting with the organizer over Surface position.
        if (isOrganized()) return;
        updateSurfacePosition(getSyncTransaction());
    }

    /**
     * Only for use internally (see PROTECTED annotation). This should only be used over
     * {@link #updateSurfacePositionNonOrganized} when the surface position needs to be
     * updated even if organized (eg. while changing to organized).
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
    void updateSurfacePosition(Transaction t) {
        if (mSurfaceControl == null || mSurfaceAnimator.hasLeash() || mSurfaceFreezer.hasLeash()) {
            return;
        }

        if (isClosingWhenResizing()) {
            // This container is closing while resizing, keep its surface at the starting position
            // to prevent animation flicker.
            getRelativePosition(mDisplayContent.mClosingChangingContainers.get(this), mTmpPos);
        } else {
            getRelativePosition(mTmpPos);
        }
        final int deltaRotation = getRelativeDisplayRotation();
        if (mTmpPos.equals(mLastSurfacePosition) && deltaRotation == mLastDeltaRotation) {
            return;
        }

        t.setPosition(mSurfaceControl, mTmpPos.x, mTmpPos.y);
        // set first, since we don't want rotation included in this (for now).
        mLastSurfacePosition.set(mTmpPos.x, mTmpPos.y);

        if (mTransitionController.isShellTransitionsEnabled()
                && !mTransitionController.useShellTransitionsRotation()) {
            if (deltaRotation != Surface.ROTATION_0) {
                updateSurfaceRotation(t, deltaRotation, null /* positionLeash */);
                getPendingTransaction().setFixedTransformHint(mSurfaceControl,
                        getWindowConfiguration().getDisplayRotation());
            } else if (deltaRotation != mLastDeltaRotation) {
                t.setMatrix(mSurfaceControl, 1, 0, 0, 1);
                getPendingTransaction().unsetFixedTransformHint(mSurfaceControl);
            }
        }
        mLastDeltaRotation = deltaRotation;
    }

    /**
     * Updates the surface transform based on a difference in displayed-rotation from its parent.
     * @param positionLeash If non-null, the rotated position will be set on this surface instead
     *                      of the window surface. {@see WindowToken#getOrCreateFixedRotationLeash}.
     */
    protected void updateSurfaceRotation(Transaction t, @Surface.Rotation int deltaRotation,
            @Nullable SurfaceControl positionLeash) {
        // parent must be non-null otherwise deltaRotation would be 0.
        RotationUtils.rotateSurface(t, mSurfaceControl, deltaRotation);
        mTmpPos.set(mLastSurfacePosition.x, mLastSurfacePosition.y);
        final Rect parentBounds = getParent().getBounds();
        final boolean flipped = (deltaRotation % 2) != 0;
        RotationUtils.rotatePoint(mTmpPos, deltaRotation,
                flipped ? parentBounds.height() : parentBounds.width(),
                flipped ? parentBounds.width() : parentBounds.height());
        t.setPosition(positionLeash != null ? positionLeash : mSurfaceControl,
                mTmpPos.x, mTmpPos.y);
    }

    @VisibleForTesting
    Point getLastSurfacePosition() {
        return mLastSurfacePosition;
    }

    /**
     * The {@code outFrame} retrieved by this method specifies where the animation will finish
     * the entrance animation, as the next frame will display the window at these coordinates. In
     * case of exit animation, this is where the animation will start, as the frame before the
     * animation is displaying the window at these bounds.
     *
     * @param outFrame The bounds where entrance animation finishes or exit animation starts.
     * @param outInsets Insets that are covered by system windows.
     * @param outStableInsets Insets that determine the area covered by the stable system windows.
     * @param outSurfaceInsets Positive insets between the drawing surface and window content.
     */
    void getAnimationFrames(Rect outFrame, Rect outInsets, Rect outStableInsets,
            Rect outSurfaceInsets) {
        final DisplayInfo displayInfo = getDisplayContent().getDisplayInfo();
        outFrame.set(0, 0, displayInfo.appWidth, displayInfo.appHeight);
        outInsets.setEmpty();
        outStableInsets.setEmpty();
        outSurfaceInsets.setEmpty();
    }

    /** Gets the position of this container in its parent's coordinate. */
    void getRelativePosition(Point outPos) {
        getRelativePosition(getBounds(), outPos);
    }

    /** Gets the position of {@code curBounds} in this container's parent's coordinate. */
    void getRelativePosition(Rect curBounds, Point outPos) {
        outPos.set(curBounds.left, curBounds.top);
        final WindowContainer parent = getParent();
        if (parent != null) {
            final Rect parentBounds = parent.getBounds();
            outPos.offset(-parentBounds.left, -parentBounds.top);
        }
    }

    /** @return the difference in displayed-rotation from parent. */
    @Surface.Rotation
    int getRelativeDisplayRotation() {
        final WindowContainer parent = getParent();
        if (parent == null) return Surface.ROTATION_0;
        final int rotation = getWindowConfiguration().getDisplayRotation();
        final int parentRotation = parent.getWindowConfiguration().getDisplayRotation();
        return RotationUtils.deltaRotation(rotation, parentRotation);
    }

    void waitForAllWindowsDrawn() {
        forAllWindows(w -> {
            w.requestDrawIfNeeded(mWaitingForDrawn);
        }, true /* traverseTopToBottom */);
    }

    @Deprecated
    Dimmer getDimmer() {
        if (mParent == null) {
            return null;
        }
        return mParent.getDimmer();
    }

    void setSurfaceControl(SurfaceControl sc) {
        mSurfaceControl = sc;
    }

    RemoteAnimationDefinition getRemoteAnimationDefinition() {
        return null;
    }

    /** Cheap way of doing cast and instanceof. */
    Task asTask() {
        return null;
    }

    /** Cheap way of doing cast and instanceof. */
    TaskFragment asTaskFragment() {
        return null;
    }

    /** Cheap way of doing cast and instanceof. */
    WindowToken asWindowToken() {
        return null;
    }

    /** Cheap way of doing cast and instanceof. */
    WindowState asWindowState() {
        return null;
    }

    /** Cheap way of doing cast and instanceof. */
    ActivityRecord asActivityRecord() {
        return null;
    }

    /** Cheap way of doing cast and instanceof. */
    WallpaperWindowToken asWallpaperToken() {
        return null;
    }

    /** Cheap way of doing cast and instanceof. */
    DisplayArea asDisplayArea() {
        return null;
    }

    /** Cheap way of doing cast and instanceof. */
    RootDisplayArea asRootDisplayArea() {
        return null;
    }

    /** Cheap way of doing cast and instanceof. */
    TaskDisplayArea asTaskDisplayArea() {
        return null;
    }

    /** Cheap way of doing cast and instanceof. */
    DisplayContent asDisplayContent() {
        return null;
    }

    /**
     * @return {@code true} if window container is manage by a
     *          {@link android.window.WindowOrganizer}
     */
    boolean isOrganized() {
        return false;
    }

    /** @return {@code true} if this is a container for embedded activities or tasks. */
    boolean isEmbedded() {
        return false;
    }

    /**
     * @return {@code true} if this container's surface should be shown when it is created.
     */
    boolean showSurfaceOnCreation() {
        return true;
    }

    /** @return {@code true} if the wallpaper is visible behind this container. */
    boolean showWallpaper() {
        if (!isVisibleRequested()
                // in multi-window mode, wallpaper is always visible at the back and not tied to
                // the app (there is no wallpaper target).
                || inMultiWindowMode()) {
            return false;
        }
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            if (child.showWallpaper()) {
                return true;
            }
        }
        return false;
    }


    /** @return {@code true} if this container wants to show wallpaper. */
    boolean hasWallpaper() {
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            if (child.hasWallpaper()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    static WindowContainer fromBinder(IBinder binder) {
        return RemoteToken.fromBinder(binder).getContainer();
    }

    static class RemoteToken extends IWindowContainerToken.Stub {

        final WeakReference<WindowContainer> mWeakRef;
        private WindowContainerToken mWindowContainerToken;

        RemoteToken(WindowContainer container) {
            mWeakRef = new WeakReference<>(container);
        }

        @Nullable
        WindowContainer getContainer() {
            return mWeakRef.get();
        }

        static RemoteToken fromBinder(IBinder binder) {
            return (RemoteToken) binder;
        }

        WindowContainerToken toWindowContainerToken() {
            if (mWindowContainerToken == null) {
                mWindowContainerToken = new WindowContainerToken(this);
            }
            return mWindowContainerToken;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("RemoteToken{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            sb.append(mWeakRef.get());
            sb.append('}');
            return sb.toString();
        }
    }

    /**
     * Call this when this container finishes drawing content.
     *
     * @return {@code true} if consumed (this container is part of a sync group).
     */
    boolean onSyncFinishedDrawing() {
        if (mSyncState == SYNC_STATE_NONE) return false;
        mSyncState = SYNC_STATE_READY;
        mSyncMethodOverride = BLASTSyncEngine.METHOD_UNDEFINED;
        ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "onSyncFinishedDrawing %s", this);
        return true;
    }

    void setSyncGroup(@NonNull BLASTSyncEngine.SyncGroup group) {
        ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "setSyncGroup #%d on %s", group.mSyncId, this);
        if (mSyncGroup != null && mSyncGroup != group) {
            // This can still happen if WMCore starts a new transition when there is ongoing
            // sync transaction from Shell. Please file a bug if it happens.
            throw new IllegalStateException("Can't sync on 2 groups simultaneously"
                    + " currentSyncId=" + mSyncGroup.mSyncId + " newSyncId=" + group.mSyncId
                    + " wc=" + this);
        }
        mSyncGroup = group;
    }

    @Nullable
    BLASTSyncEngine.SyncGroup getSyncGroup() {
        if (mSyncGroup != null) return mSyncGroup;
        WindowContainer<?> parent = mParent;
        while (parent != null) {
            if (parent.mSyncGroup != null) {
                return parent.mSyncGroup;
            }
            parent = parent.mParent;
        }
        return null;
    }

    /**
     * Prepares this container for participation in a sync-group. This includes preparing all its
     * children.
     *
     * @return {@code true} if something changed (eg. this wasn't already in the sync group).
     */
    boolean prepareSync() {
        if (mSyncState != SYNC_STATE_NONE) {
            // Already part of sync
            return false;
        }
        for (int i = getChildCount() - 1; i >= 0; --i) {
            final WindowContainer child = getChildAt(i);
            child.prepareSync();
        }
        mSyncState = SYNC_STATE_READY;
        return true;
    }

    boolean syncNextBuffer() {
        return mSyncState != SYNC_STATE_NONE;
    }

    /**
     * Returns {@code true} if this window container belongs to a different sync group than the
     * given group.
     */
    boolean isDifferentSyncGroup(@Nullable BLASTSyncEngine.SyncGroup group) {
        if (group == null) return false;
        final BLASTSyncEngine.SyncGroup thisGroup = getSyncGroup();
        if (thisGroup == null || group == thisGroup) return false;
        Slog.d(TAG, this + " uses a different SyncGroup, current=" + thisGroup.mSyncId
                + " given=" + group.mSyncId);
        return true;
    }

    /**
     * Recursively finishes/cleans-up sync state of this subtree and collects all the sync
     * transactions into `outMergedTransaction`.
     * @param outMergedTransaction A transaction to merge all the recorded sync operations into.
     * @param cancel If true, this is being finished because it is leaving the sync group rather
     *               than due to the sync group completing.
     */
    void finishSync(Transaction outMergedTransaction, @Nullable BLASTSyncEngine.SyncGroup group,
            boolean cancel) {
        if (mSyncState == SYNC_STATE_NONE) {
            if (mSyncGroup != null) {
                Slog.e(TAG, "finishSync: stale group " + mSyncGroup.mSyncId + " of " + this);
                mSyncGroup = null;
            }
            return;
        }
        if (isDifferentSyncGroup(group)) return;
        ProtoLog.v(WM_DEBUG_SYNC_ENGINE, "finishSync cancel=%b for %s", cancel, this);
        outMergedTransaction.merge(mSyncTransaction);
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).finishSync(outMergedTransaction, group, cancel);
        }
        if (cancel && mSyncGroup != null) mSyncGroup.onCancelSync(this);
        mSyncState = SYNC_STATE_NONE;
        mSyncMethodOverride = BLASTSyncEngine.METHOD_UNDEFINED;
        mSyncGroup = null;
    }

    /**
     * Checks if the subtree rooted at this container is finished syncing (everything is ready or
     * not visible). NOTE, this is not const: it may cancel/prepare/complete itself depending on
     * its state in the hierarchy.
     *
     * @return {@code true} if this subtree is finished waiting for sync participants.
     */
    boolean isSyncFinished(BLASTSyncEngine.SyncGroup group) {
        if (!isVisibleRequested()) {
            return true;
        }
        if (mSyncState == SYNC_STATE_NONE && getSyncGroup() != null) {
            Slog.i(TAG, "prepareSync in isSyncFinished: " + this);
            prepareSync();
        }
        if (mSyncState == SYNC_STATE_WAITING_FOR_DRAW) {
            return false;
        }
        // READY
        // Loop from top-down.
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            final boolean childFinished = group.isIgnoring(child) || child.isSyncFinished(group);
            if (childFinished && child.isVisibleRequested() && child.fillsParent()) {
                // Any lower children will be covered-up, so we can consider this finished.
                return true;
            }
            if (!childFinished) {
                return false;
            }
        }
        return true;
    }

    /**
     * Special helper to check that all windows are synced (vs just top one). This is only
     * used to differentiate between starting-window vs full-drawn in activity-metrics reporting.
     */
    boolean allSyncFinished() {
        if (!isVisibleRequested()) return true;
        if (mSyncState != SYNC_STATE_READY) return false;
        for (int i = mChildren.size() - 1; i >= 0; --i) {
            final WindowContainer child = mChildren.get(i);
            if (!child.allSyncFinished()) return false;
        }
        return true;
    }

    /**
     * Called during reparent to handle sync state when the hierarchy changes.
     * If this is in a sync group and gets reparented out, it will cancel syncing.
     * If this is not in a sync group and gets parented into one, it will prepare itself.
     * If its moving around within a sync-group, it needs to restart its syncing since a
     * hierarchy change implies a configuration change.
     */
    private void onSyncReparent(WindowContainer oldParent, WindowContainer newParent) {
        // Check if this is changing displays. If so, mark the old display as "ready" for
        // transitions. This is to work around the problem where setting readiness against this
        // container will only set the new display as ready and leave the old display as unready.
        if (mSyncState != SYNC_STATE_NONE && oldParent != null && newParent != null
                && oldParent.getDisplayContent() != null && newParent.getDisplayContent() != null
                && oldParent.getDisplayContent() != newParent.getDisplayContent()) {
            mTransitionController.setReady(oldParent.getDisplayContent());
        }

        if (newParent == null || newParent.mSyncState == SYNC_STATE_NONE) {
            if (mSyncState == SYNC_STATE_NONE) {
                return;
            }
            if (newParent == null) {
                // This is getting removed.
                final BLASTSyncEngine.SyncGroup syncGroup = getSyncGroup();
                if (oldParent.mSyncState != SYNC_STATE_NONE) {
                    // In order to keep the transaction in sync, merge it into the parent.
                    finishSync(oldParent.mSyncTransaction, syncGroup, true /* cancel */);
                } else if (syncGroup != null) {
                    // This is watched by the sync-group, so merge this transaction into the
                    // sync-group for not losing the operations in the transaction.
                    finishSync(syncGroup.getOrphanTransaction(), syncGroup, true /* cancel */);
                } else {
                    Slog.wtf(TAG, this + " is in sync mode without a sync group");
                    // Make sure the removal transaction take effect.
                    finishSync(getPendingTransaction(), null /* group */, true /* cancel */);
                }
                return;
            } else if (mSyncGroup == null) {
                // This is being reparented out of the sync-group. To prevent ordering issues on
                // this container, immediately apply/cancel sync on it.
                finishSync(getPendingTransaction(), getSyncGroup(), true /* cancel */);
                return;
            }
            // Otherwise this is the "root" of a synced subtree, so continue on to preparation.
        }
        if (oldParent != null && newParent != null && !shouldUpdateSyncOnReparent()) {
            return;
        }

        // This container's situation has changed so we need to restart its sync.
        // We cannot reset the sync without a chance of a deadlock since it will request a new
        // buffer from the app process. This could cause issues if the app has run out of buffers
        // since the previous buffer was already synced and is still held in a transaction.
        // Resetting syncState violates the policies outlined in BlastSyncEngine.md so for now
        // disable this when shell transitions is disabled.
        if (mTransitionController.isShellTransitionsEnabled()) {
            mSyncState = SYNC_STATE_NONE;
            mSyncMethodOverride = BLASTSyncEngine.METHOD_UNDEFINED;
        }
        prepareSync();
    }

    /** Returns {@code true} if {@link #mSyncState} needs to be updated when reparenting. */
    protected boolean shouldUpdateSyncOnReparent() {
        return true;
    }

    void registerWindowContainerListener(WindowContainerListener listener) {
        registerWindowContainerListener(listener, true /* shouldPropConfig */);
    }

    void registerWindowContainerListener(WindowContainerListener listener,
            boolean shouldDispatchConfig) {
        if (mListeners.contains(listener)) {
            return;
        }
        mListeners.add(listener);
        // Also register to ConfigurationChangeListener to receive configuration changes.
        registerConfigurationChangeListener(listener, shouldDispatchConfig);
        if (shouldDispatchConfig) {
            listener.onDisplayChanged(getDisplayContent());
        }
    }

    void unregisterWindowContainerListener(WindowContainerListener listener) {
        mListeners.remove(listener);
        unregisterConfigurationChangeListener(listener);
    }

    static void overrideConfigurationPropagation(WindowContainer<?> receiver,
            WindowContainer<?> supplier) {
        overrideConfigurationPropagation(receiver, supplier, null /* configurationMerger */);
    }

    /**
     * Forces the receiver container to always use the configuration of the supplier container as
     * its requested override configuration. It allows to propagate configuration without changing
     * the relationship between child and parent.
     *
     * @param receiver            The {@link WindowContainer<?>} which will receive the {@link
     *                            Configuration} result of the merging operation.
     * @param supplier            The {@link WindowContainer<?>} which provides the initial {@link
     *                            Configuration}.
     * @param configurationMerger A {@link ConfigurationMerger} which combines the {@link
     *                            Configuration} of the receiver and the supplier.
     */
    static WindowContainerListener overrideConfigurationPropagation(WindowContainer<?> receiver,
            WindowContainer<?> supplier, @Nullable ConfigurationMerger configurationMerger) {
        final ConfigurationContainerListener listener = new ConfigurationContainerListener() {
            @Override
            public void onMergedOverrideConfigurationChanged(Configuration mergedOverrideConfig) {
                final Configuration mergedConfiguration =
                        configurationMerger != null
                                ? configurationMerger.merge(mergedOverrideConfig,
                                        receiver.getRequestedOverrideConfiguration())
                                : supplier.getConfiguration();
                receiver.onRequestedOverrideConfigurationChanged(mergedConfiguration);
            }
        };
        supplier.registerConfigurationChangeListener(listener);
        final WindowContainerListener wcListener = new WindowContainerListener() {
            @Override
            public void onRemoved() {
                receiver.unregisterWindowContainerListener(this);
                supplier.unregisterConfigurationChangeListener(listener);
            }
        };
        receiver.registerWindowContainerListener(wcListener);
        return wcListener;
    }

    /**
     * Abstraction for functions merging two {@link Configuration} objects into one.
     */
    @FunctionalInterface
    interface ConfigurationMerger {
        Configuration merge(Configuration first, Configuration second);
    }

    /**
     * Returns the {@link WindowManager.LayoutParams.WindowType}.
     */
    @WindowManager.LayoutParams.WindowType int getWindowType() {
        return INVALID_WINDOW_TYPE;
    }

    boolean setCanScreenshot(Transaction t, boolean canScreenshot) {
        if (mSurfaceControl == null) {
            return false;
        }
        t.setSecure(mSurfaceControl, !canScreenshot);
        return true;
    }

    private class AnimationRunnerBuilder {
        /**
         * Runs when the surface stops animating
         */
        private final List<Runnable> mOnAnimationFinished = new LinkedList<>();
        /**
         * Runs when the animation is cancelled but the surface is still animating
         */
        private final List<Runnable> mOnAnimationCancelled = new LinkedList<>();

        private void setTaskBackgroundColor(@ColorInt int backgroundColor) {
            TaskDisplayArea taskDisplayArea = getTaskDisplayArea();

            if (taskDisplayArea != null && backgroundColor != Color.TRANSPARENT) {
                taskDisplayArea.setBackgroundColor(backgroundColor);

                // Atomic counter to make sure the clearColor callback is only called one.
                // It will be called twice in the case we cancel the animation without restart
                // (in that case it will run as the cancel and finished callbacks).
                final AtomicInteger callbackCounter = new AtomicInteger(0);
                final Runnable clearBackgroundColorHandler = () -> {
                    if (callbackCounter.getAndIncrement() == 0) {
                        taskDisplayArea.clearBackgroundColor();
                    }
                };

                // We want to make sure this is called both when the surface stops animating and
                // also when an animation is cancelled (i.e. animation is replaced by another
                // animation but and so the surface is still animating)
                mOnAnimationFinished.add(clearBackgroundColorHandler);
                mOnAnimationCancelled.add(clearBackgroundColorHandler);
            }
        }

        private IAnimationStarter build() {
            return (Transaction t, AnimationAdapter adapter, boolean hidden,
                    @AnimationType int type, @Nullable AnimationAdapter snapshotAnim) -> {
                startAnimation(getPendingTransaction(), adapter, !isVisible(), type,
                        (animType, anim) -> mOnAnimationFinished.forEach(Runnable::run),
                        () -> mOnAnimationCancelled.forEach(Runnable::run), snapshotAnim);
            };
        }
    }

    private interface IAnimationStarter {
        void startAnimation(Transaction t, AnimationAdapter anim, boolean hidden,
                @AnimationType int type, @Nullable AnimationAdapter snapshotAnim);
    }

    void addTrustedOverlay(SurfaceControlViewHost.SurfacePackage overlay,
            @Nullable WindowState initialWindowState) {
        if (mOverlayHost == null) {
            mOverlayHost = new TrustedOverlayHost(mWmService);
        }
        mOverlayHost.addOverlay(overlay, mSurfaceControl);

        // Emit an initial onConfigurationChanged to ensure the overlay
        // can receive any changes between their creation time and
        // attach time.
        try {
            overlay.getRemoteInterface().onConfigurationChanged(getConfiguration());
        } catch (Exception e) {
            ProtoLog.e(WM_DEBUG_ANIM,
                    "Error sending initial configuration change to WindowContainer overlay");
            removeTrustedOverlay(overlay);
        }

        // Emit an initial WindowState so that proper insets are available to overlay views
        // shortly after the overlay is added.
        if (initialWindowState != null) {
            final InsetsState insetsState = initialWindowState.getInsetsState();
            final Rect dispBounds = getBounds();
            try {
                overlay.getRemoteInterface().onInsetsChanged(insetsState, dispBounds);
            } catch (Exception e) {
                ProtoLog.e(WM_DEBUG_ANIM,
                        "Error sending initial insets change to WindowContainer overlay");
                removeTrustedOverlay(overlay);
            }
        }
    }

    void removeTrustedOverlay(SurfaceControlViewHost.SurfacePackage overlay) {
        if (mOverlayHost != null && !mOverlayHost.removeOverlay(overlay)) {
            mOverlayHost.release();
            mOverlayHost = null;
        }
    }

    void updateOverlayInsetsState(WindowState originalChange) {
        final WindowContainer p = getParent();
        if (p != null) {
            p.updateOverlayInsetsState(originalChange);
        }
    }

    void waitForSyncTransactionCommit(ArraySet<WindowContainer> wcAwaitingCommit) {
        if (wcAwaitingCommit.contains(this)) {
            return;
        }
        mSyncTransactionCommitCallbackDepth++;
        wcAwaitingCommit.add(this);

        for (int i = mChildren.size() - 1; i >= 0; --i) {
            mChildren.get(i).waitForSyncTransactionCommit(wcAwaitingCommit);
        }
    }

    void onSyncTransactionCommitted(SurfaceControl.Transaction t) {
        mSyncTransactionCommitCallbackDepth--;
        if (mSyncTransactionCommitCallbackDepth > 0) {
            return;
        }
        if (mSyncState != SYNC_STATE_NONE) {
            return;
        }

        t.merge(mSyncTransaction);
    }

    int getSyncTransactionCommitCallbackDepth() {
        return mSyncTransactionCommitCallbackDepth;
    }
}
