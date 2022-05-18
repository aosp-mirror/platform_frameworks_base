/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.tileimpl;

import static androidx.lifecycle.Lifecycle.State.CREATED;
import static androidx.lifecycle.Lifecycle.State.DESTROYED;
import static androidx.lifecycle.Lifecycle.State.RESUMED;
import static androidx.lifecycle.Lifecycle.State.STARTED;

import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_QS_CLICK;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_QS_LONG_PRESS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.ACTION_QS_SECONDARY_CLICK;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_IS_FULL_QS;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_QS_POSITION;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_QS_VALUE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.FIELD_STATUS_BAR_STATE;
import static com.android.internal.logging.nano.MetricsProto.MetricsEvent.TYPE_ACTION;
import static com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.InstanceId;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.RestrictedLockUtils;
import com.android.settingslib.RestrictedLockUtilsInternal;
import com.android.systemui.Dumpable;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSEvent;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.SideLabelTileLayout;
import com.android.systemui.qs.logging.QSLogger;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Base quick-settings tile, extend this to create a new tile.
 *
 * State management done on a looper provided by the host.  Tiles should update state in
 * handleUpdateState.  Callbacks affecting state should use refreshState to trigger another
 * state update pass on tile looper.
 *
 * @param <TState> see above
 */
public abstract class QSTileImpl<TState extends State> implements QSTile, LifecycleOwner, Dumpable {
    protected final String TAG = "Tile." + getClass().getSimpleName();
    protected static final boolean DEBUG = Log.isLoggable("Tile", Log.DEBUG);

    private static final long DEFAULT_STALE_TIMEOUT = 10 * DateUtils.MINUTE_IN_MILLIS;
    protected static final Object ARG_SHOW_TRANSIENT_ENABLING = new Object();

    private static final int READY_STATE_NOT_READY = 0;
    private static final int READY_STATE_READYING = 1;
    private static final int READY_STATE_READY = 2;

    protected final QSHost mHost;
    protected final Context mContext;
    // @NonFinalForTesting
    protected final H mHandler;
    protected final Handler mUiHandler;
    private final ArraySet<Object> mListeners = new ArraySet<>();
    private final MetricsLogger mMetricsLogger;
    private final StatusBarStateController mStatusBarStateController;
    protected final ActivityStarter mActivityStarter;
    private final UiEventLogger mUiEventLogger;
    private final FalsingManager mFalsingManager;
    protected final QSLogger mQSLogger;
    private volatile int mReadyState;

    private final ArrayList<Callback> mCallbacks = new ArrayList<>();
    private final Object mStaleListener = new Object();
    protected TState mState;
    private TState mTmpState;
    private final InstanceId mInstanceId;
    private boolean mAnnounceNextStateChange;

    private String mTileSpec;
    @Nullable
    @VisibleForTesting
    protected EnforcedAdmin mEnforcedAdmin;
    private boolean mShowingDetail;
    private int mIsFullQs;

    private final LifecycleRegistry mLifecycle = new LifecycleRegistry(this);

    /**
     * Provides a new {@link TState} of the appropriate type to use between this tile and the
     * corresponding view.
     *
     * @return new state to use by the tile.
     */
    public abstract TState newTileState();

    /**
     * Handles clicks by the user.
     *
     * Calls to the controller should be made here to set the new state of the device.
     *
     * @param view The view that was clicked.
     */
    protected abstract void handleClick(@Nullable View view);

    /**
     * Update state of the tile based on device state
     *
     * Called whenever the state of the tile needs to be updated, either after user
     * interaction or from callbacks from the controller. It populates {@code state} with the
     * information to display to the user.
     *
     * @param state {@link TState} to populate with information to display
     * @param arg additional arguments needed to populate {@code state}
     */
    abstract protected void handleUpdateState(TState state, Object arg);

    /**
     * Declare the category of this tile.
     *
     * Categories are defined in {@link com.android.internal.logging.nano.MetricsProto.MetricsEvent}
     * by editing frameworks/base/proto/src/metrics_constants.proto.
     *
     * @deprecated Not needed as this logging is deprecated. Logging tiles is done using
     * {@link QSTile#getMetricsSpec}
     */
    @Deprecated
    public int getMetricsCategory() {
        return 0;
    }

    /**
     * Performs initialization of the tile
     *
     * Use this to perform initialization of the tile. Empty by default.
     */
    protected void handleInitialize() {

    }

