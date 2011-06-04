/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.vpndialogs;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.FileInputStream;

public class ManageDialog extends Activity implements
        DialogInterface.OnClickListener, DialogInterface.OnDismissListener {
    private static final String TAG = "VpnManage";

    private String mPackageName;
    private String mInterfaceName;
    private long mStartTime;

    private ConnectivityManager mService;

    private AlertDialog mDialog;
    private TextView mDuration;
    private TextView mDataTransmitted;
    private TextView mDataReceived;

    private Updater mUpdater;

    @Override
    protected void onResume() {
        super.onResume();
        try {
            Intent intent = getIntent();
            // TODO: Move constants into VpnBuilder.
            mPackageName = intent.getStringExtra("packageName");
            mInterfaceName = intent.getStringExtra("interfaceName");
            mStartTime = intent.getLongExtra("startTime", 0);

            mService = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);

            PackageManager pm = getPackageManager();
            ApplicationInfo app = pm.getApplicationInfo(mPackageName, 0);

            View view = View.inflate(this, R.layout.manage, null);
            String session = intent.getStringExtra("session");
            if (session != null) {
                ((TextView) view.findViewById(R.id.session)).setText(session);
            }
            mDuration = (TextView) view.findViewById(R.id.duration);
            mDataTransmitted = (TextView) view.findViewById(R.id.data_transmitted);
            mDataReceived = (TextView) view.findViewById(R.id.data_received);

            mDialog = new AlertDialog.Builder(this)
                    .setIcon(app.loadIcon(pm))
                    .setTitle(app.loadLabel(pm))
                    .setView(view)
                    .setPositiveButton(R.string.configure, this)
                    .setNeutralButton(R.string.disconnect, this)
                    .setNegativeButton(android.R.string.cancel, this)
                    .create();
            mDialog.setOnDismissListener(this);
            mDialog.show();

            mUpdater = new Updater();
            mUpdater.sendEmptyMessage(0);
        } catch (Exception e) {
            Log.e(TAG, "onResume", e);
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mDialog != null) {
            mDialog.setOnDismissListener(null);
            mDialog.dismiss();
        }
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        try {
            if (which == AlertDialog.BUTTON_POSITIVE) {
                Intent intent = new Intent(Intent.ACTION_MAIN);
                intent.setPackage(mPackageName);
                startActivity(intent);
            } else if (which == AlertDialog.BUTTON_NEUTRAL) {
                mService.prepareVpn(mPackageName);
            }
        } catch (Exception e) {
            Log.e(TAG, "onClick", e);
        }
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        finish();
    }

    private class Updater extends Handler {
        public void handleMessage(Message message) {
            removeMessages(0);

            if (mDialog.isShowing()) {
                if (mStartTime != 0) {
                    long seconds = (SystemClock.elapsedRealtime() - mStartTime) / 1000;
                    mDuration.setText(String.format("%02d:%02d:%02d",
                            seconds / 3600, seconds / 60 % 60, seconds % 60));
                }

                String[] numbers = getStatistics();
                if (numbers != null) {
                    // [1] and [2] are received data in bytes and packets.
                    mDataReceived.setText(getString(R.string.data_value_format,
                            numbers[1], numbers[2]));

                    // [9] and [10] are transmitted data in bytes and packets.
                    mDataTransmitted.setText(getString(R.string.data_value_format,
                            numbers[9], numbers[10]));
                }
                sendEmptyMessageDelayed(0, 1000);
            }
        }
    }

    private String[] getStatistics() {
        DataInputStream in = null;
        try {
            // See dev_seq_printf_stats() in net/core/dev.c.
            in = new DataInputStream(new FileInputStream("/proc/net/dev"));
            String prefix = mInterfaceName + ':';

            while (true) {
                String line = in.readLine().trim();
                if (line.startsWith(prefix)) {
                    String[] numbers = line.substring(prefix.length()).split(" +");
                    if (numbers.length == 17) {
                        return numbers;
                    }
                    break;
                }
            }
        } catch (Exception e) {
            // ignore
        } finally {
            try {
                in.close();
            } catch (Exception e) {
                // ignore
            }
        }
        return null;
    }
}
