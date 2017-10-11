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

package com.android.smspush.unitTests;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;

import com.android.internal.telephony.IWapPushManager;
import com.android.internal.telephony.WapPushManagerParams;
import com.android.internal.telephony.WapPushOverSms;
import com.android.internal.util.HexDump;
import com.android.smspush.WapPushManager;

/**
 * WapPushManager test application
 */
public class ClientTest extends Activity {
    private static final String LOG_TAG = "WAP PUSH";

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        Button addpbtn = findViewById(R.id.addpkg);
        Button procbtn = findViewById(R.id.procmsg);
        Button delbtn = findViewById(R.id.delpkg);

        Log.v(LOG_TAG, "activity created!!");

        addpbtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    EditText app_id = findViewById(R.id.app_id);
                    EditText cont = findViewById(R.id.cont);
                    EditText pkg = findViewById(R.id.pkg);
                    EditText cls = findViewById(R.id.cls);
                    RadioButton act = findViewById(R.id.act);
                    CheckBox sig = findViewById(R.id.sig);
                    CheckBox ftr = findViewById(R.id.ftr);

                    try {
                        if (!mWapPushMan.addPackage(
                                app_id.getText().toString(),
                                cont.getText().toString(),
                                pkg.getText().toString(),
                                cls.getText().toString(),
                                act.isChecked() ? WapPushManagerParams.APP_TYPE_ACTIVITY :
                                WapPushManagerParams.APP_TYPE_SERVICE,
                                sig.isChecked(), ftr.isChecked())) {

                            Log.w(LOG_TAG, "remote add pkg failed...");
                            mWapPushMan.updatePackage(
                                    app_id.getText().toString(),
                                    cont.getText().toString(),
                                    pkg.getText().toString(),
                                    cls.getText().toString(),
                                    act.isChecked() ? WapPushManagerParams.APP_TYPE_ACTIVITY :
                                    WapPushManagerParams.APP_TYPE_SERVICE,
                                    sig.isChecked(), ftr.isChecked());
                        }
                    } catch (RemoteException e) {
                            Log.w(LOG_TAG, "remote func failed...");
                    }
                }
            });

        delbtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    EditText app_id = findViewById(R.id.app_id);
                    EditText cont = findViewById(R.id.cont);
                    EditText pkg = findViewById(R.id.pkg);
                    EditText cls = findViewById(R.id.cls);
                    // CheckBox delall = findViewById(R.id.delall);
                    // Log.d(LOG_TAG, "button clicked");

                    try {
                        mWapPushMan.deletePackage(
                                app_id.getText().toString(),
                                cont.getText().toString(),
                                pkg.getText().toString(),
                                cls.getText().toString());
                        // delall.isChecked());
                    } catch (RemoteException e) {
                        Log.w(LOG_TAG, "remote func failed...");
                    }
                }
            });

        procbtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    EditText pdu = findViewById(R.id.pdu);
                    EditText app_id = findViewById(R.id.app_id);
                    EditText cont = findViewById(R.id.cont);

                    // WapPushOverSms wap = new WapPushOverSms();
                    // wap.dispatchWapPdu(strToHex(pdu.getText().toString()));
                    try {
                        Intent intent = new Intent();
                        intent.putExtra("transactionId", 0);
                        intent.putExtra("pduType", 6);
                        intent.putExtra("header",
                                HexDump.hexStringToByteArray(pdu.getText().toString()));
                        intent.putExtra("data",
                                HexDump.hexStringToByteArray(pdu.getText().toString()));

                        mWapPushMan.processMessage(
                                app_id.getText().toString(),
                                cont.getText().toString(),
                                intent);
                        //HexDump.hexStringToByteArray(pdu.getText().toString()), 0, 6, 5, 5);
                    } catch (RemoteException e) {
                        Log.w(LOG_TAG, "remote func failed...");
                    }
                }
            });
    }

    private IWapPushManager mWapPushMan;
    private ServiceConnection conn = new ServiceConnection() {
        public void onServiceDisconnected(ComponentName name) {
            mWapPushMan = null;
            Log.v(LOG_TAG, "service disconnected.");
        }

        public void onServiceConnected(ComponentName name, IBinder service) {
            mWapPushMan = IWapPushManager.Stub.asInterface(service);
            Log.v(LOG_TAG, "service connected.");
        }
        };

    @Override
    public void onStart() {
        super.onStart();
        Log.v(LOG_TAG, "onStart bind WAPPushManager service "
                + IWapPushManager.class.getName());
        this.bindService(new Intent(IWapPushManager.class.getName()), conn,
                Context.BIND_AUTO_CREATE);
        Log.v(LOG_TAG, "bind service done.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        this.unbindService(conn);
    }

}
