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

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.Message;
import android.os.RemoteException;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.Slog;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerImpl;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextSwitcher;
import android.widget.TextView;
import android.widget.Toast;

import com.android.systemui.statusbar.*;
import com.android.systemui.R;

public class SystemPanel extends LinearLayout {
    private static final String TAG = "SystemPanel";

    private static final int MINIMUM_BACKLIGHT = android.os.Power.BRIGHTNESS_DIM + 5;
    private static final int MAXIMUM_BACKLIGHT = android.os.Power.BRIGHTNESS_ON;
    private static final int DEFAULT_BACKLIGHT = (int) (android.os.Power.BRIGHTNESS_ON * 0.4f);


    private TabletStatusBarService mBar;
    private boolean mAirplaneMode;

    private ImageButton mBrightnessButton;
    private ImageButton mSoundButton;
    private ImageButton mOrientationButton;
    private ImageButton mAirplaneButton;

    private final AudioManager mAudioManager;


    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(AudioManager.RINGER_MODE_CHANGED_ACTION)) {
                mSoundButton.setAlpha(getSilentMode() ? 0x7F : 0xFF);
            }
        }
    };

    public void setBar(TabletStatusBarService bar) {
        mBar = bar;
    }
    
    public SystemPanel(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SystemPanel(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        // get notified of phone state changes
        TelephonyManager telephonyManager =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        telephonyManager.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);

        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
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
        mSoundButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setSilentMode(!getSilentMode());
                mSoundButton.setAlpha(getSilentMode() ? 0x7F : 0xFF);
            }
        });
        mOrientationButton = (ImageButton)findViewById(R.id.orientation);
        mOrientationButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Toast.makeText(getContext(), "Orientation control not implemented; please adjust neck angle.", Toast.LENGTH_SHORT).show();
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

        IntentFilter filter = new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
        getContext().registerReceiver(mReceiver, filter);
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
        int alpha = 0xFF;
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
                    brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
                    alpha = 0x40;
                } else if (brightness < DEFAULT_BACKLIGHT) {
                    brightness = DEFAULT_BACKLIGHT;
                    alpha = 0xC0;
                } else if (brightness < MAXIMUM_BACKLIGHT) {
                    brightness = MAXIMUM_BACKLIGHT;
                    alpha = 0xFF;
                } else {
                    brightnessMode = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
                    brightness = MINIMUM_BACKLIGHT;
                    alpha = 0x60;
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

        mBrightnessButton.setAlpha(alpha);
    }

    PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            Slog.d(TAG, "phone service state changed: " + serviceState.getState());
            mAirplaneMode = serviceState.getState() == ServiceState.STATE_POWER_OFF;
            if (mAirplaneButton != null) {
                mAirplaneButton.setAlpha(mAirplaneMode ? 0xFF : 0x7F);
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

}
