/*
 * Copyright (C) 2015 The TeamEos Project
 * Copyright (C) 2016-2017 The DirtyUnicorns Project
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
 * Launches actions assigned to widgets. Creates bundles of state based
 * on the type of action passed.
 *
 */

package com.android.internal.util.hwkeys;

import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityTaskManager;
import android.app.IActivityManager;
import android.app.SearchManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.hardware.input.InputManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.media.session.MediaSessionLegacyHelper;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.service.wallpaper.WallpaperService;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;
import android.view.IWindowManager;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.WindowManagerGlobal;
//import android.view.WindowManagerPolicyControl;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.util.hwkeys.Config.ActionConfig;

public class ActionHandler {
    public static String TAG = ActionHandler.class.getSimpleName();

    public static final String SYSTEM_PREFIX = "task";
    public static final String SYSTEMUI = "com.android.systemui";

    // track and filter special actions
    public static final String TASK_IME = "task_ime";
    public static final String TASK_MEDIA = "task_media";
    public static final String TASK_SOUNDMODE = "task_soundmode";

    public static final String SYSTEMUI_TASK_NO_ACTION = "task_no_action";
    public static final String SYSTEMUI_TASK_SETTINGS_PANEL = "task_settings_panel";
    public static final String SYSTEMUI_TASK_NOTIFICATION_PANEL = "task_notification_panel";
    public static final String SYSTEMUI_TASK_SCREENSHOT = "task_screenshot";
    public static final String SYSTEMUI_TASK_REGION_SCREENSHOT = "task_region_screenshot";
    public static final String SYSTEMUI_TASK_SCREENRECORD = "task_screenrecord";
    // public static final String SYSTEMUI_TASK_AUDIORECORD =
    // "task_audiorecord";
    public static final String SYSTEMUI_TASK_EXPANDED_DESKTOP = "task_expanded_desktop";
    public static final String SYSTEMUI_TASK_SCREENOFF = "task_screenoff";
    public static final String SYSTEMUI_TASK_KILL_PROCESS = "task_killcurrent";
    public static final String SYSTEMUI_TASK_GOOGLE_ASSISTANT = "task_google_assistant";
    public static final String SYSTEMUI_TASK_POWER_MENU = "task_powermenu";
    public static final String SYSTEMUI_TASK_TORCH = "task_torch";
    public static final String SYSTEMUI_TASK_CAMERA = "task_camera";
    public static final String SYSTEMUI_TASK_BT = "task_bt";
    public static final String SYSTEMUI_TASK_WIFI = "task_wifi";
    public static final String SYSTEMUI_TASK_WIFIAP = "task_wifiap";
    public static final String SYSTEMUI_TASK_RECENTS = "task_recents";
    public static final String SYSTEMUI_TASK_LAST_APP = "task_last_app";
    public static final String SYSTEMUI_TASK_APP_SEARCH = "task_app_search";
    public static final String SYSTEMUI_TASK_MENU = "task_menu";
    public static final String SYSTEMUI_TASK_BACK = "task_back";
    public static final String SYSTEMUI_TASK_HOME = "task_home";
    public static final String SYSTEMUI_TASK_IME_SWITCHER = "task_ime_switcher";
    public static final String SYSTEMUI_TASK_IME_NAVIGATION_LEFT = "task_ime_navigation_left";
    public static final String SYSTEMUI_TASK_IME_NAVIGATION_RIGHT = "task_ime_navigation_right";
    public static final String SYSTEMUI_TASK_IME_NAVIGATION_UP = "task_ime_navigation_up";
    public static final String SYSTEMUI_TASK_IME_NAVIGATION_DOWN = "task_ime_navigation_down";
    public static final String SYSTEMUI_TASK_MEDIA_PREVIOUS = "task_media_previous";
    public static final String SYSTEMUI_TASK_MEDIA_NEXT = "task_media_next";
    public static final String SYSTEMUI_TASK_MEDIA_PLAY_PAUSE = "task_media_play_pause";
    public static final String SYSTEMUI_TASK_SOUNDMODE_VIB = "task_soundmode_vib";
    public static final String SYSTEMUI_TASK_SOUNDMODE_SILENT = "task_soundmode_silent";
    public static final String SYSTEMUI_TASK_SOUNDMODE_VIB_SILENT = "task_soundmode_vib_silent";
    public static final String SYSTEMUI_TASK_WAKE_DEVICE = "task_wake_device";
    public static final String SYSTEMUI_TASK_STOP_SCREENPINNING = "task_stop_screenpinning";
    public static final String SYSTEMUI_TASK_CLEAR_NOTIFICATIONS = "task_clear_notifications";
    public static final String SYSTEMUI_TASK_VOLUME_PANEL = "task_volume_panel";
    public static final String SYSTEMUI_TASK_SPLIT_SCREEN = "task_split_screen";
    public static final String SYSTEMUI_TASK_ONE_HANDED_MODE_LEFT = "task_one_handed_mode_left";
    public static final String SYSTEMUI_TASK_ONE_HANDED_MODE_RIGHT = "task_one_handed_mode_right";
    public static final String SYSTEMUI_TASK_ASSISTANT_SOUND_SEARCH = "task_assistant_sound_search";

    public static final String INTENT_SHOW_POWER_MENU = "action_handler_show_power_menu";
    public static final String INTENT_TOGGLE_SCREENRECORD = "action_handler_toggle_screenrecord";
    public static final String INTENT_SCREENSHOT = "action_take_screenshot";
    public static final String INTENT_REGION_SCREENSHOT = "action_take_region_screenshot";

