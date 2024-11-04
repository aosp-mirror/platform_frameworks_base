/*
 * Copyright (C) 2015 The Android Open Source Project
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

import static android.provider.Settings.Global.ZEN_MODE_ALARMS;
import static android.provider.Settings.Global.ZEN_MODE_OFF;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.ZenModeConfig;
import android.service.quicksettings.Tile;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Switch;

import androidx.annotation.Nullable;

import com.android.internal.jank.InteractionJankMonitor;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.settingslib.notification.modes.EnableZenModeDialog;
import com.android.systemui.Prefs;
import com.android.systemui.animation.DialogCuj;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.animation.Expandable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.modes.shared.ModesUi;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.qs.QSHost;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.UserSettingObserver;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tiles.dialog.QSZenModeDialogMetricsLogger;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.settings.SecureSettings;

import javax.inject.Inject;

/** Quick settings tile: Do not disturb **/
public class DndTile extends QSTileImpl<BooleanState> {

    public static final String TILE_SPEC = "dnd";

    private static final Intent ZEN_SETTINGS =
            new Intent(Settings.ACTION_ZEN_MODE_SETTINGS);

    private static final Intent ZEN_PRIORITY_SETTINGS =
            new Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS);

    private static final String INTERACTION_JANK_TAG = "start_zen_mode";

    private final ZenModeController mController;
    private final SharedPreferences mSharedPreferences;
    private final UserSettingObserver mSettingZenDuration;
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final QSZenModeDialogMetricsLogger mQSZenDialogMetricsLogger;

    private boolean mListening;

    @Inject
    public DndTile(
            QSHost host,
            QsEventLogger uiEventLogger,
            @Background Looper backgroundLooper,
            @Main Handler mainHandler,
            FalsingManager falsingManager,
            MetricsLogger metricsLogger,
            StatusBarStateController statusBarStateController,
            ActivityStarter activityStarter,
            QSLogger qsLogger,
            ZenModeController zenModeController,
            @Main SharedPreferences sharedPreferences,
            SecureSettings secureSettings,
            DialogTransitionAnimator dialogTransitionAnimator
    ) {
        super(host, uiEventLogger, backgroundLooper, mainHandler, falsingManager, metricsLogger,
                statusBarStateController, activityStarter, qsLogger);

        // If the flag is on, this shouldn't run at all since the modes tile replaces the DND tile.
        ModesUi.assertInLegacyMode();

        mController = zenModeController;
        mSharedPreferences = sharedPreferences;
        mController.observe(getLifecycle(), mZenCallback);
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mSettingZenDuration = new UserSettingObserver(secureSettings, mUiHandler,
                Settings.Secure.ZEN_DURATION, getHost().getUserId()) {
            @Override
            protected void handleValueChanged(int value, boolean observedChange) {
                refreshState();
            }
        };
        mQSZenDialogMetricsLogger = new QSZenModeDialogMetricsLogger(mContext);
    }

    public static void setVisible(Context context, boolean visible) {
        Prefs.putBoolean(context, Prefs.Key.DND_TILE_VISIBLE, visible);
    }

    public static boolean isVisible(SharedPreferences prefs) {
        return prefs.getBoolean(Prefs.Key.DND_TILE_VISIBLE, false /* defaultValue */);
    }

    public static void setCombinedIcon(Context context, boolean combined) {
        Prefs.putBoolean(context, Prefs.Key.DND_TILE_COMBINED_ICON, combined);
    }

    public static boolean isCombinedIcon(SharedPreferences sharedPreferences) {
        return sharedPreferences.getBoolean(Prefs.Key.DND_TILE_COMBINED_ICON,
                false /* defaultValue */);
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    public Intent getLongClickIntent() {
        return ZEN_SETTINGS;
    }

    @Override
    protected void handleClick(@Nullable Expandable expandable) {
        // Zen is currently on
        if (mState.value) {
            mController.setZen(ZEN_MODE_OFF, null, TAG);
        } else {
            enableZenMode(expandable);
        }
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        super.handleUserSwitch(newUserId);
        mSettingZenDuration.setUserId(newUserId);
    }

    private void enableZenMode(@Nullable Expandable expandable) {
        int zenDuration = mSettingZenDuration.getValue();
        switch (zenDuration) {
            case Settings.Secure.ZEN_DURATION_PROMPT:
                mUiHandler.post(() -> {
                    Dialog dialog = makeZenModeDialog();
                    if (expandable != null) {
                        DialogTransitionAnimator.Controller controller =
                                expandable.dialogTransitionController(new DialogCuj(
                                        InteractionJankMonitor.CUJ_SHADE_DIALOG_OPEN,
                                        INTERACTION_JANK_TAG));
                        if (controller != null) {
                            mDialogTransitionAnimator.show(dialog,
                                    controller, /* animateBackgroundBoundsChange= */ false);
                        } else {
                            dialog.show();
                        }
                    } else {
                        dialog.show();
                    }
                });
                break;
            case Settings.Secure.ZEN_DURATION_FOREVER:
                mController.setZen(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS, null, TAG);
                break;
            default:
                Uri conditionId = ZenModeConfig.toTimeCondition(mContext, zenDuration,
                        mHost.getUserId(), true).id;
                mController.setZen(Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS,
                        conditionId, TAG);
        }
    }

    private Dialog makeZenModeDialog() {
        AlertDialog dialog = new EnableZenModeDialog(mContext, R.style.Theme_SystemUI_Dialog,
                true /* cancelIsNeutral */,
                mQSZenDialogMetricsLogger).createDialog();
        SystemUIDialog.applyFlags(dialog);
        SystemUIDialog.setShowForAllUsers(dialog, true);
        SystemUIDialog.registerDismissListener(dialog);
        SystemUIDialog.setDialogSize(dialog);
        return dialog;
    }

    @Override
    protected void handleSecondaryClick(@Nullable Expandable expandable) {
        handleLongClick(expandable);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_dnd_label);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        if (mController == null) return;
        final int zen = arg instanceof Integer ? (Integer) arg : mController.getZen();
        final boolean newValue = zen != ZEN_MODE_OFF;
        state.dualTarget = true;
        state.value = newValue;
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
        state.icon = ResourceIcon.get(state.value
                ? R.drawable.qs_dnd_icon_on
                : R.drawable.qs_dnd_icon_off);
        state.label = getTileLabel();
        state.secondaryLabel = TextUtils.emptyIfNull(ZenModeConfig.getDescription(mContext,
                zen != Global.ZEN_MODE_OFF, mController.getConfig(), false));
        checkIfRestrictionEnforcedByAdminOnly(state, UserManager.DISALLOW_ADJUST_VOLUME);
        // Keeping the secondaryLabel in contentDescription instead of stateDescription is easier
        // to understand.
        switch (zen) {
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS:
                state.contentDescription =
                        mContext.getString(R.string.accessibility_quick_settings_dnd) + ", "
                                + state.secondaryLabel;
                break;
            case Global.ZEN_MODE_NO_INTERRUPTIONS:
                state.contentDescription =
                        mContext.getString(R.string.accessibility_quick_settings_dnd) + ", " +
                                mContext.getString(
                                        R.string.accessibility_quick_settings_dnd_none_on)
                                + ", " + state.secondaryLabel;
                break;
            case ZEN_MODE_ALARMS:
                state.contentDescription =
                        mContext.getString(R.string.accessibility_quick_settings_dnd) + ", " +
                                mContext.getString(
                                        R.string.accessibility_quick_settings_dnd_alarms_on)
                                + ", " + state.secondaryLabel;
                break;
            default:
                state.contentDescription = mContext.getString(
                        R.string.accessibility_quick_settings_dnd);
                break;
        }
        state.dualLabelContentDescription = mContext.getResources().getString(
                R.string.accessibility_quick_settings_open_settings, getTileLabel());
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.forceExpandIcon =
                mSettingZenDuration.getValue() == Settings.Secure.ZEN_DURATION_PROMPT;
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_DND;
    }

    @Override
    public void handleSetListening(boolean listening) {
        super.handleSetListening(listening);
        if (mListening == listening) return;
        mListening = listening;
        if (mListening) {
            Prefs.registerListener(mContext, mPrefListener);
        } else {
            Prefs.unregisterListener(mContext, mPrefListener);
        }
        mSettingZenDuration.setListening(listening);
    }

    @Override
    protected void handleDestroy() {
        super.handleDestroy();
        mSettingZenDuration.setListening(false);
    }

    @Override
    public boolean isAvailable() {
        return isVisible(mSharedPreferences);
    }

    private final OnSharedPreferenceChangeListener mPrefListener
            = new OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences,
                @Prefs.Key String key) {
            if (Prefs.Key.DND_TILE_COMBINED_ICON.equals(key) ||
                    Prefs.Key.DND_TILE_VISIBLE.equals(key)) {
                refreshState();
            }
        }
    };

    private final ZenModeController.Callback mZenCallback = new ZenModeController.Callback() {
        public void onZenChanged(int zen) {
            Log.d(TAG, "Zen changed to " + zen + ". Requesting refresh of tile.");
            refreshState(zen);
        }
    };

}
