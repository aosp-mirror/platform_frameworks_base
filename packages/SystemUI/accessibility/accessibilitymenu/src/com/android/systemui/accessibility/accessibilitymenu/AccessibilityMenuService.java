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

import android.accessibilityservice.AccessibilityButtonController;
import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;

import com.android.settingslib.display.BrightnessUtils;
import com.android.systemui.accessibility.accessibilitymenu.model.A11yMenuShortcut.ShortcutId;
import com.android.systemui.accessibility.accessibilitymenu.view.A11yMenuOverlayLayout;

import java.util.List;

/** @hide */
public class AccessibilityMenuService extends AccessibilityService
        implements View.OnTouchListener {
    private static final String TAG = "A11yMenuService";

    private static final long BUFFER_MILLISECONDS_TO_PREVENT_UPDATE_FAILURE = 100L;

    private static final int BRIGHTNESS_UP_INCREMENT_GAMMA =
            (int) Math.ceil(BrightnessUtils.GAMMA_SPACE_MAX * 0.11f);
    private static final int BRIGHTNESS_DOWN_INCREMENT_GAMMA =
            (int) -Math.ceil(BrightnessUtils.GAMMA_SPACE_MAX * 0.11f);

    private long mLastTimeTouchedOutside = 0L;
    // Timeout used to ignore the A11y button onClick() when ACTION_OUTSIDE is also received on
    // clicking on the A11y button.
    public static final long BUTTON_CLICK_TIMEOUT = 200;

    private A11yMenuOverlayLayout mA11yMenuLayout;

    private static boolean sInitialized = false;

    private AudioManager mAudioManager;

    // TODO(b/136716947): Support multi-display once a11y framework side is ready.
    private DisplayManager mDisplayManager;
    final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
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

        getAccessibilityButtonController().registerAccessibilityButtonCallback(
                new AccessibilityButtonController.AccessibilityButtonCallback() {
                    /**
                     * {@inheritDoc}
                     */
                    @Override
                    public void onClicked(AccessibilityButtonController controller) {
                        if (SystemClock.uptimeMillis() - mLastTimeTouchedOutside
                                > BUTTON_CLICK_TIMEOUT) {
                            mA11yMenuLayout.toggleVisibility();
                        }
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

        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mA11yMenuLayout.hideMenu();
            }}, new IntentFilter(Intent.ACTION_SCREEN_OFF)
        );

        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, null);
        mAudioManager = getSystemService(AudioManager.class);

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
     * Handles click events of shortcuts.
     *
     * @param view the shortcut button being clicked.
     */
    public void handleClick(View view) {
        // Shortcuts are repeatable in a11y menu rather than unique, so use tag ID to handle.
        int viewTag = (int) view.getTag();

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
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
        } else if (viewTag == ShortcutId.ID_RECENT_VALUE.ordinal()) {
            performGlobalAction(GLOBAL_ACTION_RECENTS);
        } else if (viewTag == ShortcutId.ID_LOCKSCREEN_VALUE.ordinal()) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        } else if (viewTag == ShortcutId.ID_QUICKSETTING_VALUE.ordinal()) {
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        } else if (viewTag == ShortcutId.ID_NOTIFICATION_VALUE.ordinal()) {
            performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
        } else if (viewTag == ShortcutId.ID_SCREENSHOT_VALUE.ordinal()) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
        } else if (viewTag == ShortcutId.ID_BRIGHTNESS_UP_VALUE.ordinal()) {
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

        mA11yMenuLayout.hideMenu();
    }

    private void adjustBrightness(int increment) {
        BrightnessInfo info = getDisplay().getBrightnessInfo();
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
        mDisplayManager.setTemporaryBrightness(getDisplayId(), brightness);
        mDisplayManager.setBrightness(getDisplayId(), brightness);
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
        List<ResolveInfo> activities =
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (!activities.isEmpty()) {
            intent.setFlags(flag);
            startActivity(intent);
        }
    }

    @Override
    public void onInterrupt() {
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
}