    // remove actions from here as they come back on deck
    static final Set<String> sDisabledActions = new HashSet<String>();
    static {
        sDisabledActions.add(SYSTEMUI_TASK_SCREENRECORD);
        sDisabledActions.add(SYSTEMUI_TASK_EXPANDED_DESKTOP);
        sDisabledActions.add(SYSTEMUI_TASK_ONE_HANDED_MODE_LEFT);
        sDisabledActions.add(SYSTEMUI_TASK_ONE_HANDED_MODE_RIGHT);
        // we need to make this more reliable when the user tap the partial screenshot button
        // quickly and more times
        sDisabledActions.add(SYSTEMUI_TASK_REGION_SCREENSHOT);
        sDisabledActions.add(SYSTEMUI_TASK_STOP_SCREENPINNING);
        sDisabledActions.add(SYSTEMUI_TASK_ASSISTANT_SOUND_SEARCH);
        // sDisabledActions.add(SYSTEMUI_TASK_POWER_MENU);
    }

    static enum SystemAction {
        NoAction(SYSTEMUI_TASK_NO_ACTION,  SYSTEMUI, "label_action_no_action", "ic_sysbar_no_action"),
        SettingsPanel(SYSTEMUI_TASK_SETTINGS_PANEL, SYSTEMUI, "label_action_settings_panel", "ic_sysbar_settings_panel"),
        NotificationPanel(SYSTEMUI_TASK_NOTIFICATION_PANEL, SYSTEMUI, "label_action_notification_panel", "ic_sysbar_notification_panel"),
        Screenshot(SYSTEMUI_TASK_SCREENSHOT, SYSTEMUI, "label_action_screenshot", "ic_sysbar_screenshot"),
        RegionScreenshot(SYSTEMUI_TASK_REGION_SCREENSHOT, SYSTEMUI, "label_action_region_screenshot", "ic_sysbar_region_screenshot"),
        Screenrecord(SYSTEMUI_TASK_SCREENRECORD, SYSTEMUI, "label_action_screenrecord", "ic_sysbar_record_screen"),
        ExpandedDesktop(SYSTEMUI_TASK_EXPANDED_DESKTOP, SYSTEMUI, "label_action_expanded_desktop", "ic_sysbar_expanded_desktop"),
        ScreenOff(SYSTEMUI_TASK_SCREENOFF, SYSTEMUI, "label_action_screen_off", "ic_sysbar_screen_off"),
        KillApp(SYSTEMUI_TASK_KILL_PROCESS, SYSTEMUI, "label_action_force_close_app", "ic_sysbar_killtask"),
        GoogleAssistant(SYSTEMUI_TASK_GOOGLE_ASSISTANT, SYSTEMUI, "label_action_google_assistant", "ic_sysbar_google_assistant"),
        InAppSearch(SYSTEMUI_TASK_APP_SEARCH, SYSTEMUI, "label_action_in_app_search", "ic_sysbar_in_app_search"),
        Flashlight(SYSTEMUI_TASK_TORCH, SYSTEMUI, "label_action_flashlight", "ic_sysbar_torch"),
        Bluetooth(SYSTEMUI_TASK_BT, SYSTEMUI, "label_action_bluetooth", "ic_sysbar_bt"),
        WiFi(SYSTEMUI_TASK_WIFI, SYSTEMUI, "label_action_wifi", "ic_sysbar_wifi"),
        Hotspot(SYSTEMUI_TASK_WIFIAP, SYSTEMUI, "label_action_hotspot", "ic_sysbar_hotspot"),
        LastApp(SYSTEMUI_TASK_LAST_APP, SYSTEMUI, "label_action_last_app", "ic_sysbar_lastapp"),
        Overview(SYSTEMUI_TASK_RECENTS, SYSTEMUI, "label_action_overview", "ic_sysbar_recent_hw"),
        PowerMenu(SYSTEMUI_TASK_POWER_MENU, SYSTEMUI, "label_action_power_menu", "ic_sysbar_power_menu"),
        Menu(SYSTEMUI_TASK_MENU, SYSTEMUI, "label_action_menu", "ic_sysbar_menu_hw"),
        Back(SYSTEMUI_TASK_BACK, SYSTEMUI, "label_action_back", "ic_sysbar_back_hw"),
        Home(SYSTEMUI_TASK_HOME, SYSTEMUI, "label_action_home", "ic_sysbar_home_hw"),
        Ime(SYSTEMUI_TASK_IME_SWITCHER, SYSTEMUI, "label_action_ime_switcher", "ic_ime_switcher_smartbar"),
        StopScreenPinning(SYSTEMUI_TASK_STOP_SCREENPINNING, SYSTEMUI, "label_action_stop_screenpinning", "ic_smartbar_screen_pinning_off"),
        ImeArrowDown(SYSTEMUI_TASK_IME_NAVIGATION_DOWN, SYSTEMUI, "label_action_ime_down", "ic_sysbar_ime_down"),
        ImeArrowLeft(SYSTEMUI_TASK_IME_NAVIGATION_LEFT, SYSTEMUI, "label_action_ime_left", "ic_sysbar_ime_left"),
        ImeArrowRight(SYSTEMUI_TASK_IME_NAVIGATION_RIGHT, SYSTEMUI, "label_action_ime_right", "ic_sysbar_ime_right"),
        ImeArrowUp(SYSTEMUI_TASK_IME_NAVIGATION_UP, SYSTEMUI, "label_action_ime_up", "ic_sysbar_ime_up"),
        ClearNotifications(SYSTEMUI_TASK_CLEAR_NOTIFICATIONS, SYSTEMUI, "label_action_clear_notifications", "ic_sysbar_clear_notifications"),
        VolumePanel(SYSTEMUI_TASK_VOLUME_PANEL, SYSTEMUI, "label_action_volume_panel", "ic_sysbar_volume_panel"),
        SplitScreen(SYSTEMUI_TASK_SPLIT_SCREEN, SYSTEMUI, "label_action_split_screen", "ic_sysbar_docked_hw"),
        OneHandedModeLeft(SYSTEMUI_TASK_ONE_HANDED_MODE_LEFT, SYSTEMUI, "label_action_one_handed_mode_left", "ic_sysbar_one_handed_mode_left"),
        OneHandedModeRight(SYSTEMUI_TASK_ONE_HANDED_MODE_RIGHT, SYSTEMUI, "label_action_one_handed_mode_right", "ic_sysbar_one_handed_mode_right"),
        MediaArrowLeft(SYSTEMUI_TASK_MEDIA_PREVIOUS, SYSTEMUI, "label_action_media_left", "ic_skip_previous"),
        MediaArrowRight(SYSTEMUI_TASK_MEDIA_NEXT, SYSTEMUI, "label_action_media_right", "ic_skip_next"),
        AssistantSoundSearch(SYSTEMUI_TASK_ASSISTANT_SOUND_SEARCH, SYSTEMUI, "label_action_assistant_sound_search", "ic_assistant_sound_search");

