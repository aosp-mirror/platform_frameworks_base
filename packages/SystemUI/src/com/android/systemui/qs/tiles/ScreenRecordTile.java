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

package com.android.systemui.qs.tiles;

import android.app.Dialog;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.mediaprojection.MediaProjectionMetricsLogger;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.pipeline.domain.interactor.PanelInteractor;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.res.R;
import com.android.systemui.screenrecord.RecordingController;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import javax.inject.Inject;

/**
 * Quick settings tile for screen recording
 */
public class ScreenRecordTile extends QSTileImpl<QSTile.BooleanState>
        implements RecordingController.RecordingStateChangeCallback {

    public static final String TILE_SPEC = "screenrecord";

    private static final String TAG = "ScreenRecordTile";
    private static final String INTERACTION_JANK_TAG = "screen_record";

    private final RecordingController mController;
    private final KeyguardDismissUtil mKeyguardDismissUtil;
    private final KeyguardStateController mKeyguardStateController;
    private final Callback mCallback = new Callback();
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final FeatureFlags mFlags;
    private final PanelInteractor mPanelInteractor;
    private final MediaProjectionMetricsLogger mMediaProjectionMetricsLogger;
    private final UserContextProvider mUserContextProvider;

    private long mMillisUntilFinished = 0;

    @Inject
    public ScreenRecordTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            FeatureFlags flags,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            RecordingController controller,
            KeyguardDismissUtil keyguardDismissUtil,
            KeyguardStateController keyguardStateController,
            DialogTransitionAnimator dialogTransitionAnimator,
            PanelInteractor panelInteractor,
            MediaProjectionMetricsLogger mediaProjectionMetricsLogger,
            UserContextProvider userContextProvider
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);
        mController = controller;
        mController.observe(this, mCallback);
        mFlags = flags;
        mKeyguardDismissUtil = keyguardDismissUtil;
        mKeyguardStateController = keyguardStateController;
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mPanelInteractor = panelInteractor;
        mMediaProjectionMetricsLogger = mediaProjectionMetricsLogger;
        mUserContextProvider = userContextProvider;
    }

    @Override
    public BooleanState newTileState() {
        BooleanState state = new BooleanState();
        state.label = mContext.getString(R.string.quick_settings_screen_record_label);
        state.handlesLongClick = false;
        return state;
    }

    @Override
    protected void handleClick(@Nullable View view) {
        if (mController.isStarting()) {
            cancelCountdown();
        } else if (mController.isRecording()) {
            stopRecording();
        } else {
            mUiHandler.post(() -> showPrompt(view));
        }
        refreshState();
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        boolean isStarting = mController.isStarting();
        boolean isRecording = mController.isRecording();

        state.value = isRecording || isStarting;
        state.state = (isRecording || isStarting) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.label = mContext.getString(R.string.quick_settings_screen_record_label);
        state.icon = ResourceIcon.get(state.value
                ? R.drawable.qs_screen_record_icon_on
                : R.drawable.qs_screen_record_icon_off);
        // Show expand icon when clicking will open a dialog
        state.forceExpandIcon = state.state == Tile.STATE_INACTIVE;

        if (isRecording) {
            state.secondaryLabel = mContext.getString(R.string.quick_settings_screen_record_stop);
        } else if (isStarting) {
            // round, since the timer isn't exact
            int countdown = (int) Math.floorDiv(mMillisUntilFinished + 500, 1000);
            state.secondaryLabel = String.format("%d...", countdown);
        } else {
            state.secondaryLabel = mContext.getString(R.string.quick_settings_screen_record_start);
        }
        state.contentDescription = TextUtils.isEmpty(state.secondaryLabel)
                ? state.label
                : TextUtils.concat(state.label, ", ", state.secondaryLabel);
        state.expandedAccessibilityClassName = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return 0;
    }

    @Nullable
    @Override
    public Intent getLongClickIntent() {
        return null;
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_screen_record_label);
    }

    private void showPrompt(@Nullable View view) {
        // We animate from the touched view only if we are not on the keyguard, given that if we
        // are we will dismiss it which will also collapse the shade.
        boolean shouldAnimateFromView = view != null && !mKeyguardStateController.isShowing();

        // Create the recording dialog that will collapse the shade only if we start the recording.
        Runnable onStartRecordingClicked = () -> {
            // We dismiss the shade. Since starting the recording will also dismiss the dialog, we
            // disable the exit animation which looks weird when it happens at the same time as the
            // shade collapsing.
            mDialogTransitionAnimator.disableAllCurrentDialogsExitAnimations();
            mPanelInteractor.collapsePanels();
        };

        final Dialog dialog = mController.createScreenRecordDialog(mContext, mFlags,
                mDialogTransitionAnimator, mActivityStarter, onStartRecordingClicked);

        ActivityStarter.OnDismissAction dismissAction = () -> {
            if (shouldAnimateFromView) {
                mDialogTransitionAnimator.showFromView(dialog, view, new DialogCuj(
                        InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN, INTERACTION_JANK_TAG),
                        /* animateBackgroundBoundsChange= */ true);
            } else {
                dialog.show();
            }

            int uid = mUserContextProvider.getUserContext().getUserId();
            mMediaProjectionMetricsLogger.notifyPermissionRequestDisplayed(uid);

            return false;
        };

        mKeyguardDismissUtil.executeWhenUnlocked(dismissAction, false /* requiresShadeOpen */,
                true /* afterKeyguardDone */);
    }

    private void cancelCountdown() {
        Log.d(TAG, "Cancelling countdown");
        mController.cancelCountdown();
    }

    private void stopRecording() {
        mController.stopRecording();
    }

    private final class Callback implements RecordingController.RecordingStateChangeCallback {
        @Override
        public void onCountdown(long millisUntilFinished) {
            mMillisUntilFinished = millisUntilFinished;
            refreshState();
        }

        @Override
        public void onCountdownEnd() {
            refreshState();
        }

        @Override
        public void onRecordingStart() {
            refreshState();
        }

        @Override
        public void onRecordingEnd() {
            refreshState();
        }
    }
}
