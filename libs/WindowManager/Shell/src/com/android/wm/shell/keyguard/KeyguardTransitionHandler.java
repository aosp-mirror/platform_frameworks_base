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

package com.android.wm.shell.keyguard;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_SLEEP;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_LOCKED;
import static android.view.WindowManager.TRANSIT_FLAG_KEYGUARD_GOING_AWAY;
import static android.window.TransitionInfo.FLAG_OCCLUDES_KEYGUARD;

import static com.android.wm.shell.util.TransitionUtil.isOpeningType;
import static com.android.wm.shell.util.TransitionUtil.isClosingType;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.RemoteException;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.Log;
import android.view.SurfaceControl;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;
import com.android.wm.shell.transition.Transitions.TransitionFinishCallback;

import java.util.Map;

/**
 * The handler for Keyguard enter/exit and occlude/unocclude animations.
 *
 * <p>This takes the highest priority.
 */
public class KeyguardTransitionHandler implements Transitions.TransitionHandler {
    private static final String TAG = "KeyguardTransition";

    private final Transitions mTransitions;
    private final Handler mMainHandler;
    private final ShellExecutor mMainExecutor;

    private final Map<IBinder, IRemoteTransition> mStartedTransitions = new ArrayMap<>();

    /**
     * Local IRemoteTransition implementations registered by the keyguard service.
     * @see KeyguardTransitions
     */
    private IRemoteTransition mExitTransition = null;
    private IRemoteTransition mOccludeTransition = null;
    private IRemoteTransition mOccludeByDreamTransition = null;
    private IRemoteTransition mUnoccludeTransition = null;

    public KeyguardTransitionHandler(
            @NonNull ShellInit shellInit,
            @NonNull Transitions transitions,
            @NonNull Handler mainHandler,
            @NonNull ShellExecutor mainExecutor) {
        mTransitions = transitions;
        mMainHandler = mainHandler;
        mMainExecutor = mainExecutor;
        shellInit.addInitCallback(this::onInit, this);
    }

    private void onInit() {
        mTransitions.addHandler(this);
    }

    /**
     * Interface for SystemUI implementations to set custom Keyguard exit/occlude handlers.
     */
    @ExternalThread
    public KeyguardTransitions asKeyguardTransitions() {
        return new KeyguardTransitionsImpl();
    }

    public static boolean handles(TransitionInfo info) {
        return (info.getFlags() & TRANSIT_FLAG_KEYGUARD_GOING_AWAY) != 0
                || (info.getFlags() & TRANSIT_FLAG_KEYGUARD_LOCKED) != 0
                || info.getType() == TRANSIT_KEYGUARD_OCCLUDE
                || info.getType() == TRANSIT_KEYGUARD_UNOCCLUDE;
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull TransitionFinishCallback finishCallback) {
        if (!handles(info)) {
            return false;
        }

        boolean hasOpeningOcclude = false;
        boolean hasOpeningDream = false;
        boolean hasClosingApp = false;

        // Check for occluding/dream/closing apps
        for (int i = info.getChanges().size() - 1; i >= 0; i--) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (isOpeningType(change.getMode())) {
                if (change.hasFlags(FLAG_OCCLUDES_KEYGUARD)) {
                    hasOpeningOcclude = true;
                }
                if (change.getTaskInfo() != null
                        && change.getTaskInfo().getActivityType() == ACTIVITY_TYPE_DREAM) {
                    hasOpeningDream = true;
                }
            } else if (isClosingType(change.getMode())) {
                hasClosingApp = true;
            }
        }