        String mAction;
        String mResPackage;
        String mLabelRes;
        String mIconRes;
        String mDarkIconRes;

        private SystemAction(String action, String resPackage, String labelRes,  String iconRes) {
            mAction = action;
            mResPackage = resPackage;
            mLabelRes = labelRes;
            mIconRes = iconRes;
            mDarkIconRes = iconRes + "_dark";
        }

        private ActionConfig create(Context ctx) {
            return new ActionConfig(ctx, mAction);
        }
    }

    /*
     * Enumerated system actions with label and drawable support
     */
    static SystemAction[] systemActions = new SystemAction[] {
            SystemAction.NoAction, SystemAction.SettingsPanel,
            SystemAction.NotificationPanel, SystemAction.Screenshot,
            SystemAction.ScreenOff, SystemAction.KillApp,
            SystemAction.Flashlight, SystemAction.Bluetooth,
            SystemAction.WiFi, SystemAction.Hotspot,
            SystemAction.LastApp, SystemAction.PowerMenu,
            SystemAction.Overview,SystemAction.Menu,
            SystemAction.Back, SystemAction.GoogleAssistant,
            SystemAction.Home, SystemAction.ExpandedDesktop,
            SystemAction.Screenrecord, SystemAction.Ime,
            SystemAction.StopScreenPinning, SystemAction.ImeArrowDown,
            SystemAction.ImeArrowLeft, SystemAction.ImeArrowRight,
            SystemAction.ImeArrowUp, SystemAction.InAppSearch,
            SystemAction.VolumePanel, SystemAction.ClearNotifications,
            SystemAction.SplitScreen, SystemAction.RegionScreenshot,
            SystemAction.OneHandedModeLeft, SystemAction.OneHandedModeRight,
            SystemAction.MediaArrowLeft, SystemAction.MediaArrowRight,
            SystemAction.AssistantSoundSearch
    };

    public static class ActionIconResources {
        Drawable[] mDrawables;
        Drawable[] mDarkDrawables;
        Map<String, Integer> mIndexMap;

        public ActionIconResources(Resources res) {
            mDrawables = new Drawable[systemActions.length];
            mDarkDrawables = new Drawable[systemActions.length];
            mIndexMap = new HashMap<String, Integer>();
            for (int i = 0; i < systemActions.length; i++) {
                mIndexMap.put(systemActions[i].mAction, i);
                mDrawables[i] = ActionUtils.getDrawable(res, systemActions[i].mIconRes,
                        systemActions[i].mResPackage);
                mDarkDrawables[i] = ActionUtils.getDrawable(res, systemActions[i].mDarkIconRes,
                        systemActions[i].mResPackage);
            }
        }

        public void updateResources(Resources res) {
            for (int i = 0; i < mDrawables.length; i++) {
                mDrawables[i] = ActionUtils.getDrawable(res, systemActions[i].mIconRes,
                        systemActions[i].mResPackage);
                mDarkDrawables[i] = ActionUtils.getDrawable(res, systemActions[i].mDarkIconRes,
                        systemActions[i].mResPackage);
            }
        }

        public Drawable getActionDrawable(String action) {
            return mDrawables[mIndexMap.get(action)];
        }

        public Drawable getDarkActionDrawable(String action) {
            return mDarkDrawables[mIndexMap.get(action)];
        }
    }

