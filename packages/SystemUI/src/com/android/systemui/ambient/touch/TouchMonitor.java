/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.ambient.touch;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static com.android.systemui.shared.Flags.bouncerAreaExclusion;
import static com.android.systemui.util.kotlin.JavaAdapterKt.collectFlow;

import android.graphics.Rect;
import android.graphics.Region;
import android.os.RemoteException;
import android.util.Log;
import android.view.GestureDetector;
import android.view.ISystemGestureExclusionListener;
import android.view.IWindowManager;
import android.view.InputEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.android.systemui.Flags;
import com.android.systemui.ambient.touch.dagger.InputSessionComponent;
import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.DisplayId;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.shared.system.InputChannelCompat;
import com.android.systemui.util.display.DisplayHelper;

import com.google.common.util.concurrent.ListenableFuture;

import kotlinx.coroutines.Job;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.inject.Inject;

/**
 * {@link TouchMonitor} is responsible for monitoring touches and gestures over the
 * dream overlay and redirecting them to a set of listeners. This monitor is in charge of figuring
 * out when listeners are eligible for receiving touches and filtering the listener pool if
 * touches are consumed.
 */
public class TouchMonitor {
    // This executor is used to protect {@code mActiveTouchSessions} from being modified
    // concurrently. Any operation that adds or removes values should use this executor.
    public String TAG = "DreamOverlayTouchMonitor";
    private final Executor mMainExecutor;
    private final Executor mBackgroundExecutor;

    private final ConfigurationInteractor mConfigurationInteractor;

    private final Lifecycle mLifecycle;
    private Rect mExclusionRect = null;

    private ISystemGestureExclusionListener mGestureExclusionListener;

    private Consumer<Rect> mMaxBoundsConsumer = rect -> mMaxBounds = rect;


    /**
     * Adds a new {@link TouchSessionImpl} to participate in receiving future touches and gestures.
     */
    private ListenableFuture<TouchHandler.TouchSession> push(
            TouchSessionImpl touchSessionImpl) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            mMainExecutor.execute(() -> {
                if (!mActiveTouchSessions.remove(touchSessionImpl)) {
                    completer.set(null);
                    return;
                }

                final TouchSessionImpl touchSession =
                        new TouchSessionImpl(this, touchSessionImpl.getBounds(),
                                touchSessionImpl);
                mActiveTouchSessions.add(touchSession);
                completer.set(touchSession);
            });

