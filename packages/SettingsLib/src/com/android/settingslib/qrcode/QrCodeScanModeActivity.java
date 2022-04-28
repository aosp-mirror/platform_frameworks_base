/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.qrcode;

import static com.android.settingslib.bluetooth.BluetoothBroadcastUtils.EXTRA_BLUETOOTH_DEVICE_SINK;
import static com.android.settingslib.bluetooth.BluetoothBroadcastUtils.EXTRA_BLUETOOTH_SINK_IS_GROUP;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.fragment.app.FragmentTransaction;

import com.android.settingslib.R;
import com.android.settingslib.bluetooth.BluetoothBroadcastUtils;
import com.android.settingslib.bluetooth.BluetoothUtils;

public class QrCodeScanModeActivity extends QrCodeScanModeBaseActivity {
    private static final boolean DEBUG = BluetoothUtils.D;
    private static final String TAG = "QrCodeScanModeActivity";

    private boolean mIsGroupOp;
    private BluetoothDevice mSink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void handleIntent(Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (DEBUG) {
            Log.d(TAG, "handleIntent(), action = " + action);
        }

        if (action == null) {
            finish();
            return;
        }

        switch (action) {
            case BluetoothBroadcastUtils.ACTION_BLUETOOTH_LE_AUDIO_QR_CODE_SCANNER:
                showQrCodeScannerFragment(intent);
                break;
            default:
                if (DEBUG) {
                    Log.e(TAG, "Launch with an invalid action");
                }
                finish();
        }
    }

    protected void showQrCodeScannerFragment(Intent intent) {
        if (DEBUG) {
            Log.d(TAG, "showQrCodeScannerFragment");
        }

        if (intent != null) {
            mSink = intent.getParcelableExtra(EXTRA_BLUETOOTH_DEVICE_SINK);
            mIsGroupOp = intent.getBooleanExtra(EXTRA_BLUETOOTH_SINK_IS_GROUP, false);
            if (DEBUG) {
                Log.d(TAG, "get extra from intent");
            }
        } else {
            if (DEBUG) {
                Log.d(TAG, "intent is null, can not get bluetooth information from intent.");
            }
        }

        QrCodeScanModeFragment fragment =
                (QrCodeScanModeFragment) mFragmentManager.findFragmentByTag(
                        BluetoothBroadcastUtils.TAG_FRAGMENT_QR_CODE_SCANNER);

        if (fragment == null) {
            fragment = new QrCodeScanModeFragment(mIsGroupOp, mSink);
        } else {
            if (fragment.isVisible()) {
                return;
            }

            // When the fragment in back stack but not on top of the stack, we can simply pop
            // stack because current fragment transactions are arranged in an order
            mFragmentManager.popBackStackImmediate();
            return;
        }
        final FragmentTransaction fragmentTransaction = mFragmentManager.beginTransaction();

        fragmentTransaction.replace(R.id.fragment_container, fragment,
                BluetoothBroadcastUtils.TAG_FRAGMENT_QR_CODE_SCANNER);
        fragmentTransaction.commit();
    }
}

