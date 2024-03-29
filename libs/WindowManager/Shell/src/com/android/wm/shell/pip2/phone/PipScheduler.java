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

package com.android.wm.shell.pip2.phone;

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.wm.shell.transition.Transitions.TRANSIT_EXIT_PIP;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.pip.PipBoundsState;
import com.android.wm.shell.common.pip.PipUtils;
import com.android.wm.shell.pip.PipTransitionController;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.function.Consumer;

/**
 * Scheduler for Shell initiated PiP transitions and animations.
 */
public class PipScheduler {
    private static final String TAG = PipScheduler.class.getSimpleName();
    private static final String BROADCAST_FILTER = PipScheduler.class.getCanonicalName();

    private final Context mContext;
    private final PipBoundsState mPipBoundsState;
    private final ShellExecutor mMainExecutor;
    private final ShellTaskOrganizer mShellTaskOrganizer;
    private PipSchedulerReceiver mSchedulerReceiver;
    private PipTransitionController mPipTransitionController;

    // pinned PiP task's WC token
    @Nullable
    private WindowContainerToken mPipTaskToken;

    // pinned PiP task's leash
    @Nullable
    private SurfaceControl mPinnedTaskLeash;

    // true if Launcher has started swipe PiP to home animation
    private boolean mInSwipePipToHomeTransition;

    // Overlay leash potentially used during swipe PiP to home transition;
    // if null while mInSwipePipToHomeTransition is true, then srcRectHint was invalid.
    @Nullable
    SurfaceControl mSwipePipToHomeOverlay;

    // App bounds used when as a starting point to swipe PiP to home animation in Launcher;
    // these are also used to calculate the app icon overlay buffer size.
    @NonNull
    final Rect mSwipePipToHomeAppBounds = new Rect();

    /**
     * Temporary PiP CUJ codes to schedule PiP related transitions directly from Shell.
     * This is used for a broadcast receiver to resolve intents. This should be removed once
     * there is an equivalent of PipTouchHandler and PipResizeGestureHandler for PiP2.
     */
    private static final int PIP_EXIT_VIA_EXPAND_CODE = 0;
    private static final int PIP_DOUBLE_TAP = 1;

    @IntDef(value = {
            PIP_EXIT_VIA_EXPAND_CODE,
            PIP_DOUBLE_TAP
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface PipUserJourneyCode {}

    /**
     * A temporary broadcast receiver to initiate PiP CUJs.
     */
    private class PipSchedulerReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int userJourneyCode = intent.getIntExtra("cuj_code_extra", 0);
            switch (userJourneyCode) {
                case PIP_EXIT_VIA_EXPAND_CODE:
                    scheduleExitPipViaExpand();
                    break;
                case PIP_DOUBLE_TAP:
                    scheduleDoubleTapToResize();
                    break;
                default:
                    throw new IllegalStateException("unexpected CUJ code=" + userJourneyCode);
            }
        }
    }

    public PipScheduler(Context context,
            PipBoundsState pipBoundsState,
            ShellExecutor mainExecutor,
            ShellTaskOrganizer shellTaskOrganizer) {
        mContext = context;
        mPipBoundsState = pipBoundsState;
        mMainExecutor = mainExecutor;
        mShellTaskOrganizer = shellTaskOrganizer;

        if (PipUtils.isPip2ExperimentEnabled()) {
            // temporary broadcast receiver to initiate exit PiP via expand
            mSchedulerReceiver = new PipSchedulerReceiver();
            ContextCompat.registerReceiver(mContext, mSchedulerReceiver,
                    new IntentFilter(BROADCAST_FILTER), ContextCompat.RECEIVER_EXPORTED);
        }
    }

    ShellExecutor getMainExecutor() {
        return mMainExecutor;
    }

    void setPipTransitionController(PipTransitionController pipTransitionController) {
        mPipTransitionController = pipTransitionController;
    }

    void setPinnedTaskLeash(SurfaceControl pinnedTaskLeash) {
        mPinnedTaskLeash = pinnedTaskLeash;
    }

    void setPipTaskToken(@Nullable WindowContainerToken pipTaskToken) {
        mPipTaskToken = pipTaskToken;
    }

    @Nullable
    private WindowContainerTransaction getExitPipViaExpandTransaction() {
        if (mPipTaskToken == null) {
            return null;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        // final expanded bounds to be inherited from the parent
        wct.setBounds(mPipTaskToken, null);
        // if we are hitting a multi-activity case
        // windowing mode change will reparent to original host task
        wct.setWindowingMode(mPipTaskToken, WINDOWING_MODE_UNDEFINED);
        return wct;
    }

    /**
     * Schedules exit PiP via expand transition.
     */
    public void scheduleExitPipViaExpand() {
        WindowContainerTransaction wct = getExitPipViaExpandTransaction();
        if (wct != null) {
            mMainExecutor.execute(() -> {
                mPipTransitionController.startExitTransition(TRANSIT_EXIT_PIP, wct,
                        null /* destinationBounds */);
            });
        }
    }

    /**
     * Schedules resize PiP via double tap.
     */
    public void scheduleDoubleTapToResize() {}

    /**
     * Animates resizing of the pinned stack given the duration.
     */
    public void scheduleAnimateResizePip(Rect toBounds, Consumer<Rect> onFinishResizeCallback) {
        if (mPipTaskToken == null) {
            return;
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        wct.setBounds(mPipTaskToken, toBounds);
        mPipTransitionController.startResizeTransition(wct, onFinishResizeCallback);
    }

    void onSwipePipToHomeAnimationStart(int taskId, ComponentName componentName,
            Rect destinationBounds, SurfaceControl overlay, Rect appBounds) {
        mInSwipePipToHomeTransition = true;
        mSwipePipToHomeOverlay = overlay;
        mSwipePipToHomeAppBounds.set(appBounds);
        if (overlay != null) {
            // Shell transitions might use a root animation leash, which will be removed when
            // the Recents transition is finished. Launcher attaches the overlay leash to this
            // animation target leash; thus, we need to reparent it to the actual Task surface now.
            // PipTransition is responsible to fade it out and cleanup when finishing the enter PIP
            // transition.
            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            mShellTaskOrganizer.reparentChildSurfaceToTask(taskId, overlay, tx);
            tx.setLayer(overlay, Integer.MAX_VALUE);
            tx.apply();
        }
    }

    void setInSwipePipToHomeTransition(boolean inSwipePipToHome) {
        mInSwipePipToHomeTransition = inSwipePipToHome;
    }

    boolean isInSwipePipToHomeTransition() {
        return mInSwipePipToHomeTransition;
    }

    void onExitPip() {
        mPipTaskToken = null;
        mPinnedTaskLeash = null;
    }
}