            return "DreamOverlayTouchMonitor::push";
        });
    }

    /**
     * Removes a {@link TouchSessionImpl} from receiving further updates.
     */
    private ListenableFuture<TouchHandler.TouchSession> pop(
            TouchSessionImpl touchSessionImpl) {
        return CallbackToFutureAdapter.getFuture(completer -> {
            mMainExecutor.execute(() -> {
                if (mActiveTouchSessions.remove(touchSessionImpl)) {
                    touchSessionImpl.onRemoved();

                    final TouchSessionImpl predecessor = touchSessionImpl.getPredecessor();

                    if (predecessor != null) {
                        mActiveTouchSessions.add(predecessor);
                    }

                    completer.set(predecessor);
                }

                if (mActiveTouchSessions.isEmpty() && mStopMonitoringPending) {
                    stopMonitoring(false);
                }
            });

            return "DreamOverlayTouchMonitor::pop";
        });
    }

    private int getSessionCount() {
        return mActiveTouchSessions.size();
    }

    /**
     * {@link TouchSessionImpl} implements {@link TouchHandler.TouchSession} for
     * {@link TouchMonitor}. It enables the monitor to access the associated listeners
     * and provides the associated client with access to the monitor.
     */
    private static class TouchSessionImpl implements TouchHandler.TouchSession {
        private final HashSet<InputChannelCompat.InputEventListener> mEventListeners =
                new HashSet<>();
        private final HashSet<GestureDetector.OnGestureListener> mGestureListeners =
                new HashSet<>();
        private final HashSet<Callback> mCallbacks = new HashSet<>();

        private final TouchSessionImpl mPredecessor;
        private final TouchMonitor mTouchMonitor;
        private final Rect mBounds;

        TouchSessionImpl(TouchMonitor touchMonitor, Rect bounds,
                TouchSessionImpl predecessor) {
            mPredecessor = predecessor;
            mTouchMonitor = touchMonitor;
            mBounds = bounds;
        }

        @Override
        public void registerCallback(Callback callback) {
            mCallbacks.add(callback);
        }

        @Override
        public boolean registerInputListener(
                InputChannelCompat.InputEventListener inputEventListener) {
            return mEventListeners.add(inputEventListener);
        }

        @Override
        public boolean registerGestureListener(GestureDetector.OnGestureListener gestureListener) {
            return mGestureListeners.add(gestureListener);
        }

        @Override
        public ListenableFuture<TouchHandler.TouchSession> push() {
            return mTouchMonitor.push(this);
        }

        @Override
        public ListenableFuture<TouchHandler.TouchSession> pop() {
            return mTouchMonitor.pop(this);
        }

        @Override
        public int getActiveSessionCount() {
            return mTouchMonitor.getSessionCount();
        }

        /**
         * Returns the active listeners to receive touch events.
         */
        public Collection<InputChannelCompat.InputEventListener> getEventListeners() {
            return mEventListeners;
        }

        /**
         * Returns the active listeners to receive gesture events.
         */
        public Collection<GestureDetector.OnGestureListener> getGestureListeners() {
            return mGestureListeners;
        }

        /**
         * Returns the {@link TouchSessionImpl} that preceded this current session. This will
         * become the new active session when this session is popped.
         */
        private TouchSessionImpl getPredecessor() {
            return mPredecessor;
        }

        /**
         * Called by the monitor when this session is removed.
         */
        private void onRemoved() {
            mEventListeners.clear();
            mGestureListeners.clear();
            final Iterator<Callback> iter = mCallbacks.iterator();
            while (iter.hasNext()) {
                final Callback callback = iter.next();
                callback.onRemoved();
                iter.remove();
            }
        }

        @Override
        public Rect getBounds() {
            return mBounds;
        }
    }

    /**
     * This lifecycle observer ensures touch monitoring only occurs while the overlay is "resumed".
     * This concept is mapped over from the equivalent view definition: The {@link LifecycleOwner}
     * will report the dream is not resumed when it is obscured (from the notification shade being
     * expanded for example) or not active (such as when it is destroyed).
     */
    private final LifecycleObserver mLifecycleObserver = new DefaultLifecycleObserver() {
        @Override
        public void onResume(@NonNull LifecycleOwner owner) {
            startMonitoring();
        }

        @Override
        public void onPause(@NonNull LifecycleOwner owner) {
            stopMonitoring(false);
        }

        @Override
        public void onDestroy(LifecycleOwner owner) {
            stopMonitoring(true);
        }
    };

    /**
     * When invoked, instantiates a new {@link InputSession} to monitor touch events.
     */
    private void startMonitoring() {
        stopMonitoring(true);

        if (bouncerAreaExclusion()) {
            mBackgroundExecutor.execute(() -> {
                try {
                    mGestureExclusionListener = new ISystemGestureExclusionListener.Stub() {
                        @Override
                        public void onSystemGestureExclusionChanged(int displayId,
                                Region systemGestureExclusion,
                                Region systemGestureExclusionUnrestricted) {
                            mExclusionRect = systemGestureExclusion.getBounds();
                        }
                    };
                    mWindowManagerService.registerSystemGestureExclusionListener(
                            mGestureExclusionListener, mDisplayId);
                } catch (RemoteException e) {
                    // Handle the exception
                    Log.e(TAG, "Failed to register gesture exclusion listener", e);
                }
            });
        }
        mCurrentInputSession = mInputSessionFactory.create(
                        "dreamOverlay",
                        mInputEventListener,
                        mOnGestureListener,
                        true)
                .getInputSession();
    }

    /**
     * Destroys any active {@link InputSession}.
     */
    private void stopMonitoring(boolean force) {
        mExclusionRect = null;
        if (bouncerAreaExclusion()) {
            mBackgroundExecutor.execute(() -> {
                try {
                    if (mGestureExclusionListener != null) {
                        mWindowManagerService.unregisterSystemGestureExclusionListener(
                                mGestureExclusionListener, mDisplayId);
                        mGestureExclusionListener = null;
                    }
                } catch (RemoteException e) {
                    // Handle the exception
                    Log.e(TAG, "unregisterSystemGestureExclusionListener: failed", e);
                }
            });
        }
        if (mCurrentInputSession == null) {
            return;
        }

        if (!mActiveTouchSessions.isEmpty() && !force) {
            mStopMonitoringPending = true;
            return;
        }

        // When we stop monitoring touches, we must ensure that all active touch sessions and
        // descendants informed of the removal so any cleanup for active tracking can proceed.
        mMainExecutor.execute(() -> {
            mActiveTouchSessions.forEach(touchSession -> {
                while (touchSession != null) {
                    touchSession.onRemoved();
                    touchSession = touchSession.getPredecessor();
                }
            });
            mActiveTouchSessions.clear();
        });

        mCurrentInputSession.dispose();
        mCurrentInputSession = null;
        mStopMonitoringPending = false;
    }


    private final HashSet<TouchSessionImpl> mActiveTouchSessions = new HashSet<>();
    private final Collection<TouchHandler> mHandlers;
    private final DisplayHelper mDisplayHelper;

    private boolean mStopMonitoringPending;

    private InputChannelCompat.InputEventListener mInputEventListener =
            new InputChannelCompat.InputEventListener() {
                @Override
                public void onInputEvent(InputEvent ev) {
                    // No Active sessions are receiving touches. Create sessions for each listener
                    if (mActiveTouchSessions.isEmpty()) {
                        final HashMap<TouchHandler, TouchHandler.TouchSession> sessionMap =
                                new HashMap<>();

                        for (TouchHandler handler : mHandlers) {
                            if (!handler.isEnabled()) {
                                continue;
                            }

                            final Rect maxBounds =
                                    Flags.ambientTouchMonitorListenToDisplayChanges()
                                            ? mMaxBounds
                                            : mDisplayHelper.getMaxBounds(ev.getDisplayId(),
                                                    TYPE_APPLICATION_OVERLAY);

                            final Region initiationRegion = Region.obtain();
                            Rect exclusionRect = null;
                            if (bouncerAreaExclusion()) {
                                exclusionRect = getCurrentExclusionRect();
                            }
                            handler.getTouchInitiationRegion(
                                    maxBounds, initiationRegion, exclusionRect);

                            if (!initiationRegion.isEmpty()) {
                                // Initiation regions require a motion event to determine pointer
                                // location
                                // within the region.
                                if (!(ev instanceof MotionEvent)) {
                                    continue;
                                }

                                final MotionEvent motionEvent = (MotionEvent) ev;

                                // If the touch event is outside the region, then ignore.
                                if (!initiationRegion.contains(Math.round(motionEvent.getX()),
                                        Math.round(motionEvent.getY()))) {
                                    continue;
                                }
                            }

                            final TouchSessionImpl sessionStack = new TouchSessionImpl(
                                    TouchMonitor.this, maxBounds, null);
                            mActiveTouchSessions.add(sessionStack);
                            sessionMap.put(handler, sessionStack);
                        }

                        // Informing handlers of new sessions is delayed until we have all
                        // created so the
                        // final session is correct.
                        sessionMap.forEach((dreamTouchHandler, touchSession)
                                -> dreamTouchHandler.onSessionStart(touchSession));
                    }

                    // Find active sessions and invoke on InputEvent.
                    mActiveTouchSessions.stream()
                            .map(touchSessionStack -> touchSessionStack.getEventListeners())
                            .flatMap(Collection::stream)
                            .forEach(inputEventListener -> inputEventListener.onInputEvent(ev));
                }

                private Rect getCurrentExclusionRect() {
                    return mExclusionRect;
                }
            };

    /**
     * The {@link Evaluator} interface allows for callers to inspect a listener from the
     * {@link android.view.GestureDetector.OnGestureListener} set. This helps reduce duplicated
     * iteration loops over this set.
     */
    private interface Evaluator {
        boolean evaluate(GestureDetector.OnGestureListener listener);
    }

    private GestureDetector.OnGestureListener mOnGestureListener =
            new GestureDetector.OnGestureListener() {
                private boolean evaluate(Evaluator evaluator) {
                    final Set<TouchSessionImpl> consumingSessions = new HashSet<>();

                    // When a gesture is consumed, it is assumed that all touches for the current
                    // session
                    // should be directed only to those TouchSessions until those sessions are
                    // popped. All
                    // non-participating sessions are removed from receiving further updates with
                    // {@link DreamOverlayTouchMonitor#isolate}.
                    final boolean eventConsumed = mActiveTouchSessions.stream()
                            .map(touchSession -> {
                                boolean consume = touchSession.getGestureListeners()
                                        .stream()
                                        .map(listener -> evaluator.evaluate(listener))
                                        .anyMatch(consumed -> consumed);

                                if (consume) {
                                    consumingSessions.add(touchSession);
                                }
                                return consume;
                            }).anyMatch(consumed -> consumed);

                    if (eventConsumed) {
                        TouchMonitor.this.isolate(consumingSessions);
                    }

                    return eventConsumed;
                }

                // This method is called for gesture events that cannot be consumed.
                private void observe(Consumer<GestureDetector.OnGestureListener> consumer) {
                    mActiveTouchSessions.stream()
                            .map(touchSession -> touchSession.getGestureListeners())
                            .flatMap(Collection::stream)
                            .forEach(listener -> consumer.accept(listener));
                }

                @Override
                public boolean onDown(MotionEvent e) {
                    return evaluate(listener -> listener.onDown(e));
                }

                @Override
                public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX,
                        float velocityY) {
                    return evaluate(listener -> listener.onFling(e1, e2, velocityX, velocityY));
                }

                @Override
                public void onLongPress(MotionEvent e) {
                    observe(listener -> listener.onLongPress(e));
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX,
                        float distanceY) {
                    return evaluate(listener -> listener.onScroll(e1, e2, distanceX, distanceY));
                }

                @Override
                public void onShowPress(MotionEvent e) {
                    observe(listener -> listener.onShowPress(e));
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    return evaluate(listener -> listener.onSingleTapUp(e));
                }
            };

    private InputSessionComponent.Factory mInputSessionFactory;
    private InputSession mCurrentInputSession;
    private final int mDisplayId;
    private final IWindowManager mWindowManagerService;

    private Rect mMaxBounds;

    private Job mBoundsFlow;

    private boolean mInitialized;


    /**
     * Designated constructor for {@link TouchMonitor}
     *
     * @param executor            This executor will be used for maintaining the active listener
     *                            list to avoid
     *                            concurrent modification.
     * @param lifecycle           {@link TouchMonitor} will listen to this lifecycle to determine
     *                            whether touch monitoring should be active.
     * @param inputSessionFactory This factory will generate the {@link InputSession} requested by
     *                            the monitor. Each session should be unique and valid when
     *                            returned.
     * @param handlers            This set represents the {@link TouchHandler} instances that will
     *                            participate in touch handling.
     */
    @Inject
    public TouchMonitor(
            @Main Executor executor,
            @Background Executor backgroundExecutor,
            Lifecycle lifecycle,
            InputSessionComponent.Factory inputSessionFactory,
            DisplayHelper displayHelper,
            ConfigurationInteractor configurationInteractor,
            Set<TouchHandler> handlers,
            IWindowManager windowManagerService,
            @DisplayId int displayId) {
        mDisplayId = displayId;
        mHandlers = handlers;
        mInputSessionFactory = inputSessionFactory;
        mMainExecutor = executor;
        mBackgroundExecutor = backgroundExecutor;
        mLifecycle = lifecycle;
        mDisplayHelper = displayHelper;
        mWindowManagerService = windowManagerService;
        mConfigurationInteractor = configurationInteractor;
    }

    /**
     * Initializes the monitor. should only be called once after creation.
     */
    public void init() {
        if (mInitialized) {
            throw new IllegalStateException("TouchMonitor already initialized");
        }

        mLifecycle.addObserver(mLifecycleObserver);
        if (Flags.ambientTouchMonitorListenToDisplayChanges()) {
            mBoundsFlow = collectFlow(mLifecycle, mConfigurationInteractor.getMaxBounds(),
                    mMaxBoundsConsumer);
        }

        mInitialized = true;
    }

    /**
     * Called when the TouchMonitor should be discarded and will not be used anymore.
     */
    public void destroy() {
        if (!mInitialized) {
            throw new IllegalStateException("TouchMonitor not initialized");
        }

        stopMonitoring(true);

        mLifecycle.removeObserver(mLifecycleObserver);
        if (Flags.ambientTouchMonitorListenToDisplayChanges()) {
            mBoundsFlow.cancel(new CancellationException());
        }

        mInitialized = false;
    }

    private void isolate(Set<TouchSessionImpl> sessions) {
        Collection<TouchSessionImpl> removedSessions = mActiveTouchSessions.stream()
                .filter(touchSession -> !sessions.contains(touchSession))
                .collect(Collectors.toCollection(HashSet::new));

        removedSessions.forEach(touchSession -> touchSession.onRemoved());

        mActiveTouchSessions.removeAll(removedSessions);
    }
}