    /*
     * Default list to display in an action picker
     * Filter by device capabilities and actions used internally
     * but we don't really want as assignable
     */
    public static ArrayList<ActionConfig> getSystemActions(Context context) {
        ArrayList<ActionConfig> bundle = new ArrayList<ActionConfig>();
        for (int i = 0; i < systemActions.length; i++) {
            ActionConfig c = systemActions[i].create(context);
            String action = c.getAction();
            if (sDisabledActions.contains(action)) {
                continue;
            }
            if (TextUtils.equals(action, SYSTEMUI_TASK_STOP_SCREENPINNING)
                    || TextUtils.equals(action, SYSTEMUI_TASK_IME_NAVIGATION_DOWN)
                    || TextUtils.equals(action, SYSTEMUI_TASK_IME_NAVIGATION_LEFT)
                    || TextUtils.equals(action, SYSTEMUI_TASK_IME_NAVIGATION_RIGHT)
                    || TextUtils.equals(action, SYSTEMUI_TASK_IME_NAVIGATION_UP)
                    || TextUtils.equals(action, SYSTEMUI_TASK_IME_SWITCHER)
                    || TextUtils.equals(action, SYSTEMUI_TASK_MEDIA_PREVIOUS)
                    || TextUtils.equals(action, SYSTEMUI_TASK_MEDIA_NEXT)) {
                continue;
            } else if (TextUtils.equals(action, SYSTEMUI_TASK_WIFIAP)
                    && !ActionUtils.deviceSupportsMobileData(context)) {
                continue;
            } else if (TextUtils.equals(action, SYSTEMUI_TASK_BT)
                    && !ActionUtils.deviceSupportsBluetooth()) {
                continue;
            } else if (TextUtils.equals(action, SYSTEMUI_TASK_TORCH)
                 && !ActionUtils.deviceSupportsFlashLight(context)) {
                continue;
            } else if (TextUtils.equals(action, SYSTEMUI_TASK_CAMERA)
                    && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
                continue;
            } else if (TextUtils.equals(action, SYSTEMUI_TASK_SCREENRECORD)) {
                if (!ActionUtils.getBoolean(context, "config_enableScreenrecordChord",
                        ActionUtils.PACKAGE_ANDROID)) {
                    continue;
                }
            }
            bundle.add(c);
        }
        Collections.sort(bundle);
        return bundle;
    }

    private static final class StatusBarHelper {
        private static boolean isPreloaded = false;
        private static IStatusBarService mService = null;

        private static IStatusBarService getStatusBarService() {
            synchronized (StatusBarHelper.class) {
                if (mService == null) {
                    try {
                        mService = IStatusBarService.Stub.asInterface(
                                ServiceManager.getService("statusbar"));
                    } catch (Exception e) {
                    }
                }
                return mService;
            }
        }

        private static void toggleFlashlight() {
            IStatusBarService service = getStatusBarService();
            try {
                service.toggleCameraFlash();
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }

        private static void toggleRecentsApps() {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    sendCloseSystemWindows("recentapps");
                    service.toggleRecentApps();
                } catch (RemoteException e) {
                    return;
                }
                isPreloaded = false;
            }
        }

        private static void cancelPreloadRecentApps() {
            if (isPreloaded == false)
                return;
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.cancelPreloadRecentApps();
                } catch (Exception e) {
                    return;
                }
            }
            isPreloaded = false;
        }

        private static void preloadRecentApps() {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.preloadRecentApps();
                } catch (RemoteException e) {
                    isPreloaded = false;
                    return;
                }
                isPreloaded = true;
            }
        }

        private static void expandNotificationPanel() {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.expandNotificationsPanel();
                } catch (RemoteException e) {
                }
            }
        }

        private static void expandSettingsPanel() {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.expandSettingsPanel(null);
                } catch (RemoteException e) {
                }
            }
        }

        private static void fireGoogleAssistant() {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.startAssist(new Bundle());
                } catch (RemoteException e) {
                }
            }
        }

        private static void splitScreen() {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.toggleSplitScreen();
                } catch (RemoteException e) {
                }
            }
        }
/*
        private static void fireIntentAfterKeyguard(Intent intent) {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.showCustomIntentAfterKeyguard(intent);
                } catch (RemoteException e) {
                }
            }
        }
*/
        private static void clearAllNotifications() {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.onClearAllNotifications(ActivityManager.getCurrentUser());
                } catch (RemoteException e) {
                }
            }
        }
    }

    public static void toggleRecentApps() {
        StatusBarHelper.toggleRecentsApps();
    }

    public static void cancelPreloadRecentApps() {
        StatusBarHelper.cancelPreloadRecentApps();
    }

    public static void preloadRecentApps() {
        StatusBarHelper.preloadRecentApps();
    }
