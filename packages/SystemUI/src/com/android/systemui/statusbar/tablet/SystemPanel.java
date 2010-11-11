/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.media.AudioManager;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.os.IPowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.View;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.cdma.EriInfo;
import com.android.internal.telephony.cdma.TtyIntent;

import com.android.server.WindowManagerService;

import com.android.systemui.statusbar.*;
import com.android.systemui.R;

public class SystemPanel extends LinearLayout implements StatusBarPanel {
    private static final String TAG = "SystemPanel";
    private static final boolean DEBUG = TabletStatusBar.DEBUG;

    private static final int MINIMUM_BACKLIGHT = android.os.Power.BRIGHTNESS_DIM + 5;
    private static final int MAXIMUM_BACKLIGHT = android.os.Power.BRIGHTNESS_ON;
    private static final int DEFAULT_BACKLIGHT = (int) (android.os.Power.BRIGHTNESS_ON * 0.4f);

    private TabletStatusBar mBar;
    private boolean mAirplaneMode;

    private ImageButton mBrightnessButton;
    private ImageButton mSoundButton;
    private ImageButton mOrientationButton;
    private ImageButton mAirplaneButton;
    private ImageButton mGpsButton;
    private ImageButton mBluetoothButton;

    private final IWindowManager mWM;

    private final AudioManager mAudioManager;
    private final BluetoothAdapter mBluetoothAdapter;

    public boolean isInContentArea(int x, int y) {
        final int l = getPaddingLeft();
        final int r = getWidth() - getPaddingRight();
        final int t = getPaddingTop();
        final int b = getHeight() - getPaddingBottom();
        return x >= l && x < r && y >= t && y < b;
    }

    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                refreshSound();
            } else if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                refreshBluetooth();
            }
        }
    };

    public void setBar(TabletStatusBar bar) {
        mBar = bar;
    }

    public SystemPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SystemPanel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        // our mighty overlord
        mWM = IWindowManager.Stub.asInterface(
                    ServiceManager.getService("window"));

        // audio status 
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

        // Bluetooth
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
    }

    public void onAttachedToWindow() {
        TextView settingsButton = (TextView)findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                getContext().startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                mBar.animateCollapse();
            }});

        mBrightnessButton = (ImageButton)findViewById(R.id.brightness);
        mBrightnessButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                rotateBrightness();
            }
        });

        mSoundButton = (ImageButton)findViewById(R.id.sound);
        mSoundButton.setAlpha(getSilentMode() ? 0x7F : 0xFF);
        mSoundButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setSilentMode(!getSilentMode());
                mSoundButton.setAlpha(getSilentMode() ? 0x7F : 0xFF);
            }
        });
        mOrientationButton = (ImageButton)findViewById(R.id.orientation);
        mOrientationButton.setImageResource(
            getAutoRotate()
                ? R.drawable.ic_sysbar_rotate_on
                : R.drawable.ic_sysbar_rotate_off);
        mOrientationButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setAutoRotate(!getAutoRotate());
                mOrientationButton.setImageResource(
                    getAutoRotate()
                        ? R.drawable.ic_sysbar_rotate_on
                        : R.drawable.ic_sysbar_rotate_off);
                Toast.makeText(getContext(), 
                    getAutoRotate() 
                        ? R.string.toast_rotation_free
                        : R.string.toast_rotation_locked,
                    Toast.LENGTH_SHORT).show();
            }
        });

        mAirplaneButton = (ImageButton)findViewById(R.id.airplane);
        mAirplaneButton.setAlpha(mAirplaneMode ? 0xFF : 0x7F);
        mAirplaneButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean newMode = !getAirplaneMode();
                Toast.makeText(getContext(), "Attempting to turn "
                    + (newMode ? "on" : "off") + " airplane mode (flaky).",
                    Toast.LENGTH_SHORT).show();
                setAirplaneMode(newMode);
            }
        });

        mGpsButton = (ImageButton)findViewById(R.id.gps);
        mGpsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleGps();
                refreshGps();
            }
        });

        mBluetoothButton = (ImageButton)findViewById(R.id.bluetooth);
        mBluetoothButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleBluetooth();
                refreshBluetooth();
            }
        });

        // register for broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioManager.RINGER_MODE_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        getContext().registerReceiver(mReceiver, filter);
        
        refreshBluetooth();
        refreshGps();
    }

    public void onDetachedFromWindow() {
        getContext().unregisterReceiver(mReceiver);
    }

    // ----------------------------------------------------------------------

