/*
 * Copyright (C) 2019 CypherOS
 * Copyright (C) 2020 Paranoid Android
 * Copyright (C) 2020-2021 crDroid Android Project
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

package com.android.systemui.tristate;

import static android.view.Surface.ROTATION_90;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;

import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.hardware.display.DisplayManagerGlobal;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.util.DisplayUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.OrientationEventListener;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.tristate.TriStateUiController;
import com.android.systemui.tristate.TriStateUiController.UserActivityListener;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.plugins.VolumeDialogController.Callbacks;
import com.android.systemui.plugins.VolumeDialogController.State;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.tuner.TunerService;

public class TriStateUiControllerImpl implements TriStateUiController,
        ConfigurationController.ConfigurationListener, TunerService.Tunable {


    private static String TAG = "TriStateUiControllerImpl";

    public static final String ALERT_SLIDER_NOTIFICATIONS =
            "system:" + Settings.System.ALERT_SLIDER_NOTIFICATIONS;

    private static final int MSG_DIALOG_SHOW = 1;
    private static final int MSG_DIALOG_DISMISS = 2;
    private static final int MSG_RESET_SCHEDULE = 3;
    private static final int MSG_STATE_CHANGE = 4;

    private static final int RINGER_MODE_NORMAL = AudioManager.RINGER_MODE_NORMAL;
    private static final int RINGER_MODE_SILENT = AudioManager.RINGER_MODE_SILENT;
    private static final int RINGER_MODE_VIBRATE = AudioManager.RINGER_MODE_VIBRATE;

    private static final int POSITION_TOP = 0;
    private static final int POSITION_MIDDLE = 1;
    private static final int POSITION_BOTTOM = 2;

    // Slider
    private static final int MODE_TOTAL_SILENCE = 600;
    private static final int MODE_ALARMS_ONLY = 601;
    private static final int MODE_PRIORITY_ONLY = 602;
    private static final int MODE_NONE = 603;
    private static final int MODE_VIBRATE = 604;
    private static final int MODE_RING = 605;
    // Arbitrary value which hopefully doesn't conflict with upstream anytime soon
    private static final int MODE_SILENT = 620;
    private static final int MODE_FLASHLIGHT_ON = 621;
    private static final int MODE_FLASHLIGHT_OFF = 622;
    private static final int MODE_FLASHLIGHT_BLINK = 623;
    private static final int MODE_BRIGHTNESS_BRIGHT = 630;
    private static final int MODE_BRIGHTNESS_DARK = 631;
    private static final int MODE_BRIGHTNESS_AUTO = 632;
    private static final int MODE_ROTATION_AUTO = 640;
    private static final int MODE_ROTATION_0 = 641;
    private static final int MODE_ROTATION_90 = 642;
    private static final int MODE_ROTATION_270 = 643;
    private static final int MODE_REFRESH_AUTO = 644;
    private static final int MODE_REFRESH_MIN = 645;
    private static final int MODE_REFRESH_MAX = 646;
    private static final int MODE_EXTRADIM_OFF = 647;
    private static final int MODE_EXTRADIM_MIN = 648;
    private static final int MODE_EXTRADIM_MID = 649;
    private static final int MODE_EXTRADIM_MAX = 650;
    private static final int MODE_NIGHTLIGHT_OFF = 651;
    private static final int MODE_NIGHTLIGHT_MIN = 652;
    private static final int MODE_NIGHTLIGHT_MID = 653;
    private static final int MODE_NIGHTLIGHT_MAX = 654;
    private static final int MODE_COLORSPACE_VIVID = 655;
    private static final int MODE_COLORSPACE_NATURAL = 656;
    private static final int MODE_COLORSPACE_CINEMATIC = 657;
    private static final int MODE_COLORSPACE_BRILLIANT = 658;

    private static final String EXTRA_SLIDER_POSITION = "position";
    private static final String EXTRA_SLIDER_POSITION_VALUE = "position_value";

    private static final int TRI_STATE_UI_POSITION_LEFT = 0;
    private static final int TRI_STATE_UI_POSITION_RIGHT = 1;

    private static final long DIALOG_TIMEOUT = 2000;
    private static final long DIALOG_DELAY = 300;

    private Context mContext;
    private final VolumeDialogController mVolumeDialogController;
    private final Callbacks mVolumeDialogCallback = new Callbacks() {
        @Override
        public void onShowRequested(int reason, boolean keyguardLocked, int lockTaskModeState) { }

        @Override
        public void onDismissRequested(int reason) { }

        @Override
        public void onScreenOff() { }

        @Override
        public void onStateChanged(State state) { }

        @Override
        public void onLayoutDirectionChanged(int layoutDirection) { }

        @Override
        public void onShowVibrateHint() { }

        @Override
        public void onShowSilentHint() { }

        @Override
        public void onShowSafetyWarning(int flags) { }

        @Override
        public void onAccessibilityModeChanged(Boolean showA11yStream) { }

        @Override
        public void onCaptionComponentStateChanged(
                Boolean isComponentEnabled, Boolean fromTooltip) {}

        @Override
        public void onConfigurationChanged() {
            updateTriStateLayout();
        }
    };

    private int mDensity;
    private Dialog mDialog;
    private int mDialogPosition;
    private ViewGroup mDialogView;
    private final H mHandler;
    private UserActivityListener mListener;
    OrientationEventListener mOrientationListener;
    private int mOrientationType = 0;
    private boolean mShowing = false;
    private int mBackgroundColor = 0;
    private ImageView mTriStateIcon;
    private TextView mTriStateText;
    private int mTriStateMode = -1;
    private int mPosition = -1;
    private int mPositionValue = -1;
    private Window mWindow;
    private LayoutParams mWindowLayoutParams;
    private int mWindowType;
    private String mIntentAction;
    private boolean mIntentActionSupported;
    private boolean mRingModeChanged;
    private boolean mSliderPositionChanged;
    private boolean mAlertSliderNotification;

    private final BroadcastReceiver mSliderStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mAlertSliderNotification) {
                mRingModeChanged = false;
                mSliderPositionChanged = false;
                return;
            }

            String action = intent.getAction();
            if (mIntentActionSupported && action.equals(mIntentAction)) {
                Bundle extras = intent.getExtras();
                mPosition = extras.getInt(EXTRA_SLIDER_POSITION);
                mPositionValue = extras.getInt(EXTRA_SLIDER_POSITION_VALUE);
                mHandler.sendEmptyMessage(MSG_DIALOG_DISMISS);
                mHandler.sendEmptyMessage(MSG_STATE_CHANGE);
                mSliderPositionChanged = true;
                Log.d(TAG, "received slider position " + mPosition
                                    + " with value " + mPositionValue);
            } else if (!mIntentActionSupported && action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                mHandler.sendEmptyMessage(MSG_DIALOG_DISMISS);
                mHandler.sendEmptyMessage(MSG_STATE_CHANGE);
                mRingModeChanged = true;
            }

            if (mRingModeChanged || mSliderPositionChanged) {
                mRingModeChanged = false;
                mSliderPositionChanged = false;
                if (mTriStateMode != -1) {
                    mHandler.sendEmptyMessageDelayed(MSG_DIALOG_SHOW, (long) DIALOG_DELAY);
               }
            }
        }
    };

    private final class H extends Handler {
        private TriStateUiControllerImpl mUiController;

        public H(TriStateUiControllerImpl uiController) {
            super(Looper.getMainLooper());
            mUiController = uiController;
        }

        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DIALOG_SHOW:
                    mUiController.handleShow();
                    return;
                case MSG_DIALOG_DISMISS:
                    mUiController.handleDismiss();
                    return;
                case MSG_RESET_SCHEDULE:
                    mUiController.handleResetTimeout();
                    return;
                case MSG_STATE_CHANGE:
                    mUiController.handleStateChanged();
                    return;
                default:
                    return;
            }
        }
    }

    public TriStateUiControllerImpl(Context context) {
        mContext =
                new ContextThemeWrapper(context, R.style.Theme_SystemUI_QuickSettings);
        mHandler = new H(this);
        mOrientationListener = new OrientationEventListener(mContext, 3) {
            @Override
            public void onOrientationChanged(int orientation) {
                checkOrientationType();
            }
        };
        mVolumeDialogController = (VolumeDialogController) Dependency.get(VolumeDialogController.class);
        mIntentAction = context.getResources().getString(com.android.internal.R.string.config_alertSliderIntent);
        mIntentActionSupported = mIntentAction != null && !mIntentAction.isEmpty();

        IntentFilter filter = new IntentFilter();
        if (mIntentActionSupported) {
            filter.addAction(mIntentAction);
        } else {
            filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        }
        mContext.registerReceiver(mSliderStateReceiver, filter);

        final TunerService tunerService = Dependency.get(TunerService.class);
        tunerService.addTunable(this, ALERT_SLIDER_NOTIFICATIONS);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case ALERT_SLIDER_NOTIFICATIONS:
                mAlertSliderNotification
                        = TunerService.parseIntegerSwitch(newValue, true);
                mHandler.sendEmptyMessage(MSG_DIALOG_DISMISS);
                break;
            default:
                break;
        }
    }

    @Override
    public void onUiModeChanged() {
        mContext.getTheme().applyStyle(mContext.getThemeResId(), true);
        initDialog();
    }

    private void checkOrientationType() {
        Display display = DisplayManagerGlobal.getInstance().getRealDisplay(0);
        if (display != null) {
            int rotation = display.getRotation();
            if (rotation != mOrientationType) {
                mOrientationType = rotation;
                updateTriStateLayout();
            }
        }
    }

    public void init(int windowType, UserActivityListener listener) {
        mWindowType = windowType;
        mDensity = mContext.getResources().getConfiguration().densityDpi;
        mListener = listener;
        ((ConfigurationController) Dependency.get(ConfigurationController.class)).addCallback(this);
        mVolumeDialogController.addCallback(mVolumeDialogCallback, mHandler);
        initDialog();
    }

    public void destroy() {
        ((ConfigurationController) Dependency.get(ConfigurationController.class)).removeCallback(this);
        mVolumeDialogController.removeCallback(mVolumeDialogCallback);
        mContext.unregisterReceiver(mSliderStateReceiver);
    }

    private void initDialog() {
        mDialog = new Dialog(mContext, R.style.Theme_SystemUI_QuickSettings);
        mShowing = false;
        mWindow = mDialog.getWindow();
        mWindow.requestFeature(Window.FEATURE_NO_TITLE);
        mWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        mWindow.clearFlags(LayoutParams.FLAG_DIM_BEHIND
                | LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        mWindow.addFlags(LayoutParams.FLAG_NOT_FOCUSABLE
                | LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | LayoutParams.FLAG_NOT_TOUCH_MODAL
                | LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | LayoutParams.FLAG_HARDWARE_ACCELERATED);
        mWindow.setType(LayoutParams.TYPE_VOLUME_OVERLAY);
        mWindow.setWindowAnimations(com.android.internal.R.style.Animation_Toast);
        mDialog.setCanceledOnTouchOutside(false);
        mWindowLayoutParams = mWindow.getAttributes();
        mWindowLayoutParams.type = mWindowType;
        mWindowLayoutParams.format = -3;
        mWindowLayoutParams.setTitle(TriStateUiControllerImpl.class.getSimpleName());
        mWindowLayoutParams.gravity = 53;
        mWindowLayoutParams.y = mDialogPosition;
        mWindow.setAttributes(mWindowLayoutParams);
        mWindow.setSoftInputMode(LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        mDialog.setContentView(R.layout.tri_state_dialog);
        mDialogView = (ViewGroup) mDialog.findViewById(R.id.tri_state_layout);
        mTriStateIcon = (ImageView) mDialog.findViewById(R.id.tri_state_icon);
        mTriStateText = (TextView) mDialog.findViewById(R.id.tri_state_text);
    }

    private void registerOrientationListener(boolean enable) {
        if (mOrientationListener.canDetectOrientation() && enable) {
            Log.v(TAG, "Can detect orientation");
            mOrientationListener.enable();
            return;
        }
        Log.v(TAG, "Cannot detect orientation");
        mOrientationListener.disable();
    }

    private void updateTriStateLayout() {
        if (mContext != null) {
            int iconId = 0;
            int textId = 0;
            int bg = 0;
            Resources res = mContext.getResources();
            if (res != null) {
                int positionY;
                int positionY2 = mWindowLayoutParams.y;
                int positionX = mWindowLayoutParams.x;
                int gravity = mWindowLayoutParams.gravity;
                switch (mTriStateMode) {
                    case MODE_RING:
                    case MODE_NONE:
                    case RINGER_MODE_NORMAL:
                        iconId = R.drawable.ic_volume_ringer;
                        textId = R.string.volume_ringer_status_normal;
                        break;
                    case MODE_VIBRATE:
                    case RINGER_MODE_VIBRATE:
                        iconId = R.drawable.ic_volume_ringer_vibrate;
                        textId = R.string.volume_ringer_status_vibrate;
                        break;
                    case MODE_SILENT:
                    case RINGER_MODE_SILENT:
                        iconId = R.drawable.ic_volume_ringer_mute;
                        textId = R.string.volume_ringer_status_silent;
                        break;
                    case MODE_PRIORITY_ONLY:
                        iconId = R.drawable.ic_qs_dnd_on;
                        textId = R.string.volume_ringer_priority_only;
                        break;
                    case MODE_ALARMS_ONLY:
                        iconId = R.drawable.ic_qs_dnd_on;
                        textId = R.string.volume_ringer_alarms_only;
                        break;
                    case MODE_TOTAL_SILENCE:
                        iconId = R.drawable.ic_qs_dnd_on;
                        textId = R.string.volume_ringer_dnd;
                        break;
                    case MODE_FLASHLIGHT_ON:
                        iconId = R.drawable.ic_tristate_flashlight;
                        textId = R.string.tristate_flashlight_on;
                        break;
                    case MODE_FLASHLIGHT_OFF:
                        iconId = R.drawable.ic_tristate_flashlight_off;
                        textId = R.string.tristate_flashlight_off;
                        break;
                    case MODE_FLASHLIGHT_BLINK:
                        iconId = R.drawable.ic_tristate_flashlight;
                        textId = R.string.tristate_flashlight_blink;
                        break;
                    case MODE_BRIGHTNESS_BRIGHT:
                        iconId = R.drawable.ic_tristate_brightness_bright;
                        textId = R.string.tristate_brightness_bright;
                        break;
                    case MODE_BRIGHTNESS_DARK:
                        iconId = R.drawable.ic_tristate_brightness_dark;
                        textId = R.string.tristate_brightness_dark;
                        break;
                    case MODE_BRIGHTNESS_AUTO:
                        iconId = R.drawable.ic_tristate_brightness_auto;
                        textId = R.string.tristate_brightness_auto;
                        break;
                    case MODE_ROTATION_AUTO:
                        iconId = R.drawable.ic_tristate_rotate_auto;
                        textId = R.string.tristate_rotation_auto;
                        break;
                    case MODE_ROTATION_0:
                        iconId = R.drawable.ic_tristate_rotate_portrait;
                        textId = R.string.tristate_rotation_0;
                        break;
                    case MODE_ROTATION_90:
                        iconId = R.drawable.ic_tristate_rotate_landscape;
                        textId = R.string.tristate_rotation_90;
                        break;
                    case MODE_ROTATION_270:
                        iconId = R.drawable.ic_tristate_rotate_landscape;
                        textId = R.string.tristate_rotation_270;
                        break;
                    case MODE_REFRESH_AUTO:
                        iconId = R.drawable.ic_tristate_refresh;
                        textId = R.string.tristate_refresh_auto;
                        break;
                    case MODE_REFRESH_MIN:
                        iconId = R.drawable.ic_tristate_refresh;
                        textId = R.string.tristate_refresh_min;
                        break;
                    case MODE_REFRESH_MAX:
                        iconId = R.drawable.ic_tristate_refresh;
                        textId = R.string.tristate_refresh_max;
                        break;
                    case MODE_EXTRADIM_OFF:
                        iconId = R.drawable.ic_tristate_extradim;
                        textId = R.string.tristate_extradim_off;
                        break;
                    case MODE_EXTRADIM_MIN:
                        iconId = R.drawable.ic_tristate_extradim;
                        textId = R.string.tristate_extradim_min;
                        break;
                    case MODE_EXTRADIM_MID:
                        iconId = R.drawable.ic_tristate_extradim;
                        textId = R.string.tristate_extradim_mid;
                        break;
                    case MODE_EXTRADIM_MAX:
                        iconId = R.drawable.ic_tristate_extradim;
                        textId = R.string.tristate_extradim_max;
                        break;
                    case MODE_NIGHTLIGHT_OFF:
                        iconId = R.drawable.ic_tristate_nightlight;
                        textId = R.string.tristate_nightlight_off;
                        break;
                    case MODE_NIGHTLIGHT_MIN:
                        iconId = R.drawable.ic_tristate_nightlight;
                        textId = R.string.tristate_nightlight_min;
                        break;
                    case MODE_NIGHTLIGHT_MID:
                        iconId = R.drawable.ic_tristate_nightlight;
                        textId = R.string.tristate_nightlight_mid;
                        break;
                    case MODE_NIGHTLIGHT_MAX:
                        iconId = R.drawable.ic_tristate_nightlight;
                        textId = R.string.tristate_nightlight_max;
                        break;
                    case MODE_COLORSPACE_VIVID:
                        iconId = R.drawable.ic_tristate_colorspace;
                        textId = R.string.tristate_colorspace_vivid;
                        break;
                    case MODE_COLORSPACE_NATURAL:
                        iconId = R.drawable.ic_tristate_colorspace;
                        textId = R.string.tristate_colorspace_natural;
                        break;
                    case MODE_COLORSPACE_CINEMATIC:
                        iconId = R.drawable.ic_tristate_colorspace;
                        textId = R.string.tristate_colorspace_cinematic;
                        break;
                    case MODE_COLORSPACE_BRILLIANT:
                        iconId = R.drawable.ic_tristate_colorspace;
                        textId = R.string.tristate_colorspace_brilliant;
                        break;
                }

                int triStatePos = res.getInteger(com.android.internal.R.integer.config_alertSliderLocation);
                boolean isTsKeyRight = true;
                if (triStatePos == TRI_STATE_UI_POSITION_LEFT) {
                    isTsKeyRight = false;
                } else if (triStatePos == TRI_STATE_UI_POSITION_RIGHT) {
                    isTsKeyRight = true;
                }
                switch (mOrientationType) {
                    case ROTATION_90:
                        if (isTsKeyRight) {
                            gravity = 51;
                        } else {
                            gravity = 83;
                        }
                        positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_deep_land);
                        if (isTsKeyRight) {
                            positionY2 += res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                        }
                        if (mPosition == POSITION_TOP) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_l);
                        } else if (mPosition == POSITION_MIDDLE) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position_l);
                        } else if (mPosition == POSITION_BOTTOM) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position_l);
                        } else if (!mIntentActionSupported && mTriStateMode == RINGER_MODE_SILENT) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_l);
                        } else if (!mIntentActionSupported && mTriStateMode == RINGER_MODE_VIBRATE) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position_l);
                        } else if (!mIntentActionSupported && mTriStateMode == RINGER_MODE_NORMAL) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position_l);
                        }
                        bg = R.drawable.dialog_tri_state_middle_bg;
                        break;
                    case ROTATION_180:
                        if (isTsKeyRight) {
                            gravity = 83;
                        } else {
                            gravity = 85;
                        }
                        positionX = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_deep);
                        positionY = res.getDimensionPixelSize(R.dimen.status_bar_height);
                        if (mPosition == POSITION_TOP) {
                            positionY += res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position);
                            bg = !isTsKeyRight ? R.drawable.right_dialog_tri_state_down_bg : R.drawable.left_dialog_tri_state_down_bg;
                        } else if (mPosition == POSITION_MIDDLE) {
                            positionY += res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position);
                            bg = R.drawable.dialog_tri_state_middle_bg;
                        } else if (mPosition == POSITION_BOTTOM) {
                            positionY += res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position);
                            bg = !isTsKeyRight ? R.drawable.right_dialog_tri_state_up_bg : R.drawable.left_dialog_tri_state_up_bg;
                        } else if (!mIntentActionSupported && mTriStateMode == RINGER_MODE_SILENT) {
                            positionY += res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position);
                            bg = !isTsKeyRight ? R.drawable.right_dialog_tri_state_down_bg : R.drawable.left_dialog_tri_state_down_bg;
                        } else if (!mIntentActionSupported && mTriStateMode == RINGER_MODE_VIBRATE) {
                            positionY += res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position);
                            bg = R.drawable.dialog_tri_state_middle_bg;
                        } else if (!mIntentActionSupported && mTriStateMode == RINGER_MODE_NORMAL) {
                            positionY += res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position);
                            bg = !isTsKeyRight ? R.drawable.right_dialog_tri_state_up_bg : R.drawable.left_dialog_tri_state_up_bg;
                        }
                        positionY2 = positionY;
                        break;
                    case ROTATION_270:
                        if (isTsKeyRight) {
                            gravity = 85;
                        } else {
                            gravity = 53;
                        }
                        positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_deep_land);
                        if (!isTsKeyRight) {
                            positionY2 += res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                        }
                        if (mPosition == POSITION_TOP) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_l);
                        } else if (mPosition == POSITION_MIDDLE) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position_l);
                        } else if (mPosition == POSITION_BOTTOM) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position_l);
                        } else if (!mIntentActionSupported && mTriStateMode == RINGER_MODE_SILENT) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_l);
                        } else if (!mIntentActionSupported && mTriStateMode == RINGER_MODE_VIBRATE) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position_l);
                        } else if (!mIntentActionSupported && mTriStateMode == RINGER_MODE_NORMAL) {
                            positionX = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position_l);
                        }
                        bg = R.drawable.dialog_tri_state_middle_bg;
                        break;
                    default:
                        if (isTsKeyRight) {
                            gravity = 53;
                        } else {
                            gravity = 51;
                        }
                        positionX = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position_deep);
                        if (mPosition == POSITION_TOP) {
                            positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position);
                            bg = isTsKeyRight ? R.drawable.right_dialog_tri_state_up_bg : R.drawable.left_dialog_tri_state_up_bg;
                        } else if (mPosition == POSITION_MIDDLE) {
                            positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position);
                            bg = R.drawable.dialog_tri_state_middle_bg;
                        } else if (mPosition == POSITION_BOTTOM) {
                            positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position);
                            bg = isTsKeyRight ? R.drawable.right_dialog_tri_state_down_bg : R.drawable.left_dialog_tri_state_down_bg;
                        } else if (!mIntentActionSupported && mTriStateMode == RINGER_MODE_SILENT) {
                            positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_up_dialog_position);
                            bg = isTsKeyRight ? R.drawable.right_dialog_tri_state_up_bg : R.drawable.left_dialog_tri_state_up_bg;
                        } else if (!mIntentActionSupported && mTriStateMode == RINGER_MODE_VIBRATE) {
                            positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_middle_dialog_position);
                            bg = R.drawable.dialog_tri_state_middle_bg;
                        } else if (!mIntentActionSupported && mTriStateMode == RINGER_MODE_NORMAL) {
                            positionY2 = res.getDimensionPixelSize(R.dimen.tri_state_down_dialog_position);
                            bg = isTsKeyRight ? R.drawable.right_dialog_tri_state_down_bg : R.drawable.left_dialog_tri_state_down_bg;
                        }
                        positionY2 += res.getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
                        break;
                }
                if (mTriStateMode != -1) {
                    if (mTriStateIcon != null && iconId != 0) {
                        mTriStateIcon.setImageResource(iconId);
                    }
                    if (mTriStateText != null && textId != 0) {
                        String inputText = res.getString(textId);
                        if (inputText != null && mTriStateText.length() == inputText.length()) {
                            StringBuilder sb = new StringBuilder();
                            sb.append(inputText);
                            sb.append(" ");
                            inputText = sb.toString();
                        }
                        mTriStateText.setText(inputText);
                    }
                    if (mDialogView != null && bg != 0) {
                        mDialogView.setBackgroundDrawable(res.getDrawable(bg));
                        mBackgroundColor = getAttrColor(R.attr.offStateColor);
                        mDialogView.setBackgroundTintList(ColorStateList.valueOf(mBackgroundColor));
                    }
                    mDialogPosition = positionY2;
                }

                final float scaleFactor = DisplayUtils.getScaleFactor(mContext);

                positionY = res.getDimensionPixelSize(R.dimen.tri_state_dialog_padding);
                mWindowLayoutParams.gravity = gravity;
                mWindowLayoutParams.y = (int) ((positionY2 - positionY) * scaleFactor);
                mWindowLayoutParams.x = (int) ((positionX - positionY) * scaleFactor);
                mWindow.setAttributes(mWindowLayoutParams);
                mHandler.sendEmptyMessageDelayed(MSG_RESET_SCHEDULE, DIALOG_TIMEOUT);
            }
        }
    }

    private void handleShow() {
        mHandler.removeMessages(MSG_DIALOG_SHOW);
        if (!mShowing) {
            registerOrientationListener(true);
            checkOrientationType();
            mShowing = true;
            mDialog.show();
            if (mListener != null) {
                mListener.onTriStateUserActivity();
            }
            mHandler.sendEmptyMessageDelayed(MSG_RESET_SCHEDULE, DIALOG_TIMEOUT);
        }
    }

    private void handleDismiss() {
        mHandler.removeMessages(MSG_DIALOG_DISMISS);
        if (mShowing) {
            registerOrientationListener(false);
            mShowing = false;
            mDialog.dismiss();
        }
    }

    private void handleStateChanged() {
        mHandler.removeMessages(MSG_STATE_CHANGE);
        if (mIntentActionSupported && mPositionValue != mTriStateMode) {
            mTriStateMode = mPositionValue;
            updateTriStateLayout();
            if (mListener != null) {
                mListener.onTriStateUserActivity();
            }
        } else if (!mIntentActionSupported) {
            AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            int ringerMode = am.getRingerModeInternal();
            if (ringerMode != mTriStateMode) {
                mTriStateMode = ringerMode;
                updateTriStateLayout();
                if (mListener != null) {
                    mListener.onTriStateUserActivity();
                }
            }
        }
    }

    public void handleResetTimeout() {
        mHandler.removeMessages(MSG_RESET_SCHEDULE);
        mHandler.sendEmptyMessage(MSG_DIALOG_DISMISS);
        if (mListener != null) {
            mListener.onTriStateUserActivity();
        }
    }

    @Override
    public void onDensityOrFontScaleChanged() {
        mHandler.sendEmptyMessage(MSG_DIALOG_DISMISS);
        initDialog();
        updateTriStateLayout();
    }

    public int getAttrColor(int attr) {
        TypedArray ta = mContext.obtainStyledAttributes(new int[]{attr});
        int colorAccent = ta.getColor(0, 0);
        ta.recycle();
        return colorAccent;
    }
}