/*
    public static void performTaskFromKeyguard(Context ctx, String action) {
        // null: throw it out
        if (action == null) {
            return;
        }
        // not a system action, should be intent
        if (!action.startsWith(SYSTEM_PREFIX)) {
            Intent intent = ActionUtils.getIntent(action);
            if (intent == null) {
                return;
            }
            StatusBarHelper.fireIntentAfterKeyguard(intent);
        } else {
            performTask(ctx, action);
        }
    }
*/

    public static void performTask(Context context, String action) {
        // null: throw it out
        if (action == null) {
            return;
        }
        if (sDisabledActions.contains(action)) {
            return;
        }
        // not a system action, should be intent
        if (!action.startsWith(SYSTEM_PREFIX)) {
            Intent intent = ActionUtils.getIntent(action);
            if (intent == null) {
                return;
            }
            launchActivity(context, intent);
            return;
        } else if (action.equals(SYSTEMUI_TASK_NO_ACTION)) {
            return;
        } else if (action.equals(SYSTEMUI_TASK_KILL_PROCESS)) {
            killProcess(context);
            return;
        } else if (action.equals(SYSTEMUI_TASK_SCREENSHOT)) {
            sendCommandToWindowManager(new Intent(INTENT_SCREENSHOT));
            return;
        } else if (action.equals(SYSTEMUI_TASK_REGION_SCREENSHOT)) {
            sendCommandToWindowManager(new Intent(INTENT_REGION_SCREENSHOT));
            return;
        } else if (action.equals(SYSTEMUI_TASK_SCREENRECORD)) {
            sendCommandToWindowManager(new Intent(INTENT_TOGGLE_SCREENRECORD));
            return;
            // } else if (action.equals(SYSTEMUI_TASK_AUDIORECORD)) {
            // takeAudiorecord();
        } else if (action.equals(SYSTEMUI_TASK_EXPANDED_DESKTOP)) {
            // toggleExpandedDesktop(context);
            return;
        } else if (action.equals(SYSTEMUI_TASK_SCREENOFF)) {
            screenOff(context);
            return;
        } else if (action.equals(SYSTEMUI_TASK_WAKE_DEVICE)) {
            PowerManager powerManager =
                    (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (!powerManager.isScreenOn()) {
                powerManager.wakeUp(SystemClock.uptimeMillis());
            }
            return;
        } else if (action.equals(SYSTEMUI_TASK_GOOGLE_ASSISTANT)) {
            StatusBarHelper.fireGoogleAssistant();
            return;
        } else if (action.equals(SYSTEMUI_TASK_POWER_MENU)) {
            sendCommandToWindowManager(new Intent(INTENT_SHOW_POWER_MENU));
            return;
        } else if (action.equals(SYSTEMUI_TASK_TORCH)) {
            StatusBarHelper.toggleFlashlight();
            return;
        } else if (action.equals(SYSTEMUI_TASK_CAMERA)) {
            launchCamera(context);
            return;
        } else if (action.equals(SYSTEMUI_TASK_WIFI)) {
            toggleWifi(context);
            return;
        } else if (action.equals(SYSTEMUI_TASK_WIFIAP)) {
            toggleWifiAP(context);
            return;
        } else if (action.equals(SYSTEMUI_TASK_BT)) {
            toggleBluetooth();
            return;
        } else if (action.equals(SYSTEMUI_TASK_RECENTS)) {
            toggleRecentApps();
            return;
        } else if (action.equals(SYSTEMUI_TASK_LAST_APP)) {
            ActionUtils.switchToLastApp(context);
            return;
        } else if (action.equals(SYSTEMUI_TASK_SETTINGS_PANEL)) {
            StatusBarHelper.expandSettingsPanel();
            return;
        } else if (action.equals(SYSTEMUI_TASK_NOTIFICATION_PANEL)) {
            StatusBarHelper.expandNotificationPanel();
            return;
        } else if (action.equals(SYSTEMUI_TASK_APP_SEARCH)) {
            triggerVirtualKeypress(context, KeyEvent.KEYCODE_SEARCH);
            return;
        } else if (action.equals(SYSTEMUI_TASK_MENU)) {
            triggerVirtualKeypress(context, KeyEvent.KEYCODE_MENU);
            return;
        } else if (action.equals(SYSTEMUI_TASK_BACK)) {
            triggerVirtualKeypress(context, KeyEvent.KEYCODE_BACK);
            return;
        } else if (action.equals(SYSTEMUI_TASK_HOME)) {
            triggerVirtualKeypress(context, KeyEvent.KEYCODE_HOME);
            return;
/*        } else if (action.equals(SYSTEMUI_TASK_IME_SWITCHER)) {
            ((InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showInputMethodPicker(true /* showAuxiliarySubtypes);*/
//            return;
/*        } else if (action.equals(SYSTEMUI_TASK_STOP_SCREENPINNING)) {
            turnOffLockTask();
            return;
*/
        } else if (action.equals(SYSTEMUI_TASK_IME_NAVIGATION_RIGHT)) {
            triggerVirtualKeypress(context, KeyEvent.KEYCODE_DPAD_RIGHT);
            return;
        } else if (action.equals(SYSTEMUI_TASK_IME_NAVIGATION_UP)) {
            triggerVirtualKeypress(context, KeyEvent.KEYCODE_DPAD_UP);
            return;
        } else if (action.equals(SYSTEMUI_TASK_IME_NAVIGATION_DOWN)) {
            triggerVirtualKeypress(context, KeyEvent.KEYCODE_DPAD_DOWN);
            return;
        } else if (action.equals(SYSTEMUI_TASK_IME_NAVIGATION_LEFT)) {
            triggerVirtualKeypress(context, KeyEvent.KEYCODE_DPAD_LEFT);
            return;
        } else if (action.equals(SYSTEMUI_TASK_MEDIA_PREVIOUS)) {
            dispatchMediaKeyWithWakeLock(KeyEvent.KEYCODE_MEDIA_PREVIOUS, context);
            return;
        } else if (action.equals(SYSTEMUI_TASK_MEDIA_NEXT)) {
            dispatchMediaKeyWithWakeLock(KeyEvent.KEYCODE_MEDIA_NEXT, context);
            return;
        } else if (action.equals(SYSTEMUI_TASK_MEDIA_PLAY_PAUSE)) {
            dispatchMediaKeyWithWakeLock(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, context);
            return;
        } else if (action.equals(SYSTEMUI_TASK_SOUNDMODE_VIB)) {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && ActivityManagerNative.isSystemReady()) {
                if (am.getRingerMode() != AudioManager.RINGER_MODE_VIBRATE) {
                    am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                    Vibrator vib = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                    if (vib != null) {
                        vib.vibrate(50);
                    }
                } else {
                    am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                    ToneGenerator tg = new ToneGenerator(
                            AudioManager.STREAM_NOTIFICATION,
                            (int) (ToneGenerator.MAX_VOLUME * 0.85));
                    if (tg != null) {
                        tg.startTone(ToneGenerator.TONE_PROP_BEEP);
                    }
                }
            }
            return;
        } else if (action.equals(SYSTEMUI_TASK_SOUNDMODE_SILENT)) {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && ActivityManagerNative.isSystemReady()) {
                if (am.getRingerMode() != AudioManager.RINGER_MODE_SILENT) {
                    am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                } else {
                    am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                    ToneGenerator tg = new ToneGenerator(
                            AudioManager.STREAM_NOTIFICATION,
                            (int) (ToneGenerator.MAX_VOLUME * 0.85));
                    if (tg != null) {
                        tg.startTone(ToneGenerator.TONE_PROP_BEEP);
                    }
                }
            }
            return;
        } else if (action.equals(SYSTEMUI_TASK_SOUNDMODE_VIB_SILENT)) {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && ActivityManagerNative.isSystemReady()) {
                if (am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL) {
                    am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
                    Vibrator vib = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
                    if (vib != null) {
                        vib.vibrate(50);
                    }
                } else if (am.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE) {
                    am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
                } else {
                    am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                    ToneGenerator tg = new ToneGenerator(
                            AudioManager.STREAM_NOTIFICATION,
                            (int) (ToneGenerator.MAX_VOLUME * 0.85));
                    if (tg != null) {
                        tg.startTone(ToneGenerator.TONE_PROP_BEEP);
                    }
                }
            }
            return;
        } else if (action.equals(SYSTEMUI_TASK_CLEAR_NOTIFICATIONS)) {
            StatusBarHelper.clearAllNotifications();
            return;
        } else if (action.equals(SYSTEMUI_TASK_VOLUME_PANEL)) {
            volumePanel(context);
            return;
        } else if (action.equals(SYSTEMUI_TASK_SPLIT_SCREEN)) {
            StatusBarHelper.splitScreen();
            return;
        } else if (action.equals(SYSTEMUI_TASK_ONE_HANDED_MODE_LEFT)) {
//            toggleOneHandedMode(context, "left");
            return;
        } else if (action.equals(SYSTEMUI_TASK_ONE_HANDED_MODE_RIGHT)) {
//            toggleOneHandedMode(context, "right");
            return;
        } else if (action.equals(SYSTEMUI_TASK_ASSISTANT_SOUND_SEARCH)) {
            startAssistantSoundSearch(context);
            return;
        }
    }

    public static boolean isActionKeyEvent(String action) {
        if (action.equals(SYSTEMUI_TASK_HOME)
                || action.equals(SYSTEMUI_TASK_BACK)
//                || action.equals(SYSTEMUI_TASK_SEARCH)
                || action.equals(SYSTEMUI_TASK_MENU)
//                || action.equals(ActionConstants.ACTION_MENU_BIG)
                || action.equals(SYSTEMUI_TASK_NO_ACTION)) {
            return true;
        }
        return false;
    }

    private static void launchActivity(Context context, Intent intent) {
        try {
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        } catch (Exception e) {
            Log.i(TAG, "Unable to launch activity " + e);
        }
    }

    private static void sendCloseSystemWindows(String reason) {
        if (ActivityManagerNative.isSystemReady()) {
            try {
                ActivityManagerNative.getDefault().closeSystemDialogs(reason);
            } catch (RemoteException e) {
            }
        }
    }

