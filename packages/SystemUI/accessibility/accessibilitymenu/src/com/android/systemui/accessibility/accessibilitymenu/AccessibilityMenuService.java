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

package com.android.systemui.accessibility.accessibilitymenu;

import android.Manifest;
import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import androidx.preference.PreferenceManager;

import com.android.settingslib.display.BrightnessUtils;
import com.android.systemui.accessibility.accessibilitymenu.model.A11yMenuShortcut.ShortcutId;
import com.android.systemui.accessibility.accessibilitymenu.view.A11yMenuOverlayLayout;

import java.util.List;

/** @hide */
public class AccessibilityMenuService extends AccessibilityService
        implements View.OnTouchListener {

    public static final String PACKAGE_NAME = AccessibilityMenuService.class.getPackageName();
    public static final String PACKAGE_TESTS = ".tests";
    public static final String INTENT_TOGGLE_MENU = ".toggle_menu";
    public static final String INTENT_HIDE_MENU = ".hide_menu";
    public static final String INTENT_GLOBAL_ACTION = ".global_action";
    public static final String INTENT_GLOBAL_ACTION_EXTRA = "GLOBAL_ACTION";
    public static final String INTENT_OPEN_BLOCKED = "OPEN_BLOCKED";

    private static final String TAG = "A11yMenuService";
    private static final long BUFFER_MILLISECONDS_TO_PREVENT_UPDATE_FAILURE = 100L;
    private static final long HIDE_UI_DELAY_MS = 100L;

    private static final int BRIGHTNESS_UP_INCREMENT_GAMMA =
            (int) Math.ceil(BrightnessUtils.GAMMA_SPACE_MAX * 0.11f);
    private static final int BRIGHTNESS_DOWN_INCREMENT_GAMMA =
            (int) -Math.ceil(BrightnessUtils.GAMMA_SPACE_MAX * 0.11f);

    private long mLastTimeTouchedOutside = 0L;
    // Timeout used to ignore the A11y button onClick() when ACTION_OUTSIDE is also received on
    // clicking on the A11y button.
    public static final long BUTTON_CLICK_TIMEOUT = 200;

    private A11yMenuOverlayLayout mA11yMenuLayout;
    private SharedPreferences mPrefs;

    private static boolean sInitialized = false;

    private AudioManager mAudioManager;

    // TODO(b/136716947): Support multi-display once a11y framework side is ready.
    private DisplayManager mDisplayManager;

    private KeyguardManager mKeyguardManager;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        int mRotation;

        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {
            // TODO(b/136716947): Need to reset A11yMenuOverlayLayout by display id.
        }

        @Override
        public void onDisplayChanged(int displayId) {
            Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (mRotation != display.getRotation()) {
                mRotation = display.getRotation();
                mA11yMenuLayout.updateViewLayout();
            }
        }
    };

    private final BroadcastReceiver mHideMenuReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mA11yMenuLayout.hideMenu();
        }
    };

    private final BroadcastReceiver mToggleMenuReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            toggleVisibility();
        }
    };

    /**
     * Update a11y menu layout when large button setting is changed.
     */
    private final OnSharedPreferenceChangeListener mSharedPreferenceChangeListener =
            (SharedPreferences prefs, String key) -> {
                {
                    if (key.equals(getString(R.string.pref_large_buttons))) {
                        mA11yMenuLayout.configureLayout();
                    }
                }
            };

    // Update layout.
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mOnConfigChangedRunnable = new Runnable() {
        @Override
        public void run() {
            if (!sInitialized) {
                return;
            }
            // Re-assign theme to service after onConfigurationChanged
            getTheme().applyStyle(R.style.ServiceTheme, true);
            // Caches & updates the page index to ViewPager when a11y menu is refreshed.
            // Otherwise, the menu page would reset on a UI update.
            int cachedPageIndex = mA11yMenuLayout.getPageIndex();
            mA11yMenuLayout.configureLayout(cachedPageIndex);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        setTheme(R.style.ServiceTheme);

        getAccessibilityButtonController().registerAccessibilityButtonCallback(
                new AccessibilityButtonController.AccessibilityButtonCallback() {
                    /**
                     * {@inheritDoc}
                     */
                    @Override
                    public void onClicked(AccessibilityButtonController controller) {
                        toggleVisibility();
                    }

                    /**
                     * {@inheritDoc}
                     */
                    @Override
                    public void onAvailabilityChanged(AccessibilityButtonController controller,
                            boolean available) {}
                }
        );
    }

    @Override
    public void onDestroy() {
        if (mHandler.hasCallbacks(mOnConfigChangedRunnable)) {
            mHandler.removeCallbacks(mOnConfigChangedRunnable);
        }

        super.onDestroy();
    }

    @Override
    protected void onServiceConnected() {
        mA11yMenuLayout = new A11yMenuOverlayLayout(this);

        IntentFilter hideMenuFilter = new IntentFilter();
        hideMenuFilter.addAction(Intent.ACTION_SCREEN_OFF);
        hideMenuFilter.addAction(INTENT_HIDE_MENU);

        // Including WRITE_SECURE_SETTINGS enforces that we only listen to apps
        // with the restricted WRITE_SECURE_SETTINGS permission who broadcast this intent.
        registerReceiver(mHideMenuReceiver, hideMenuFilter,
                Manifest.permission.WRITE_SECURE_SETTINGS, null,
                Context.RECEIVER_EXPORTED);
        registerReceiver(mToggleMenuReceiver,
                new IntentFilter(INTENT_TOGGLE_MENU),
                Manifest.permission.WRITE_SECURE_SETTINGS, null,
                Context.RECEIVER_EXPORTED);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        mPrefs.registerOnSharedPreferenceChangeListener(mSharedPreferenceChangeListener);


        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, null);
        mAudioManager = getSystemService(AudioManager.class);
        mKeyguardManager = getSystemService(KeyguardManager.class);

        sInitialized = true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    /**
     * This method would notify service when device configuration, such as display size,
     * localization, orientation or theme, is changed.
     *
     * @param newConfig the new device configuration.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // Prevent update layout failure
        // if multiple onConfigurationChanged are called at the same time.
        if (mHandler.hasCallbacks(mOnConfigChangedRunnable)) {
            mHandler.removeCallbacks(mOnConfigChangedRunnable);
        }
        mHandler.postDelayed(
                mOnConfigChangedRunnable, BUFFER_MILLISECONDS_TO_PREVENT_UPDATE_FAILURE);
    }

    /**
     * Performs global action and broadcasts an intent indicating the action was performed.
     * This is unnecessary for any current functionality, but is used for testing.
     * Refer to {@code performGlobalAction()}.
     *
     * @param globalAction Global action to be performed.
     * @return {@code true} if successful, {@code false} otherwise.
     */
    private boolean performGlobalActionInternal(int globalAction) {
        Intent intent = new Intent(INTENT_GLOBAL_ACTION);
        intent.putExtra(INTENT_GLOBAL_ACTION_EXTRA, globalAction);
        intent.setPackage(PACKAGE_NAME + PACKAGE_TESTS);
        sendBroadcast(intent);
        Log.i("A11yMenuService", "Broadcasting global action " + globalAction);
        return performGlobalAction(globalAction);
    }

    /**
     * Handles click events of shortcuts.
     *
     * @param view the shortcut button being clicked.
     */
    public void handleClick(View view) {
        // Shortcuts are repeatable in a11y menu rather than unique, so use tag ID to handle.
        int viewTag = (int) view.getTag();

        // First check if this was a shortcut which should keep a11y menu visible. If so,
        // perform the shortcut and return without hiding the UI.
        if (viewTag == ShortcutId.ID_BRIGHTNESS_UP_VALUE.ordinal()) {
            adjustBrightness(BRIGHTNESS_UP_INCREMENT_GAMMA);
            return;
        } else if (viewTag == ShortcutId.ID_BRIGHTNESS_DOWN_VALUE.ordinal()) {
            adjustBrightness(BRIGHTNESS_DOWN_INCREMENT_GAMMA);
            return;
        } else if (viewTag == ShortcutId.ID_VOLUME_UP_VALUE.ordinal()) {
            adjustVolume(AudioManager.ADJUST_RAISE);
            return;
        } else if (viewTag == ShortcutId.ID_VOLUME_DOWN_VALUE.ordinal()) {
            adjustVolume(AudioManager.ADJUST_LOWER);
            return;
        }

        // Hide the a11y menu UI before performing the following shortcut actions.
        mA11yMenuLayout.hideMenu();

        if (viewTag == ShortcutId.ID_ASSISTANT_VALUE.ordinal()) {
            // Always restart the voice command activity, so that the UI is reloaded.
            startActivityIfIntentIsSafe(
                    new Intent(Intent.ACTION_VOICE_COMMAND),
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        } else if (viewTag == ShortcutId.ID_A11YSETTING_VALUE.ordinal()) {
            startActivityIfIntentIsSafe(
                    new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        } else if (viewTag == ShortcutId.ID_POWER_VALUE.ordinal()) {
            performGlobalActionInternal(GLOBAL_ACTION_POWER_DIALOG);
        } else if (viewTag == ShortcutId.ID_RECENT_VALUE.ordinal()) {
            performGlobalActionInternal(GLOBAL_ACTION_RECENTS);
        } else if (viewTag == ShortcutId.ID_LOCKSCREEN_VALUE.ordinal()) {
            // Delay before locking the screen to give time for the UI to close.
            mHandler.postDelayed(
                    () -> performGlobalActionInternal(GLOBAL_ACTION_LOCK_SCREEN),
                    HIDE_UI_DELAY_MS);
        } else if (viewTag == ShortcutId.ID_QUICKSETTING_VALUE.ordinal()) {
            performGlobalActionInternal(GLOBAL_ACTION_QUICK_SETTINGS);
        } else if (viewTag == ShortcutId.ID_NOTIFICATION_VALUE.ordinal()) {
            performGlobalActionInternal(GLOBAL_ACTION_NOTIFICATIONS);
        } else if (viewTag == ShortcutId.ID_SCREENSHOT_VALUE.ordinal()) {
            mHandler.postDelayed(
                    () -> performGlobalActionInternal(GLOBAL_ACTION_TAKE_SCREENSHOT),
                    HIDE_UI_DELAY_MS);
        }
    }

    /**
     * Adjusts brightness using the same logic and utils class as the SystemUI brightness slider.
     *
     * @see BrightnessUtils
     * @see com.android.systemui.settings.brightness.BrightnessController
     * @param increment The increment amount in gamma-space
     */
    private void adjustBrightness(int increment) {
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        BrightnessInfo info = display.getBrightnessInfo();
        int gamma = BrightnessUtils.convertLinearToGammaFloat(
                info.brightness,
                info.brightnessMinimum,
                info.brightnessMaximum
        );
        gamma = Math.max(
                BrightnessUtils.GAMMA_SPACE_MIN,
                Math.min(BrightnessUtils.GAMMA_SPACE_MAX, gamma + increment));

        float brightness = BrightnessUtils.convertGammaToLinearFloat(
                gamma,
                info.brightnessMinimum,
                info.brightnessMaximum
        );
        mDisplayManager.setBrightness(display.getDisplayId(), brightness);
        mA11yMenuLayout.showSnackbar(
                getString(R.string.brightness_percentage_label,
                        (gamma / (BrightnessUtils.GAMMA_SPACE_MAX / 100))));
    }

    private void adjustVolume(int direction) {
        mAudioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC, direction,
                AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE);
        final int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        final int volume = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        mA11yMenuLayout.showSnackbar(
                getString(
                        R.string.music_volume_percentage_label,
                        (int) (100.0 / maxVolume * volume))
        );
    }

    private void startActivityIfIntentIsSafe(Intent intent, int flag) {
        PackageManager packageManager = getPackageManager();
        List<ResolveInfo> activities = packageManager.queryIntentActivities(intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY));
        if (!activities.isEmpty()) {
            intent.setFlags(flag);
            startActivity(intent);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        unregisterReceiver(mHideMenuReceiver);
        unregisterReceiver(mToggleMenuReceiver);
        mPrefs.unregisterOnSharedPreferenceChangeListener(mSharedPreferenceChangeListener);
        sInitialized = false;
        if (mA11yMenuLayout != null) {
            mA11yMenuLayout.clearLayout();
            mA11yMenuLayout = null;
        }
        return super.onUnbind(intent);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            mA11yMenuLayout.hideMenu();
        }
        return false;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
            if (mA11yMenuLayout.hideMenu()) {
                mLastTimeTouchedOutside = SystemClock.uptimeMillis();
            }
        }
        return false;
    }

    private void toggleVisibility() {
        boolean locked = mKeyguardManager != null && mKeyguardManager.isKeyguardLocked();
        if (!locked) {
            if (SystemClock.uptimeMillis() - mLastTimeTouchedOutside
                    > BUTTON_CLICK_TIMEOUT) {
                mA11yMenuLayout.toggleVisibility();
            }
        } else {
            // Broadcast for testing.
            Intent intent = new Intent(INTENT_OPEN_BLOCKED);
            intent.setPackage(PACKAGE_NAME + PACKAGE_TESTS);
            sendBroadcast(intent);
        }
    }
}