//    private boolean isAutoBrightness() {
//        Context context = getContext();
//        try {
//            IPowerManager power = IPowerManager.Stub.asInterface(
//                    ServiceManager.getService("power"));
//            if (power != null) {
//                int brightnessMode = Settings.System.getInt(context.getContentResolver(),
//                        Settings.System.SCREEN_BRIGHTNESS_MODE);
//                return brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
//            }
//        } catch (RemoteException e) {
//        } catch (Settings.SettingNotFoundException e) {
//        }
//        return false;
//    }

    private void rotateBrightness() {
        int icon = R.drawable.ic_sysbar_brightness;
        int bg = R.drawable.sysbar_toggle_bg_on;
        Context context = getContext();
        try {
            IPowerManager power = IPowerManager.Stub.asInterface(
                    ServiceManager.getService("power"));
            if (power != null) {
                ContentResolver cr = context.getContentResolver();
                int brightness = Settings.System.getInt(cr,
                        Settings.System.SCREEN_BRIGHTNESS);
                int brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
                //Only get brightness setting if available
                if (context.getResources().getBoolean(
                        com.android.internal.R.bool.config_automatic_brightness_available)) {
                    brightnessMode = Settings.System.getInt(cr,
                            Settings.System.SCREEN_BRIGHTNESS_MODE);
                }

                // Rotate AUTO -> MINIMUM -> DEFAULT -> MAXIMUM
                // Technically, not a toggle...
                if (brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                    brightness = MINIMUM_BACKLIGHT;
                    icon = R.drawable.ic_sysbar_brightness_low;
                    brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
                } else if (brightness < DEFAULT_BACKLIGHT) {
                    brightness = DEFAULT_BACKLIGHT;
                } else if (brightness < MAXIMUM_BACKLIGHT) {
                    brightness = MAXIMUM_BACKLIGHT;
                } else {
                    brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
                    brightness = MINIMUM_BACKLIGHT;
                    icon = R.drawable.ic_sysbar_brightness_auto;
                }

                if (context.getResources().getBoolean(
                        com.android.internal.R.bool.config_automatic_brightness_available)) {
                    // Set screen brightness mode (automatic or manual)
                    Settings.System.putInt(context.getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            brightnessMode);
                } else {
                    // Make sure we set the brightness if automatic mode isn't available
                    brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
                }
                if (brightnessMode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                    power.setBacklightBrightness(brightness);
                    Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, brightness);
                }
            }
        } catch (RemoteException e) {
        } catch (Settings.SettingNotFoundException e) {
        }

        mBrightnessButton.setImageResource(icon);
        mBrightnessButton.setBackgroundResource(bg);
    }

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            if (DEBUG) {
                Slog.d(TAG, "phone service state changed: " + serviceState.getState());
            }
            mAirplaneMode = serviceState.getState() == ServiceState.STATE_POWER_OFF;
            if (mAirplaneButton != null) {
                mAirplaneButton.setImageResource(mAirplaneMode 
                                                 ? R.drawable.ic_sysbar_airplane_on
                                                 : R.drawable.ic_sysbar_airplane_off);
                mAirplaneButton.setBackgroundResource(mAirplaneMode 
                                                 ? R.drawable.sysbar_toggle_bg_on
                                                 : R.drawable.sysbar_toggle_bg_off);
            }
        }
    };

    private boolean getAirplaneMode() {
        return mAirplaneMode;
    }

    private void setAirplaneMode(boolean on) {
        Settings.System.putInt(
                mContext.getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON,
                on ? 1 : 0);
        Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        intent.addFlags(Intent.FLAG_RECEIVER_REPLACE_PENDING);
        intent.putExtra("state", on);
        getContext().sendBroadcast(intent);
    }

    boolean getSilentMode() {
        return mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
    }

    void setSilentMode(boolean on) {
        if (on) {
            mAudioManager.setRingerMode((Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.VIBRATE_IN_SILENT, 1) == 1)
                ? AudioManager.RINGER_MODE_VIBRATE
                : AudioManager.RINGER_MODE_SILENT);
        } else {
            mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        }
    }

    void refreshSound() {
        boolean silent = getSilentMode();
        mSoundButton.setImageResource(!silent 
                                         ? R.drawable.ic_sysbar_sound_on
                                         : R.drawable.ic_sysbar_sound_off);
        mSoundButton.setBackgroundResource(!silent 
                                         ? R.drawable.sysbar_toggle_bg_on
                                         : R.drawable.sysbar_toggle_bg_off);
    }

    void toggleBluetooth() {
        if (mBluetoothAdapter == null) return;
        if (mBluetoothAdapter.isEnabled()) {
            mBluetoothAdapter.disable();
        } else {
            mBluetoothAdapter.enable();
        }
    }

    void refreshBluetooth() {
        boolean on = mBluetoothAdapter != null && mBluetoothAdapter.isEnabled();
        mBluetoothButton.setImageResource(on ? R.drawable.ic_sysbar_bluetooth_on
                                             : R.drawable.ic_sysbar_bluetooth_off);
        mBluetoothButton.setBackgroundResource(on
                                         ? R.drawable.sysbar_toggle_bg_on
                                         : R.drawable.sysbar_toggle_bg_off);
    }

    private boolean isGpsEnabled() {
        ContentResolver res = mContext.getContentResolver();
        return Settings.Secure.isLocationProviderEnabled(
                                res, LocationManager.GPS_PROVIDER);
    }

    private void toggleGps() {
        Settings.Secure.setLocationProviderEnabled(mContext.getContentResolver(),
                LocationManager.GPS_PROVIDER, !isGpsEnabled());
    }

    private void refreshGps() {
        boolean on = isGpsEnabled();
        mGpsButton.setImageResource(on ? R.drawable.ic_sysbar_gps_on
                                       : R.drawable.ic_sysbar_gps_off);
        mGpsButton.setBackgroundResource(on
                                         ? R.drawable.sysbar_toggle_bg_on
                                         : R.drawable.sysbar_toggle_bg_off);
    }

    void setAutoRotate(boolean rot) {
        try {
            ContentResolver cr = getContext().getContentResolver();
            if (rot) {
                mWM.thawRotation();
            } else {
                mWM.freezeRotation();
            }
        } catch (RemoteException exc) {
        }
    }

    boolean getAutoRotate() {
        ContentResolver cr = getContext().getContentResolver();
        return 1 == Settings.System.getInt(cr,
                Settings.System.ACCELEROMETER_ROTATION,
                1);
    }

    int getDisplayRotation() {
        try {
            return mWM.getRotation();
        } catch (RemoteException exc) {
            return 0;
        }
    }
}