/*
    private static void toggleExpandedDesktop(Context context) {
        ContentResolver cr = context.getContentResolver();
        String newVal = "";
        String currentVal = Settings.Global.getString(cr, Settings.Global.POLICY_CONTROL);
        if (currentVal == null) {
            currentVal = newVal;
        }
        if ("".equals(currentVal)) {
            newVal = "immersive.full=*";
        }
        Settings.Global.putString(cr, Settings.Global.POLICY_CONTROL, newVal);
        if (newVal.equals("")) {
            WindowManagerPolicyControl.reloadFromSetting(context);
        }
    }
*/

    private static void dispatchMediaKeyWithWakeLock(int keycode, Context context) {
        if (ActivityManagerNative.isSystemReady()) {
            KeyEvent event = new KeyEvent(SystemClock.uptimeMillis(),
                    SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, keycode, 0);
            MediaSessionLegacyHelper.getHelper(context).sendMediaButtonEvent(event, true);
            event = KeyEvent.changeAction(event, KeyEvent.ACTION_UP);
            MediaSessionLegacyHelper.getHelper(context).sendMediaButtonEvent(event, true);
        }
    }

    private static void triggerVirtualKeypress(Context context, final int keyCode) {
        final InputManager im = InputManager.getInstance();
        final long now = SystemClock.uptimeMillis();
        int downflags = 0;

        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            downflags = KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE;
        } else {
            downflags = KeyEvent.FLAG_FROM_SYSTEM;
        }

        final KeyEvent downEvent = new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                downflags, InputDevice.SOURCE_KEYBOARD);
        final KeyEvent upEvent = KeyEvent.changeAction(downEvent, KeyEvent.ACTION_UP);
        final Handler handler = new Handler(Looper.getMainLooper());

        final Runnable downRunnable = new Runnable() {
            @Override
            public void run() {
                im.injectInputEvent(downEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            }
        };

        final Runnable upRunnable = new Runnable() {
            @Override
            public void run() {
                im.injectInputEvent(upEvent, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            }
        };

        handler.post(downRunnable);
        handler.postDelayed(upRunnable, 10);
    }

    private static void launchCamera(Context context) {
        Intent i = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
        PackageManager pm = context.getPackageManager();
        final ResolveInfo mInfo = pm.resolveActivity(i, 0);
        Intent intent = new Intent().setComponent(new ComponentName(mInfo.activityInfo.packageName,
                mInfo.activityInfo.name));
        launchActivity(context, intent);
    }

    private static void toggleWifi(Context context) {
        WifiManager wfm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        wfm.setWifiEnabled(!wfm.isWifiEnabled());
    }

    private static void toggleBluetooth() {
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        boolean enabled = bluetoothAdapter.isEnabled();
        if (enabled) {
            bluetoothAdapter.disable();
        } else {
            bluetoothAdapter.enable();
        }
    }

    private static void toggleWifiAP(Context context) {
        final ContentResolver cr = context.getContentResolver();
        WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        final ConnectivityManager mConnectivityManager;
        mConnectivityManager = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        int state = wm.getWifiApState();
        boolean enabled = false;
        switch (state) {
            case WifiManager.WIFI_AP_STATE_ENABLING:
            case WifiManager.WIFI_AP_STATE_ENABLED:
                enabled = false;
                break;
            case WifiManager.WIFI_AP_STATE_DISABLING:
            case WifiManager.WIFI_AP_STATE_DISABLED:
                enabled = true;
                break;
        }

        // Turn on the Wifi AP
        if (enabled) {
            OnStartTetheringCallback callback = new OnStartTetheringCallback();
            mConnectivityManager.startTethering(
                    ConnectivityManager.TETHERING_WIFI, false, callback);
        } else {
            mConnectivityManager.stopTethering(ConnectivityManager.TETHERING_WIFI);
        }
    }

    static final class OnStartTetheringCallback extends
            ConnectivityManager.OnStartTetheringCallback {
        @Override
        public void onTetheringStarted() {}
        @Override
        public void onTetheringFailed() {
          // TODO: Show error.
        }
    }

    private static void sendCommandToWindowManager(Intent intent) {
        IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        try {
            wm.sendCustomAction(intent);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public static boolean killProcess(Context context) {
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.FORCE_STOP_PACKAGES) == PackageManager.PERMISSION_GRANTED
            && !isLockTaskOn()) {
            final int mUserId = ActivityManager.getCurrentUser();
            try {
                return killForegroundAppInternal(context, mUserId);
            } catch (RemoteException e) {
                Log.e(TAG, "Could not kill foreground app");
            }
        }
        Log.d("ActionHandler", "Caller cannot kill processes, aborting");
        return false;
    }

    private static boolean killForegroundAppInternal(Context context, int userId)
            throws RemoteException {
        final String packageName = getForegroundTaskPackageName(context, userId);
        String appName;

        if (packageName == null) {
            return false;
        }

        final IActivityManager am = ActivityManagerNative.getDefault();

        try {
            ApplicationInfo app = context.getPackageManager().getApplicationInfo(packageName, 0);
            appName = context.getPackageManager().getApplicationLabel(app).toString();
        } catch (Exception e) {
            appName = "";
        }
        am.forceStopPackage(packageName, UserHandle.USER_CURRENT);
        Resources systemUIRes = ActionUtils.getResourcesForPackage(context, ActionUtils.PACKAGE_SYSTEMUI);
        int ident = systemUIRes.getIdentifier("app_killed_message", ActionUtils.STRING, ActionUtils.PACKAGE_SYSTEMUI);
        String toastMsg = systemUIRes.getString(ident,appName);
        Context ctx = getPackageContext(context, ActionUtils.PACKAGE_SYSTEMUI);
        Toast.makeText(ctx != null ? ctx : context, toastMsg, Toast.LENGTH_SHORT).show();
        return true;
    }

    private static String getForegroundTaskPackageName(Context context, int userId)
            throws RemoteException {
        final String defaultHomePackage = resolveCurrentLauncherPackage(context, userId);
        final ActivityTaskManager.RootTaskInfo focusedStack = ActivityTaskManager.getService()
                .getFocusedRootTaskInfo();

        if (focusedStack == null || focusedStack.topActivity == null) {
            return null;
        }

        final String packageName = focusedStack.topActivity.getPackageName();
        if (!packageName.equals(defaultHomePackage)
                && !packageName.equals(SYSTEMUI)) {
            return packageName;
        }

        return null;
    }

    private static String resolveCurrentLauncherPackage(Context context, int userId) {
        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME);
        final PackageManager pm = context.getPackageManager();
        final ResolveInfo launcherInfo = pm.resolveActivityAsUser(launcherIntent, 0, userId);

        if (launcherInfo.activityInfo != null &&
                !launcherInfo.activityInfo.packageName.equals("android")) {
            return launcherInfo.activityInfo.packageName;
        }

        return null;
    }
