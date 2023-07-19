/**
 * Copyright (C) 2014 The TeamEos Project
 * Copyright (C) 2016 The DirtyUnicorns Project
 *
 * @author Randall Rushing <randall.rushing@gmail.com>
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
 *
 * Single tap, double tap, and long press logic for hardware key events
 * Monitors user configuration changes, sets sane defaults, executes actions,
 * lets PhoneWindowManager know relevant configuration changes. This handler
 * fully consumes all key events it watches
 *
 */

package com.android.server.policy;

import java.util.ArrayList;

import com.android.internal.policy.KeyInterceptionInfo;
import com.android.internal.util.hwkeys.ActionConstants;
import com.android.internal.util.hwkeys.ActionHandler;
import com.android.internal.util.hwkeys.ActionUtils;
import com.android.internal.util.hwkeys.Config;
import com.android.internal.util.hwkeys.Config.ActionConfig;
import com.android.internal.util.hwkeys.Config.ButtonConfig;
import com.android.server.LocalServices;
import com.android.server.wm.WindowManagerInternal;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.IBinder;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import static android.view.WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;

public class HardkeyActionHandler {
    private interface ActionReceiver {
        public void onActionDispatched(HardKeyButton button, String task);
    }

    private static final String TAG = HardkeyActionHandler.class.getSimpleName();

    WindowManagerInternal mWindowManagerInternal;

    // messages to PWM to do some actions we can't really do here
    public static final int MSG_FIRE_HOME = 7102;
    public static final int MSG_UPDATE_MENU_KEY = 7106;
    public static final int MSG_DO_HAPTIC_FB = 7107;

    // fire rocket boosters
    private static final int BOOST_LEVEL = 1000 * 1000;

    public static final int KEY_MASK_HOME = 0x01;
    public static final int KEY_MASK_BACK = 0x02;
    public static final int KEY_MASK_MENU = 0x04;
    public static final int KEY_MASK_ASSIST = 0x08;
    public static final int KEY_MASK_APP_SWITCH = 0x10;
    public static final int KEY_MASK_CAMERA = 0x20;
    public static final int KEY_MASK_VOLUME = 0x40;

    // lock our configuration changes
    private final Object mLock = new Object();

    private HardKeyButton mBackButton;
    private HardKeyButton mHomeButton;
    private HardKeyButton mRecentButton;
    private HardKeyButton mMenuButton;
    private HardKeyButton mAssistButton;

    // Behavior of HOME button during incomming call ring.
    // (See Settings.Secure.RING_HOME_BUTTON_BEHAVIOR.)
//    int mRingHomeBehavior;

    private ActionReceiver mActionReceiver = new ActionReceiver() {
        @Override
        public void onActionDispatched(HardKeyButton button, String task) {
            if (task.equals(ActionHandler.SYSTEMUI_TASK_HOME)) {
                mHandler.sendEmptyMessage(MSG_FIRE_HOME);
                return;
            } else if (task.equals(ActionHandler.SYSTEMUI_TASK_SCREENOFF)) {
                // can't consume UP event if screen is off, do it manually
                button.setPressed(false);
                button.setWasConsumed(false);
            }
            ActionHandler.performTask(mContext, task);
        }
    };

    private int mDeviceHardwareKeys;

    private SettingsObserver mObserver;
    private Handler mHandler;
//    private PowerManager mPm;
    private Context mContext;
    private boolean mHwKeysDisabled = false;

    public HardkeyActionHandler(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
//        mPm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);

        mDeviceHardwareKeys = ActionUtils.getInt(context, "config_deviceHardwareKeys",
                ActionUtils.PACKAGE_ANDROID);

        mWindowManagerInternal = LocalServices.getService(WindowManagerInternal.class);

        mBackButton = new HardKeyButton(mActionReceiver, handler);
        mHomeButton = new HardKeyButton(mActionReceiver, handler);
        mRecentButton = new HardKeyButton(mActionReceiver, handler);
        mMenuButton = new HardKeyButton(mActionReceiver, handler);
        mAssistButton = new HardKeyButton(mActionReceiver, handler);

        mObserver = new SettingsObserver(mHandler);
        mObserver.observe();
    }