    protected QSTileImpl(
            QSHost host,
            Looper backgroundLooper,
            Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger
    ) {
        mHost = host;
        mContext = host.getContext();
        mInstanceId = host.getNewInstanceId();
        mUiEventLogger = host.getUiEventLogger();

        mUiHandler = mainHandler;
        mHandler = new H(backgroundLooper);
        mFalsingManager = falsingManager;
        mQSLogger = qsLogger;
        mMetricsLogger = metricsLogger;
        mStatusBarStateController = statusBarStateController;
        mActivityStarter = activityStarter;

        resetStates();
        mUiHandler.post(() -> mLifecycle.setCurrentState(CREATED));
    }

    protected final void resetStates() {
        mState = newTileState();
        mTmpState = newTileState();
        mState.spec = mTileSpec;
        mTmpState.spec = mTileSpec;
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @Override
    public InstanceId getInstanceId() {
        return mInstanceId;
    }

    /**
     * Adds or removes a listening client for the tile. If the tile has one or more
     * listening client it will go into the listening state.
     */
    public void setListening(Object listener, boolean listening) {
        mHandler.obtainMessage(H.SET_LISTENING, listening ? 1 : 0, 0, listener).sendToTarget();
    }

    protected long getStaleTimeout() {
        return DEFAULT_STALE_TIMEOUT;
    }

    @VisibleForTesting
    protected void handleStale() {
        if (!mListeners.isEmpty()) {
            // If the tile is already listening (it's been a long time since it refreshed), just
            // force a refresh. Don't add the staleListener because there's already a listener there
            refreshState();
        } else {
            setListening(mStaleListener, true);
        }
    }

    public String getTileSpec() {
        return mTileSpec;
    }

    public void setTileSpec(String tileSpec) {
        mTileSpec = tileSpec;
        mState.spec = tileSpec;
        mTmpState.spec = tileSpec;
    }

    public QSHost getHost() {
        return mHost;
    }

    /**
     * Return the {@link QSIconView} to be used by this tile's view.
     *
     * @param context view context for the view
     * @return icon view for this tile
     */
    public QSIconView createTileView(Context context) {
        return new QSIconViewImpl(context);
    }

    /**
     * Is a startup check whether this device currently supports this tile.
     * Should not be used to conditionally hide tiles.  Only checked on tile
     * creation or whether should be shown in edit screen.
     */
    public boolean isAvailable() {
        return true;
    }

    // safe to call from any thread

    public void addCallback(Callback callback) {
        mHandler.obtainMessage(H.ADD_CALLBACK, callback).sendToTarget();
    }

    public void removeCallback(Callback callback) {
        mHandler.obtainMessage(H.REMOVE_CALLBACK, callback).sendToTarget();
    }

    public void removeCallbacks() {
        mHandler.sendEmptyMessage(H.REMOVE_CALLBACKS);
    }

    public void click(@Nullable View view) {
        mMetricsLogger.write(populate(new LogMaker(ACTION_QS_CLICK).setType(TYPE_ACTION)
                .addTaggedData(FIELD_STATUS_BAR_STATE,
                        mStatusBarStateController.getState())));
        mUiEventLogger.logWithInstanceId(QSEvent.QS_ACTION_CLICK, 0, getMetricsSpec(),
                getInstanceId());
        mQSLogger.logTileClick(mTileSpec, mStatusBarStateController.getState(), mState.state);
        if (!mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            mHandler.obtainMessage(H.CLICK, view).sendToTarget();
        }
    }

    public void secondaryClick(@Nullable View view) {
        mMetricsLogger.write(populate(new LogMaker(ACTION_QS_SECONDARY_CLICK).setType(TYPE_ACTION)
                .addTaggedData(FIELD_STATUS_BAR_STATE,
                        mStatusBarStateController.getState())));
        mUiEventLogger.logWithInstanceId(QSEvent.QS_ACTION_SECONDARY_CLICK, 0, getMetricsSpec(),
                getInstanceId());
        mQSLogger.logTileSecondaryClick(mTileSpec, mStatusBarStateController.getState(),
                mState.state);
        mHandler.obtainMessage(H.SECONDARY_CLICK, view).sendToTarget();
    }

    @Override
    public void longClick(@Nullable View view) {
        mMetricsLogger.write(populate(new LogMaker(ACTION_QS_LONG_PRESS).setType(TYPE_ACTION)
                .addTaggedData(FIELD_STATUS_BAR_STATE,
                        mStatusBarStateController.getState())));
        mUiEventLogger.logWithInstanceId(QSEvent.QS_ACTION_LONG_PRESS, 0, getMetricsSpec(),
                getInstanceId());
        mQSLogger.logTileLongClick(mTileSpec, mStatusBarStateController.getState(), mState.state);
        mHandler.obtainMessage(H.LONG_CLICK, view).sendToTarget();
    }

    public LogMaker populate(LogMaker logMaker) {
        if (mState instanceof BooleanState) {
            logMaker.addTaggedData(FIELD_QS_VALUE, ((BooleanState) mState).value ? 1 : 0);
        }
        return logMaker.setSubtype(getMetricsCategory())
                .addTaggedData(FIELD_IS_FULL_QS, mIsFullQs)
                .addTaggedData(FIELD_QS_POSITION, mHost.indexOf(mTileSpec));
    }

    public void refreshState() {
        refreshState(null);
    }

    @Override
    public final boolean isListening() {
        return getLifecycle().getCurrentState().isAtLeast(RESUMED);
    }

    protected final void refreshState(@Nullable Object arg) {
        mHandler.obtainMessage(H.REFRESH_STATE, arg).sendToTarget();
    }

    public void userSwitch(int newUserId) {
        mHandler.obtainMessage(H.USER_SWITCH, newUserId, 0).sendToTarget();
    }

    public void destroy() {
        mHandler.sendEmptyMessage(H.DESTROY);
    }

    /**
     * Schedules initialization of the tile.
     *
     * Should be called upon creation of the tile, before performing other operations
     */
    public void initialize() {
        mHandler.sendEmptyMessage(H.INITIALIZE);
    }

    public TState getState() {
        return mState;
    }

    public void setDetailListening(boolean listening) {
        // optional
    }

    // call only on tile worker looper

    private void handleAddCallback(Callback callback) {
        mCallbacks.add(callback);
        callback.onStateChanged(mState);
    }

    private void handleRemoveCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    private void handleRemoveCallbacks() {
        mCallbacks.clear();
    }

    /**
     * Posts a stale message to the background thread.
     */
    public void postStale() {
        mHandler.sendEmptyMessage(H.STALE);
    }

    /**
     * Handles secondary click on the tile.
     *
     * Defaults to {@link QSTileImpl#handleClick}
     *
     * @param view The view that was clicked.
     */
    protected void handleSecondaryClick(@Nullable View view) {
        // Default to normal click.
        handleClick(view);
    }

    /**
     * Handles long click on the tile by launching the {@link Intent} defined in
     * {@link QSTileImpl#getLongClickIntent}.
     *
     * @param view The view from which the opening window will be animated.
     */
    protected void handleLongClick(@Nullable View view) {
        ActivityLaunchAnimator.Controller animationController =
                view != null ? ActivityLaunchAnimator.Controller.fromView(view,
                        InteractionJankMonitor.CUJ_SHADE_APP_LAUNCH_FROM_QS_TILE) : null;
        mActivityStarter.postStartActivityDismissingKeyguard(getLongClickIntent(), 0,
                animationController);
    }

    /**
     * Returns an intent to be launched when the tile is long pressed.
     *
     * @return the intent to launch
     */
    @Nullable
    public abstract Intent getLongClickIntent();

    protected final void handleRefreshState(@Nullable Object arg) {
        handleUpdateState(mTmpState, arg);
        boolean changed = mTmpState.copyTo(mState);
        if (mReadyState == READY_STATE_READYING) {
            mReadyState = READY_STATE_READY;
            changed = true;
        }
        if (changed) {
            mQSLogger.logTileUpdated(mTileSpec, mState);
            handleStateChanged();
        }
        mHandler.removeMessages(H.STALE);
        mHandler.sendEmptyMessageDelayed(H.STALE, getStaleTimeout());
        setListening(mStaleListener, false);
    }

    private void handleStateChanged() {
        if (mCallbacks.size() != 0) {
            for (int i = 0; i < mCallbacks.size(); i++) {
                mCallbacks.get(i).onStateChanged(mState);
            }
        }
    }

    protected void handleUserSwitch(int newUserId) {
        handleRefreshState(null);
    }

    private void handleSetListeningInternal(Object listener, boolean listening) {
        // This should be used to go from resumed to paused. Listening for ON_RESUME and ON_PAUSE
        // in this lifecycle will determine the listening window.
        if (listening) {
            if (mListeners.add(listener) && mListeners.size() == 1) {
                if (DEBUG) Log.d(TAG, "handleSetListening true");
                handleSetListening(listening);
                mUiHandler.post(() -> {
                    // This tile has been destroyed, the state should not change anymore and we
                    // should not refresh it anymore.
                    if (mLifecycle.getCurrentState().equals(DESTROYED)) return;
                    mLifecycle.setCurrentState(RESUMED);
                    if (mReadyState == READY_STATE_NOT_READY) {
                        mReadyState = READY_STATE_READYING;
                    }
                    refreshState(); // Ensure we get at least one refresh after listening.
                });
            }
        } else {
            if (mListeners.remove(listener) && mListeners.size() == 0) {
                if (DEBUG) Log.d(TAG, "handleSetListening false");
                handleSetListening(listening);
                mUiHandler.post(() -> {
                    // This tile has been destroyed, the state should not change anymore.
                    if (mLifecycle.getCurrentState().equals(DESTROYED)) return;
                    mLifecycle.setCurrentState(STARTED);
                });
            }
        }
        updateIsFullQs();
    }

    private void updateIsFullQs() {
        for (Object listener : mListeners) {
            if (SideLabelTileLayout.class.equals(listener.getClass())) {
                mIsFullQs = 1;
                return;
            }
        }
        mIsFullQs = 0;
    }

    @CallSuper
    protected void handleSetListening(boolean listening) {
        if (mTileSpec != null) {
            mQSLogger.logTileChangeListening(mTileSpec, listening);
        }
    }

    protected void handleDestroy() {
        mQSLogger.logTileDestroyed(mTileSpec, "Handle destroy");
        if (mListeners.size() != 0) {
            handleSetListening(false);
            mListeners.clear();
        }
        mCallbacks.clear();
        mHandler.removeCallbacksAndMessages(null);
        // This will force it to be removed from all controllers that may have it registered.
        mUiHandler.post(() -> {
            mLifecycle.setCurrentState(DESTROYED);
        });
    }

    protected void checkIfRestrictionEnforcedByAdminOnly(State state, String userRestriction) {
        EnforcedAdmin admin = RestrictedLockUtilsInternal.checkIfRestrictionEnforced(mContext,
                userRestriction, mHost.getUserId());
        if (admin != null && !RestrictedLockUtilsInternal.hasBaseUserRestriction(mContext,
                userRestriction, mHost.getUserId())) {
            state.disabledByPolicy = true;
            mEnforcedAdmin = admin;
        } else {
            state.disabledByPolicy = false;
            mEnforcedAdmin = null;
        }
    }

    @Override
    public String getMetricsSpec() {
        return mTileSpec;
    }

    /**
     * Provides a default label for the tile.
     * @return default label for the tile.
     */
    public abstract CharSequence getTileLabel();

    /**
     * @return {@code true} if the tile has refreshed state at least once after having set its
     *         lifecycle to {@link Lifecycle.State#RESUMED}.
     */
    @Override
    public boolean isTileReady() {
        return mReadyState == READY_STATE_READY;
    }

    protected final class H extends Handler {
        private static final int ADD_CALLBACK = 1;
        private static final int CLICK = 2;
        private static final int SECONDARY_CLICK = 3;
        private static final int LONG_CLICK = 4;
        private static final int REFRESH_STATE = 5;
        private static final int USER_SWITCH = 6;
        private static final int DESTROY = 7;
        private static final int REMOVE_CALLBACKS = 8;
        private static final int REMOVE_CALLBACK = 9;
        private static final int SET_LISTENING = 10;
        @VisibleForTesting
        protected static final int STALE = 11;
        private static final int INITIALIZE = 12;

        @VisibleForTesting
        protected H(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            String name = null;
            try {
                if (msg.what == ADD_CALLBACK) {
                    name = "handleAddCallback";
                    handleAddCallback((QSTile.Callback) msg.obj);
                } else if (msg.what == REMOVE_CALLBACKS) {
                    name = "handleRemoveCallbacks";
                    handleRemoveCallbacks();
                } else if (msg.what == REMOVE_CALLBACK) {
                    name = "handleRemoveCallback";
                    handleRemoveCallback((QSTile.Callback) msg.obj);
                } else if (msg.what == CLICK) {
                    name = "handleClick";
                    if (mState.disabledByPolicy) {
                        Intent intent = RestrictedLockUtils.getShowAdminSupportDetailsIntent(
                                mContext, mEnforcedAdmin);
                        mActivityStarter.postStartActivityDismissingKeyguard(intent, 0);
                    } else {
                        handleClick((View) msg.obj);
                    }
                } else if (msg.what == SECONDARY_CLICK) {
                    name = "handleSecondaryClick";
                    handleSecondaryClick((View) msg.obj);
                } else if (msg.what == LONG_CLICK) {
                    name = "handleLongClick";
                    handleLongClick((View) msg.obj);
                } else if (msg.what == REFRESH_STATE) {
                    name = "handleRefreshState";
                    handleRefreshState(msg.obj);
                } else if (msg.what == USER_SWITCH) {
                    name = "handleUserSwitch";
                    handleUserSwitch(msg.arg1);
                } else if (msg.what == DESTROY) {
                    name = "handleDestroy";
                    handleDestroy();
                } else if (msg.what == SET_LISTENING) {
                    name = "handleSetListeningInternal";
                    handleSetListeningInternal(msg.obj, msg.arg1 != 0);
                } else if (msg.what == STALE) {
                    name = "handleStale";
                    handleStale();
                } else if (msg.what == INITIALIZE) {
                    name = "initialize";
                    handleInitialize();
                } else {
                    throw new IllegalArgumentException("Unknown msg: " + msg.what);
                }
            } catch (Throwable t) {
                final String error = "Error in " + name;
                Log.w(TAG, error, t);
                mHost.warn(error, t);
            }
        }
    }

    public static class DrawableIcon extends Icon {
        protected final Drawable mDrawable;
        protected final Drawable mInvisibleDrawable;

        public DrawableIcon(Drawable drawable) {
            mDrawable = drawable;
            mInvisibleDrawable = drawable.getConstantState().newDrawable();
        }

        @Override
        public Drawable getDrawable(Context context) {
            return mDrawable;
        }

        @Override
        public Drawable getInvisibleDrawable(Context context) {
            return mInvisibleDrawable;
        }

        @Override
        @NonNull
        public String toString() {
            return "DrawableIcon";
        }
    }

    public static class DrawableIconWithRes extends DrawableIcon {
        private final int mId;

        public DrawableIconWithRes(Drawable drawable, int id) {
            super(drawable);
            mId = id;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DrawableIconWithRes && ((DrawableIconWithRes) o).mId == mId;
        }

        @Override
        @NonNull
        public String toString() {
            return String.format("DrawableIconWithRes[resId=0x%08x]", mId);
        }
    }

    public static class ResourceIcon extends Icon {
        private static final SparseArray<Icon> ICONS = new SparseArray<Icon>();

        protected final int mResId;

        private ResourceIcon(int resId) {
            mResId = resId;
        }

        public static synchronized Icon get(int resId) {
            Icon icon = ICONS.get(resId);
            if (icon == null) {
                icon = new ResourceIcon(resId);
                ICONS.put(resId, icon);
            }
            return icon;
        }

        @Override
        public Drawable getDrawable(Context context) {
            return context.getDrawable(mResId);
        }

        @Override
        public Drawable getInvisibleDrawable(Context context) {
            return context.getDrawable(mResId);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ResourceIcon && ((ResourceIcon) o).mResId == mResId;
        }

        @Override
        @NonNull
        public String toString() {
            return String.format("ResourceIcon[resId=0x%08x]", mResId);
        }
    }

    protected static class AnimationIcon extends ResourceIcon {
        private final int mAnimatedResId;

        public AnimationIcon(int resId, int staticResId) {
            super(staticResId);
            mAnimatedResId = resId;
        }

        @Override
        public Drawable getDrawable(Context context) {
            // workaround: get a clean state for every new AVD
            return context.getDrawable(mAnimatedResId).getConstantState().newDrawable();
        }

        @Override
        @NonNull
        public String toString() {
            return String.format("AnimationIcon[resId=0x%08x]", mResId);
        }
    }

    /**
     * Dumps the state of this tile along with its name.
     *
     * This may be used for CTS testing of tiles.
     */
    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.println(this.getClass().getSimpleName() + ":");
        pw.print("    "); pw.println(getState().toString());
    }
}