/*
    private static void killProcess(Context context) {
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.FORCE_STOP_PACKAGES) == PackageManager.PERMISSION_GRANTED
            && !isLockTaskOn()) {
            try {
                PackageManager packageManager = context.getPackageManager();
                final Intent intent = new Intent(Intent.ACTION_MAIN);
                String defaultHomePackage = "com.android.launcher";
                intent.addCategory(Intent.CATEGORY_HOME);
                final ResolveInfo res = packageManager.resolveActivity(intent, 0);
                if (res.activityInfo != null
                        && !res.activityInfo.packageName.equals("android")) {
                    defaultHomePackage = res.activityInfo.packageName;
                }

                // Use UsageStats to determine foreground app
                UsageStatsManager usageStatsManager = (UsageStatsManager)
                    context.getSystemService(Context.USAGE_STATS_SERVICE);
                long current = System.currentTimeMillis();
                long past = current - (1000 * 60 * 60); // uses snapshot of usage over past 60 minutes

                // Get the list, then sort it chronilogically so most recent usage is at start of list
                List<UsageStats> recentApps = usageStatsManager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, past, current);
                Collections.sort(recentApps, new Comparator<UsageStats>() {
                    @Override
                    public int compare(UsageStats lhs, UsageStats rhs) {
                        long timeLHS = lhs.getLastTimeUsed();
                        long timeRHS = rhs.getLastTimeUsed();
                        if (timeLHS > timeRHS) {
                            return -1;
                        } else if (timeLHS < timeRHS) {
                            return 1;
                        }
                        return 0;
                    }
                });

                IActivityManager iam = ActivityManagerNative.getDefault();
                // this may not be needed due to !isLockTaskOn() in entry if
                //if (am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) return;

                // Look for most recent usagestat with lastevent == 1 and grab package name
                // ...this seems to map to the UsageEvents.Event.MOVE_TO_FOREGROUND
                String pkg = null;
                for (int i = 0; i < recentApps.size(); i++) {
                    UsageStats mostRecent = recentApps.get(i);
                    if (mostRecent.mLastEvent == 1) {
                        pkg = mostRecent.mPackageName;
                        break;
                    }
                }

                if (pkg != null && !pkg.equals("com.android.systemui")
                        && !pkg.equals(defaultHomePackage)) {

                    // Restore home screen stack before killing the app
                    Intent home = new Intent(Intent.ACTION_MAIN, null);
                    home.addCategory(Intent.CATEGORY_HOME);
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    context.startActivity(home);

                    // Kill the app
                    iam.forceStopPackage(pkg, UserHandle.USER_CURRENT);

                    // Remove killed app from Recents
/*                    final ActivityManager am = (ActivityManager)
                            context.getSystemService(Context.ACTIVITY_SERVICE);
                    final List<ActivityManager.RecentTaskInfo> recentTasks =
                            am.getRecentTasksForUser(ActivityManager.getMaxRecentTasksStatic(),
                            ActivityManager.RECENT_IGNORE_HOME_AND_RECENTS_STACK_TASKS
                                    | ActivityManager.RECENT_INGORE_PINNED_STACK_TASKS
                                    | ActivityManager.RECENT_IGNORE_UNAVAILABLE
                                    | ActivityManager.RECENT_INCLUDE_PROFILES,
                                    UserHandle.CURRENT.getIdentifier());
                    final int size = recentTasks.size();
                    for (int i = 0; i < size; i++) {
                        ActivityManager.RecentTaskInfo recentInfo = recentTasks.get(i);
                        if (recentInfo.baseIntent.getComponent().getPackageName().equals(pkg)) {
                            int taskid = recentInfo.persistentId;
                            am.removeTask(taskid);
                        }
                    }
*/
/*
                    String pkgName;
                    try {
                        pkgName = (String) packageManager.getApplicationLabel(
                            packageManager.getApplicationInfo(pkg, PackageManager.GET_META_DATA));
                    } catch (PackageManager.NameNotFoundException e) {
                        // Just use pkg if issues getting appName
                        pkgName = pkg;
                    }

                    Resources systemUIRes = ActionUtils.getResourcesForPackage(context, ActionUtils.PACKAGE_SYSTEMUI);
                    int ident = systemUIRes.getIdentifier("app_killed_message", ActionUtils.STRING, ActionUtils.PACKAGE_SYSTEMUI);
                    String toastMsg = systemUIRes.getString(ident, pkgName);
                    Context ctx = getPackageContext(context, ActionUtils.PACKAGE_SYSTEMUI);
                    Toast.makeText(ctx != null ? ctx : context, toastMsg, Toast.LENGTH_SHORT).show();
                    return;
                } else {
                    // make a "didnt kill anything" toast?
                    return;
                }
            } catch (RemoteException remoteException) {
                Log.d("ActionHandler", "Caller cannot kill processes, aborting");
            }
        } else {
            Log.d("ActionHandler", "Caller cannot kill processes, aborting");
        }
    }
*/

    public static Context getPackageContext(Context context, String packageName) {
        Context pkgContext = null;
        if (context.getPackageName().equals(packageName)) {
            pkgContext = context;
        } else {
            try {
                pkgContext = context.createPackageContext(packageName,
                        Context.CONTEXT_IGNORE_SECURITY
                                | Context.CONTEXT_INCLUDE_CODE);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }
        return pkgContext;
    }

    private static boolean isPackageLiveWalls(Context ctx, String pkg) {
        if (ctx == null || pkg == null) {
            return false;
        }
        List<ResolveInfo> liveWallsList = ctx.getPackageManager().queryIntentServices(
                new Intent(WallpaperService.SERVICE_INTERFACE),
                PackageManager.GET_META_DATA);
        if (liveWallsList == null) {
            return false;
        }
        for (ResolveInfo info : liveWallsList) {
            if (info.serviceInfo != null) {
                String packageName = info.serviceInfo.packageName;
                if (TextUtils.equals(pkg, packageName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void screenOff(Context context) {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        pm.goToSleep(SystemClock.uptimeMillis());
    }

/*    public static void turnOffLockTask() {
        try {
        	ActivityManagerNative.getDefault().stopLockTaskMode();
        } catch (Exception e) {
        }
    }
*/

    public static boolean isLockTaskOn() {
        try {
            return ActivityManagerNative.getDefault().isInLockTaskMode();
        } catch (Exception e) {
        }
        return false;
    }

    public static void volumePanel(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        am.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
    }

/*
    private static void toggleOneHandedMode(Context context, String direction) {
        String str = Settings.Global.getString(context.getContentResolver(), Settings.Global.SINGLE_HAND_MODE);

        if (TextUtils.isEmpty(str))
            Settings.Global.putString(context.getContentResolver(), Settings.Global.SINGLE_HAND_MODE, direction);
        else
            Settings.Global.putString(context.getContentResolver(), Settings.Global.SINGLE_HAND_MODE, "");
    }
    */

    public static void startAssistantSoundSearch(Context context) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setAction("com.google.android.googlequicksearchbox.MUSIC_SEARCH");
        context.startActivity(intent);
    }
}