        // Choose a transition applicable for the changes and keyguard state.
        if ((info.getFlags() & TRANSIT_FLAG_KEYGUARD_GOING_AWAY) != 0) {
            return startAnimation(mExitTransition,
                    "going-away",
                    transition, info, startTransaction, finishTransaction, finishCallback);
        }
        if (hasOpeningOcclude || info.getType() == TRANSIT_KEYGUARD_OCCLUDE) {
            if (hasOpeningDream) {
                return startAnimation(mOccludeByDreamTransition,
                        "occlude-by-dream",
                        transition, info, startTransaction, finishTransaction, finishCallback);
            } else {
                return startAnimation(mOccludeTransition,
                        "occlude",
                        transition, info, startTransaction, finishTransaction, finishCallback);
            }
        } else if (hasClosingApp || info.getType() == TRANSIT_KEYGUARD_UNOCCLUDE) {
             return startAnimation(mUnoccludeTransition,
                    "unocclude",
                    transition, info, startTransaction, finishTransaction, finishCallback);
        } else {
            Log.wtf(TAG, "Failed to play: " + info);
            return false;
        }
    }

    private boolean startAnimation(IRemoteTransition remoteHandler, String description,
            @NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull TransitionFinishCallback finishCallback) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                "start keyguard %s transition, info = %s", description, info);

        try {
            remoteHandler.startAnimation(transition, info, startTransaction,
                    new IRemoteTransitionFinishedCallback.Stub() {
                        @Override
                        public void onTransitionFinished(
                                WindowContainerTransaction wct, SurfaceControl.Transaction sct) {
                            mMainExecutor.execute(() -> {
                                finishCallback.onTransitionFinished(wct, null);
                            });
                        }
                    });
            mStartedTransitions.put(transition, remoteHandler);
        } catch (RemoteException e) {
            Log.wtf(TAG, "RemoteException thrown from local IRemoteTransition", e);
            return false;
        }
        startTransaction.clear();
        return true;
    }

    @Override
    public void mergeAnimation(@NonNull IBinder nextTransition, @NonNull TransitionInfo nextInfo,
            @NonNull SurfaceControl.Transaction nextT, @NonNull IBinder currentTransition,
            @NonNull TransitionFinishCallback nextFinishCallback) {
        final IRemoteTransition playing = mStartedTransitions.get(currentTransition);

        if (playing == null) {
            ProtoLog.e(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, 
                    "unknown keyguard transition %s", currentTransition);
            return;
        }

        if (nextInfo.getType() == TRANSIT_SLEEP) {
            // An empty SLEEP transition comes in as a signal to abort transitions whenever a sleep
            // token is held. In cases where keyguard is showing, we are running the animation for
            // the device sleeping/waking, so it's best to ignore this and keep playing anyway.
            return;
        } else {
            finishAnimationImmediately(currentTransition);
        }
    }

    @Override
    public void onTransitionConsumed(IBinder transition, boolean aborted,
            SurfaceControl.Transaction finishTransaction) {
        finishAnimationImmediately(transition);
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        return null;
    }

    private void finishAnimationImmediately(IBinder transition) {
        final IRemoteTransition playing = mStartedTransitions.get(transition);

        if (playing != null) {
            final IBinder fakeTransition = new Binder();
            final TransitionInfo fakeInfo = new TransitionInfo(TRANSIT_SLEEP, 0x0);
            final SurfaceControl.Transaction fakeT = new SurfaceControl.Transaction();
            final FakeFinishCallback fakeFinishCb = new FakeFinishCallback();
            try {
                playing.mergeAnimation(fakeTransition, fakeInfo, fakeT, transition, fakeFinishCb);
            } catch (RemoteException e) {
                // There is no good reason for this to happen because the player is a local object
                // implementing an AIDL interface.
                Log.wtf(TAG, "RemoteException thrown from KeyguardService transition", e);
            }
        }
    }

    private static class FakeFinishCallback extends IRemoteTransitionFinishedCallback.Stub {
        @Override
        public void onTransitionFinished(
                WindowContainerTransaction wct, SurfaceControl.Transaction t) {
            return;
        }
    }

    @ExternalThread
    private final class KeyguardTransitionsImpl implements KeyguardTransitions {
        @Override
        public void register(
                IRemoteTransition exitTransition,
                IRemoteTransition occludeTransition,
                IRemoteTransition occludeByDreamTransition,
                IRemoteTransition unoccludeTransition) {
            mMainExecutor.execute(() -> {
                mExitTransition = exitTransition;
                mOccludeTransition = occludeTransition;
                mOccludeByDreamTransition = occludeByDreamTransition;
                mUnoccludeTransition = unoccludeTransition;
            });
        }
    }
}