    void fireBooster(HardKeyButton button) {
//        if (!button.isDoubleTapPending()) {
//            mPm.cpuBoost(BOOST_LEVEL);
//        }
    }

    public boolean isHwKeysDisabled() {
        return mHwKeysDisabled;
    }

    private boolean filterDisabledKey(int keyCode) {
        return mHwKeysDisabled && (keyCode == KeyEvent.KEYCODE_HOME
                || keyCode == KeyEvent.KEYCODE_MENU
                || keyCode == KeyEvent.KEYCODE_APP_SWITCH
                || keyCode == KeyEvent.KEYCODE_ASSIST
                || keyCode == KeyEvent.KEYCODE_BACK);
    }

    public boolean handleKeyEvent(IBinder focusedToken, int keyCode, int repeatCount, boolean down,
            boolean canceled,
            boolean longPress, boolean keyguardOn) {
        if (filterDisabledKey(keyCode)) {
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_HOME) {
            if (!down && mHomeButton.isPressed()) {
                mHomeButton.setPressed(false);
                if (mHomeButton.wasConsumed()) {
                    mHomeButton.setWasConsumed(false);
                    return true;
                }

                if (!mHomeButton.keyHasDoubleTapRecents()) {
                    ActionHandler.cancelPreloadRecentApps();
                }

                if (canceled) {
                    return true;
                }
/*
                // If an incoming call is ringing, HOME is totally disabled.
                // (The user is already on the InCallUI at this point,
                // and his ONLY options are to answer or reject the call.)
                TelecomManager telecomManager = getTelecommService();
                if (telecomManager != null && telecomManager.isRinging()) {
                    if ((mRingHomeBehavior
                            & Settings.Secure.RING_HOME_BUTTON_BEHAVIOR_ANSWER) != 0) {
                        Log.i(TAG, "Answering with HOME button.");
                        telecomManager.acceptRingingCall();
                        return true;
                    } else {
                        Log.i(TAG, "Ignoring HOME; there's a ringing incoming call.");
                        return true;
                    }
                }
*/

                // If an incoming call is ringing, HOME is totally disabled.
                // (The user is already on the InCallUI at this point,
                // and his ONLY options are to answer or reject the call.)
                TelecomManager telecomManager = getTelecommService();
                if (telecomManager != null && telecomManager.isRinging()) {
                    Log.i(TAG, "Ignoring HOME; there's a ringing incoming call.");
                    return true;
                }

                if (mHomeButton.isDoubleTapEnabled()) {
                    mHomeButton.cancelDTTimeout();
                    mHomeButton.setDoubleTapPending(true);
                    mHomeButton.postDTTimeout();
                    return true;
                }

                mHomeButton.fireSingleTap();
                return true;
            }

            final KeyInterceptionInfo info =
                    mWindowManagerInternal.getKeyInterceptionInfoFromToken(focusedToken);
            if (info != null) {
                // If a system window has focus, then it doesn't make sense
                // right now to interact with applications.
                if (info.layoutParamsType == TYPE_KEYGUARD_DIALOG
                        || (info.layoutParamsType == TYPE_NOTIFICATION_SHADE
                        && keyguardOn)) {
                    // the "app" is keyguard, so give it the key
                    return false;
                }
                for (int t : WINDOW_TYPES_WHERE_HOME_DOESNT_WORK) {
                    if (info.layoutParamsType == t) {
                        // don't do anything, but also don't pass it to the app
                        return true;
                    }
                }
            }

            if (!down) {
                return true;
            }

            if (repeatCount == 0) {
                mHomeButton.setPressed(true);
                fireBooster(mHomeButton);
                if (mHomeButton.isDoubleTapPending()) {
                    mHomeButton.setDoubleTapPending(false);
                    mHomeButton.cancelDTTimeout();
                    mHomeButton.fireDoubleTap();
                    mHomeButton.setWasConsumed(true);
                } else if (mHomeButton.keyHasLongPressRecents()
                        || mHomeButton.keyHasDoubleTapRecents()) {
                    ActionHandler.preloadRecentApps();
                }
            } else if (longPress) {
                if (!keyguardOn
                        && !mHomeButton.wasConsumed()
                        && mHomeButton.isLongTapEnabled()) {
                    mHomeButton.setPressed(true);
                    if (!mHomeButton.keyHasLongPressRecents()) {
                        ActionHandler.cancelPreloadRecentApps();
                    }
                    mHandler.sendEmptyMessage(MSG_DO_HAPTIC_FB);
                    mHomeButton.fireLongPress();
                    mHomeButton.setWasConsumed(true);
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (!down && mMenuButton.isPressed()) {
                mMenuButton.setPressed(false);

                if (mMenuButton.wasConsumed()) {
                    mMenuButton.setWasConsumed(false);
                    return true;
                }

                if (!mMenuButton.keyHasDoubleTapRecents()) {
                    ActionHandler.cancelPreloadRecentApps();
                }

                if (canceled || keyguardOn) {
                    return true;
                }

                if (mMenuButton.isDoubleTapEnabled()) {
                    mMenuButton.setDoubleTapPending(true);
                    mMenuButton.cancelDTTimeout();
                    mMenuButton.postDTTimeout();
                    return true;
                }

                if (!mMenuButton.keyHasSingleTapRecent()) {
                    ActionHandler.cancelPreloadRecentApps();
                }

                mMenuButton.fireSingleTap();
                return true;
            }

            if (!down) {
                return true;
            }

            if (repeatCount == 0) {
                mMenuButton.setPressed(true);
                fireBooster(mMenuButton);
                if (mMenuButton.isDoubleTapPending()) {
                    mMenuButton.setDoubleTapPending(false);
                    mMenuButton.cancelDTTimeout();
                    mMenuButton.fireDoubleTap();
                    mMenuButton.setWasConsumed(true);
                } else if (mMenuButton.keyHasLongPressRecents()
                        || mMenuButton.keyHasDoubleTapRecents()
                        || mMenuButton.keyHasSingleTapRecent()) {
                    ActionHandler.preloadRecentApps();
                }
            } else if (longPress) {
                if (!keyguardOn
                        && !mMenuButton.wasConsumed()
                        && mMenuButton.isLongTapEnabled()) {
                    mMenuButton.setPressed(true);
                    if (!mMenuButton.keyHasLongPressRecents()) {
                        ActionHandler.cancelPreloadRecentApps();
                    }
                    mHandler.sendEmptyMessage(MSG_DO_HAPTIC_FB);
                    mMenuButton.fireLongPress();
                    mMenuButton.setWasConsumed(true);
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            if (!down && mRecentButton.isPressed()) {
                mRecentButton.setPressed(false);

                if (mRecentButton.wasConsumed()) {
                    mRecentButton.setWasConsumed(false);
                    return true;
                }

                if (!mRecentButton.keyHasDoubleTapRecents()) {
                    ActionHandler.cancelPreloadRecentApps();
                }

                if (canceled || keyguardOn) {
                    return true;
                }

                if (mRecentButton.isDoubleTapEnabled()) {
                    mRecentButton.setDoubleTapPending(true);
                    mRecentButton.cancelDTTimeout();
                    mRecentButton.postDTTimeout();
                    return true;
                }

                if (!mRecentButton.keyHasSingleTapRecent()) {
                    ActionHandler.cancelPreloadRecentApps();
                }

                mRecentButton.fireSingleTap();
                return true;
            }

            if (!down) {
                return true;
            }

            if (repeatCount == 0) {
                mRecentButton.setPressed(true);
                fireBooster(mRecentButton);
                if (mRecentButton.isDoubleTapPending()) {
                    mRecentButton.setDoubleTapPending(false);
                    mRecentButton.cancelDTTimeout();
                    mRecentButton.fireDoubleTap();
                    mRecentButton.setWasConsumed(true);
                } else if (mRecentButton.keyHasLongPressRecents()
                        || mRecentButton.keyHasDoubleTapRecents()
                        || mRecentButton.keyHasSingleTapRecent()) {
                    ActionHandler.preloadRecentApps();
                }
            } else if (longPress) {
                if (!keyguardOn
                        && !mRecentButton.wasConsumed()
                        && mRecentButton.isLongTapEnabled()) {
                    mRecentButton.setPressed(true);
                    if (!mRecentButton.keyHasLongPressRecents()) {
                        ActionHandler.cancelPreloadRecentApps();
                    }
                    mHandler.sendEmptyMessage(MSG_DO_HAPTIC_FB);
                    mRecentButton.fireLongPress();
                    mRecentButton.setWasConsumed(true);
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_ASSIST) {
            if (!down && mAssistButton.isPressed()) {
                mAssistButton.setPressed(false);

                if (mAssistButton.wasConsumed()) {
                    mAssistButton.setWasConsumed(false);
                    return true;
                }

                if (!mAssistButton.keyHasDoubleTapRecents()) {
                    ActionHandler.cancelPreloadRecentApps();
                }

                if (canceled || keyguardOn) {
                    return true;
                }

                if (mAssistButton.isDoubleTapEnabled()) {
                    mAssistButton.setDoubleTapPending(true);
                    mAssistButton.cancelDTTimeout();
                    mAssistButton.postDTTimeout();
                    return true;
                }

                if (!mAssistButton.keyHasSingleTapRecent()) {
                    ActionHandler.cancelPreloadRecentApps();
                }
                mAssistButton.fireSingleTap();
                return true;
            }

            if (!down) {
                return true;
            }

            if (repeatCount == 0) {
                mAssistButton.setPressed(true);
                fireBooster(mAssistButton);
                if (mAssistButton.isDoubleTapPending()) {
                    mAssistButton.setDoubleTapPending(false);
                    mAssistButton.cancelDTTimeout();
                    mAssistButton.fireDoubleTap();
                    mAssistButton.setWasConsumed(true);
                } else if (mAssistButton.keyHasLongPressRecents()
                        || mAssistButton.keyHasDoubleTapRecents()
                        || mAssistButton.keyHasSingleTapRecent()) {
                    ActionHandler.preloadRecentApps();
                }
            } else if (longPress) {
                if (!keyguardOn
                        && !mAssistButton.wasConsumed()
                        && mAssistButton.isLongTapEnabled()) {
                    mAssistButton.setPressed(true);
                    if (!mAssistButton.keyHasLongPressRecents()) {
                        ActionHandler.cancelPreloadRecentApps();
                    }
                    mHandler.sendEmptyMessage(MSG_DO_HAPTIC_FB);
                    mAssistButton.fireLongPress();
                    mAssistButton.setWasConsumed(true);
                }
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!down && mBackButton.isPressed()) {
                mBackButton.setPressed(false);

                if (mBackButton.wasConsumed()) {
                    mBackButton.setWasConsumed(false);
                    return true;
                }

                if (!mBackButton.keyHasDoubleTapRecents()) {
                    ActionHandler.cancelPreloadRecentApps();
                }

                if (canceled || keyguardOn) {
                    return true;
                }

                if (mBackButton.isDoubleTapEnabled()) {
                    mBackButton.setDoubleTapPending(true);
                    mBackButton.cancelDTTimeout();
                    mBackButton.postDTTimeout();
                    return true;
                }

                mBackButton.fireSingleTap();
                return true;
            }

            if (!down) {
                return true;
            }

            if (repeatCount == 0) {
                mBackButton.setPressed(true);
                fireBooster(mBackButton);
                if (mBackButton.isDoubleTapPending()) {
                    mBackButton.setDoubleTapPending(false);
                    mBackButton.cancelDTTimeout();
                    mBackButton.fireDoubleTap();
                    mBackButton.setWasConsumed(true);
                } else if (mBackButton.keyHasLongPressRecents()
                        || mBackButton.keyHasDoubleTapRecents()) {
                    ActionHandler.preloadRecentApps();
                }
            } else if (longPress) {
                if (!keyguardOn
                        && !mBackButton.wasConsumed()) {
                    mBackButton.setPressed(true);
/*                    if (ActionHandler.isLockTaskOn()) {
                        ActionHandler.turnOffLockTask();
                        mHandler.sendEmptyMessage(MSG_DO_HAPTIC_FB);
                        mBackButton.setWasConsumed(true);
                    } else {
*/
                        if (mBackButton.isLongTapEnabled()) {
                            if (!mBackButton.keyHasLongPressRecents()) {
                                ActionHandler.cancelPreloadRecentApps();
                            }
                            mBackButton.fireLongPress();
                            mHandler.sendEmptyMessage(MSG_DO_HAPTIC_FB);
                            mBackButton.setWasConsumed(true);
                        }
//                    }
                }
            }
            return true;
        }
        return false;
    }

    private class HardKeyButton {
        private ButtonConfig mConfig;
        private ActionReceiver mActionReceiver;
        private Handler mHandler;

        private boolean mDoubleTapPending = false;
        private boolean mIsPressed = false;
        private boolean mWasConsumed = false;

        public HardKeyButton(ActionReceiver receiver, Handler handler) {
            mHandler = handler;
            mActionReceiver = receiver;
        }

        void setConfig(ButtonConfig config) {
            mConfig = config;
        }

        final Runnable mDoubleTapTimeout = new Runnable() {
            public void run() {
                if (mDoubleTapPending) {
                    mDoubleTapPending = false;
                    if (!keyHasSingleTapRecent()) {
                        ActionHandler.cancelPreloadRecentApps();
                    }
                    mActionReceiver.onActionDispatched(HardKeyButton.this, mConfig.getActionConfig(ActionConfig.PRIMARY).getAction());
                }
            }
        };

        final Runnable mSTRunnable = new Runnable() {
            public void run() {
                mActionReceiver.onActionDispatched(HardKeyButton.this, mConfig.getActionConfig(ActionConfig.PRIMARY).getAction());
            }
        };

        final Runnable mDTRunnable = new Runnable() {
            public void run() {
                mActionReceiver.onActionDispatched(HardKeyButton.this, mConfig.getActionConfig(ActionConfig.THIRD).getAction());
            }
        };

        final Runnable mLPRunnable = new Runnable() {
            public void run() {
                mActionReceiver.onActionDispatched(HardKeyButton.this, mConfig.getActionConfig(ActionConfig.SECOND).getAction());
            }
        };

        boolean keyHasSingleTapRecent() {
            return mConfig.getActionConfig(ActionConfig.PRIMARY).isActionRecents();
        }

        boolean keyHasLongPressRecents() {
            return mConfig.getActionConfig(ActionConfig.SECOND).isActionRecents();
        }

        boolean keyHasDoubleTapRecents() {
            return mConfig.getActionConfig(ActionConfig.THIRD).isActionRecents();
        }

        boolean keyHasMenuAction() {
            return ActionHandler.SYSTEMUI_TASK_MENU.equals(mConfig.getActionConfig(ActionConfig.PRIMARY).getAction())
                    || ActionHandler.SYSTEMUI_TASK_MENU.equals(mConfig.getActionConfig(ActionConfig.SECOND).getAction())
                    || ActionHandler.SYSTEMUI_TASK_MENU.equals(mConfig.getActionConfig(ActionConfig.THIRD).getAction());
        }

        boolean isDoubleTapEnabled() {
            return !mConfig.getActionConfig(ActionConfig.THIRD).hasNoAction();
        }

        boolean isLongTapEnabled() {
            return !mConfig.getActionConfig(ActionConfig.SECOND).hasNoAction();
        }

        void setDoubleTapPending(boolean pending) {
            mDoubleTapPending = pending;
        }

        boolean isDoubleTapPending() {
            return mDoubleTapPending;
        }

        void setPressed(boolean pressed) {
            mIsPressed = pressed;
        }

        boolean isPressed() {
            return mIsPressed;
        }

        void setWasConsumed(boolean consumed) {
            mWasConsumed = consumed;
        }

        boolean wasConsumed() {
            return mWasConsumed;
        }

        void fireDoubleTap() {
            mHandler.post(mDTRunnable);
        }

        void fireLongPress() {
            mHandler.post(mLPRunnable);
        }

        void fireSingleTap() {
            mHandler.post(mSTRunnable);
        }

        void cancelDTTimeout() {
            mHandler.removeCallbacks(mDoubleTapTimeout);
        }

        void postDTTimeout() {
            mHandler.postDelayed(mDoubleTapTimeout, ViewConfiguration.getDoubleTapTimeout());
        }
    }

    private TelecomManager getTelecommService() {
        return (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
    }

    private static final int[] WINDOW_TYPES_WHERE_HOME_DOESNT_WORK = {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
    };

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            // Observe all users' changes
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(
                    Settings.Secure.getUriFor(ActionConstants.getDefaults(ActionConstants.HWKEYS)
                            .getUri()), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.HARDWARE_KEYS_DISABLE), false, this,
                    UserHandle.USER_ALL);
            updateKeyAssignments();
        }

        @Override
        public void onChange(boolean selfChange) {
            updateKeyAssignments();
        }
    }

    private void updateKeyAssignments() {
        ContentResolver cr = mContext.getContentResolver();
        synchronized (mLock) {
            mHwKeysDisabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                    Settings.System.HARDWARE_KEYS_DISABLE, 0,
                    UserHandle.USER_CURRENT) != 0;

            final boolean hasMenu = (mDeviceHardwareKeys & KEY_MASK_MENU) != 0;
            final boolean hasHome = (mDeviceHardwareKeys & KEY_MASK_HOME) != 0;
            final boolean hasAssist = (mDeviceHardwareKeys & KEY_MASK_ASSIST) != 0;
            final boolean hasAppSwitch = (mDeviceHardwareKeys & KEY_MASK_APP_SWITCH) != 0;

            ArrayList<ButtonConfig> configs = Config.getConfig(mContext,
                    ActionConstants.getDefaults(ActionConstants.HWKEYS));

            ButtonConfig config = Config.getButtonConfigFromTag(configs, ActionConstants.Hwkeys.BACK_BUTTON_TAG);
            mBackButton.setConfig(config);

            config = Config.getButtonConfigFromTag(configs, ActionConstants.Hwkeys.HOME_BUTTON_TAG);
            mHomeButton.setConfig(config);

            config = Config.getButtonConfigFromTag(configs, ActionConstants.Hwkeys.OVERVIEW_BUTTON_TAG);
            mRecentButton.setConfig(config);

            config = Config.getButtonConfigFromTag(configs, ActionConstants.Hwkeys.MENU_BUTTON_TAG);
            mMenuButton.setConfig(config);

            config = Config.getButtonConfigFromTag(configs, ActionConstants.Hwkeys.ASSIST_BUTTON_TAG);
            mAssistButton.setConfig(config);

            boolean hasMenuKeyEnabled = false;

            if (hasHome) {
                hasMenuKeyEnabled = mHomeButton.keyHasMenuAction();
            }
            if (hasMenu) {
                hasMenuKeyEnabled |= mMenuButton.keyHasMenuAction();
            }
            if (hasAssist) {
                hasMenuKeyEnabled |= mAssistButton.keyHasMenuAction();
            }
            if (hasAppSwitch) {
                hasMenuKeyEnabled |= mRecentButton.keyHasMenuAction();
            }
            hasMenuKeyEnabled |= mBackButton.keyHasMenuAction();

            // let PWM know to update menu key settings
            Message msg = mHandler.obtainMessage(MSG_UPDATE_MENU_KEY);
            msg.arg1 = hasMenuKeyEnabled ? 1 : 0;
            mHandler.sendMessage(msg);

//            mRingHomeBehavior = Settings.Secure.getIntForUser(cr,
//                    Settings.Secure.RING_HOME_BUTTON_BEHAVIOR,
//                    Settings.Secure.RING_HOME_BUTTON_BEHAVIOR_DEFAULT,
//                    UserHandle.USER_CURRENT);
        }
    }
}
