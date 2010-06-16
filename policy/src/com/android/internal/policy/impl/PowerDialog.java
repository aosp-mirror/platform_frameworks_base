/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.internal.policy.impl;

import com.android.internal.R;

import android.app.Dialog;
import android.app.StatusBarManager;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.LocalPowerManager;
import android.os.ServiceManager;
import android.os.SystemClock;

import com.android.internal.app.ShutdownThread;
import com.android.internal.telephony.ITelephony;
import android.view.KeyEvent;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.view.View.OnKeyListener;
import android.widget.Button;

/**
 * @deprecated use {@link GlobalActions} instead.
 */
public class PowerDialog extends Dialog implements OnClickListener,
        OnKeyListener {
    private static final String TAG = "PowerDialog";

    static private StatusBarManager sStatusBar;
    private Button mKeyguard;
    private Button mPower;
    private Button mRadioPower;
    private Button mSilent;

    private LocalPowerManager mPowerManager;

    public PowerDialog(Context context, LocalPowerManager powerManager) {
        super(context);
        mPowerManager = powerManager;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = getContext();

        if (sStatusBar == null) {
            sStatusBar = (StatusBarManager)context.getSystemService(Context.STATUS_BAR_SERVICE);
        }

        setContentView(com.android.internal.R.layout.power_dialog);

        getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        if (!getContext().getResources().getBoolean(
                com.android.internal.R.bool.config_sf_slowBlur)) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                    WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);

        setTitle(context.getText(R.string.power_dialog));

        mKeyguard = (Button) findViewById(R.id.keyguard);
        mPower = (Button) findViewById(R.id.off);
        mRadioPower = (Button) findViewById(R.id.radio_power);
        mSilent = (Button) findViewById(R.id.silent);

        if (mKeyguard != null) {
            mKeyguard.setOnKeyListener(this);
            mKeyguard.setOnClickListener(this);
        }
        if (mPower != null) {
            mPower.setOnClickListener(this);
        }
        if (mRadioPower != null) {
            mRadioPower.setOnClickListener(this);
        }
        if (mSilent != null) {
            mSilent.setOnClickListener(this);
            // XXX: HACK for now hide the silent until we get mute support
            mSilent.setVisibility(View.GONE);
        }

        CharSequence text;

        // set the keyguard button's text
        text = context.getText(R.string.screen_lock);
        mKeyguard.setText(text);
        mKeyguard.requestFocus();

        try {
            ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
            if (phone != null) {
                text = phone.isRadioOn() ? context
                        .getText(R.string.turn_off_radio) : context
                        .getText(R.string.turn_on_radio);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        mRadioPower.setText(text);
    }

    public void onClick(View v) {
        this.dismiss();
        if (v == mPower) {
            // shutdown by making sure radio and power are handled accordingly.
            ShutdownThread.shutdown(getContext(), true);
        } else if (v == mRadioPower) {
            try {
                ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
                if (phone != null) {
                    phone.toggleRadioOnOff();
                }
            } catch (RemoteException ex) {
                // ignore it
            }
        } else if (v == mSilent) {
            // do something
        } else if (v == mKeyguard) {
            if (v.isInTouchMode()) {
                // only in touch mode for the reasons explained in onKey.
                this.dismiss();
                mPowerManager.goToSleep(SystemClock.uptimeMillis() + 1);
            }
        }
    }

    public boolean onKey(View v, int keyCode, KeyEvent event) {
        // The activate keyguard button needs to put the device to sleep on the
        // key up event. If we try to put it to sleep on the click or down
        // action
        // the the up action will cause the device to wake back up.

        // Log.i(TAG, "keyCode: " + keyCode + " action: " + event.getAction());
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER
                || event.getAction() != KeyEvent.ACTION_UP) {
            // Log.i(TAG, "getting out of dodge...");
            return false;
        }

        // Log.i(TAG, "Clicked mKeyguard! dimissing dialog");
        this.dismiss();
        // Log.i(TAG, "onKey: turning off the screen...");
        // XXX: This is a hack for now
        mPowerManager.goToSleep(event.getEventTime() + 1);
        return true;
    }

    public void show() {
        super.show();
        Log.d(TAG, "show... disabling expand");
        sStatusBar.disable(StatusBarManager.DISABLE_EXPAND);
    }

    public void dismiss() {
        super.dismiss();
        Log.d(TAG, "dismiss... reenabling expand");
        sStatusBar.disable(StatusBarManager.DISABLE_NONE);
    }
}
