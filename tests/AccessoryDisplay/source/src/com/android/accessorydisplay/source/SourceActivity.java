/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.accessorydisplay.source;

import com.android.accessorydisplay.common.Logger;
import com.android.accessorydisplay.source.presentation.DemoPresentation;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Display;
import android.widget.TextView;

public class SourceActivity extends Activity {
    private static final String TAG = "SourceActivity";

    private static final String ACTION_USB_ACCESSORY_PERMISSION =
            "com.android.accessorydisplay.source.ACTION_USB_ACCESSORY_PERMISSION";

    private static final String MANUFACTURER = "Android";
    private static final String MODEL = "Accessory Display";

    private UsbManager mUsbManager;
    private AccessoryReceiver mReceiver;
    private TextView mLogTextView;
    private Logger mLogger;
    private Presenter mPresenter;

    private boolean mConnected;
    private UsbAccessory mAccessory;
    private UsbAccessoryStreamTransport mTransport;

    private DisplaySourceService mDisplaySourceService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mUsbManager = (UsbManager)getSystemService(Context.USB_SERVICE);

        setContentView(R.layout.source_activity);

        mLogTextView = (TextView) findViewById(R.id.logTextView);
        mLogTextView.setMovementMethod(ScrollingMovementMethod.getInstance());
        mLogger = new TextLogger();
        mPresenter = new Presenter();

        mLogger.log("Waiting for accessory display sink to be attached to USB...");

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        filter.addAction(ACTION_USB_ACCESSORY_PERMISSION);
        mReceiver = new AccessoryReceiver();
        registerReceiver(mReceiver, filter);

        Intent intent = getIntent();
        if (intent.getAction().equals(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)) {
            UsbAccessory accessory =
                    (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
            if (accessory != null) {
                onAccessoryAttached(accessory);
            }
        } else {
            UsbAccessory[] accessories = mUsbManager.getAccessoryList();
            if (accessories != null) {
                for (UsbAccessory accessory : accessories) {
                    onAccessoryAttached(accessory);
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        unregisterReceiver(mReceiver);
    }

    @Override
    protected void onResume() {
        super.onResume();

        //new DemoPresentation(this, getWindowManager().getDefaultDisplay()).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    private void onAccessoryAttached(UsbAccessory accessory) {
        mLogger.log("USB accessory attached: " + accessory);
        if (!mConnected) {
            connect(accessory);
        }
    }

    private void onAccessoryDetached(UsbAccessory accessory) {
        mLogger.log("USB accessory detached: " + accessory);
        if (mConnected && accessory.equals(mAccessory)) {
            disconnect();
        }
    }

    private void connect(UsbAccessory accessory) {
        if (!isSink(accessory)) {
            mLogger.log("Not connecting to USB accessory because it is not an accessory display sink: "
                    + accessory);
            return;
        }

        if (mConnected) {
            disconnect();
        }

        // Check whether we have permission to access the accessory.
        if (!mUsbManager.hasPermission(accessory)) {
            mLogger.log("Prompting the user for access to the accessory.");
            Intent intent = new Intent(ACTION_USB_ACCESSORY_PERMISSION);
            intent.setPackage(getPackageName());
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this, 0, intent, PendingIntent.FLAG_ONE_SHOT);
            mUsbManager.requestPermission(accessory, pendingIntent);
            return;
        }

        // Open the accessory.
        ParcelFileDescriptor fd = mUsbManager.openAccessory(accessory);
        if (fd == null) {
            mLogger.logError("Could not obtain accessory connection.");
            return;
        }

        // All set.
        mLogger.log("Connected.");
        mConnected = true;
        mAccessory = accessory;
        mTransport = new UsbAccessoryStreamTransport(mLogger, fd);
        startServices();
        mTransport.startReading();
    }

    private void disconnect() {
        mLogger.log("Disconnecting from accessory: " + mAccessory);
        stopServices();

        mLogger.log("Disconnected.");
        mConnected = false;
        mAccessory = null;
        if (mTransport != null) {
            mTransport.close();
            mTransport = null;
        }
    }

    private void startServices() {
        mDisplaySourceService = new DisplaySourceService(this, mTransport, mPresenter);
        mDisplaySourceService.start();
    }

    private void stopServices() {
        if (mDisplaySourceService != null) {
            mDisplaySourceService.stop();
            mDisplaySourceService = null;
        }
    }

    private static boolean isSink(UsbAccessory accessory) {
        return MANUFACTURER.equals(accessory.getManufacturer())
                && MODEL.equals(accessory.getModel());
    }

    class TextLogger extends Logger {
        @Override
        public void log(final String message) {
            Log.d(TAG, message);

            mLogTextView.post(new Runnable() {
                @Override
                public void run() {
                    mLogTextView.append(message);
                    mLogTextView.append("\n");
                }
            });
        }
    }

    class AccessoryReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            UsbAccessory accessory = intent.<UsbAccessory>getParcelableExtra(
                    UsbManager.EXTRA_ACCESSORY);
            if (accessory != null) {
                String action = intent.getAction();
                if (action.equals(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)) {
                    onAccessoryAttached(accessory);
                } else if (action.equals(UsbManager.ACTION_USB_ACCESSORY_DETACHED)) {
                    onAccessoryDetached(accessory);
                } else if (action.equals(ACTION_USB_ACCESSORY_PERMISSION)) {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        mLogger.log("Accessory permission granted: " + accessory);
                        onAccessoryAttached(accessory);
                    } else {
                        mLogger.logError("Accessory permission denied: " + accessory);
                    }
                }
            }
        }
    }

    class Presenter implements DisplaySourceService.Callbacks {
        private DemoPresentation mPresentation;

        @Override
        public void onDisplayAdded(Display display) {
            mLogger.log("Accessory display added: " + display);

            mPresentation = new DemoPresentation(SourceActivity.this, display, mLogger);
            mPresentation.show();
        }

        @Override
        public void onDisplayRemoved(Display display) {
            mLogger.log("Accessory display removed: " + display);

            if (mPresentation != null) {
                mPresentation.dismiss();
                mPresentation = null;
            }
        }
    }
}
