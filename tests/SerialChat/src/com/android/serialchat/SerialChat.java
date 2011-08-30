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

package com.android.serialchat;

import android.app.Activity;
import android.content.Context;
import android.hardware.SerialManager;
import android.hardware.SerialPort;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.util.Log;
import android.widget.EditText;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.io.IOException;

public class SerialChat extends Activity implements Runnable, TextView.OnEditorActionListener {

    private static final String TAG = "SerialChat";

    private TextView mLog;
    private EditText mEditText;
    private ByteBuffer mInputBuffer;
    private ByteBuffer mOutputBuffer;
    private SerialManager mSerialManager;
    private SerialPort mSerialPort;
    private boolean mPermissionRequestPending;

    private static final int MESSAGE_LOG = 1;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mSerialManager = (SerialManager)getSystemService(Context.SERIAL_SERVICE);
        setContentView(R.layout.serial_chat);
        mLog = (TextView)findViewById(R.id.log);
        mEditText = (EditText)findViewById(R.id.message);
        mEditText.setOnEditorActionListener(this);

        if (false) {
            mInputBuffer = ByteBuffer.allocateDirect(1024);
            mOutputBuffer = ByteBuffer.allocateDirect(1024);
        } else {
            mInputBuffer = ByteBuffer.allocate(1024);
            mOutputBuffer = ByteBuffer.allocate(1024);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        String[] ports = mSerialManager.getSerialPorts();
        if (ports != null && ports.length > 0) {
            try {
                mSerialPort = mSerialManager.openSerialPort(ports[0], 115200);
                if (mSerialPort != null) {
                    new Thread(this).start();
                }
            } catch (IOException e) {
            }
        }

    }

    @Override
    public void onPause() {
        super.onPause();
    
    }

    @Override
    public void onDestroy() {
        if (mSerialPort != null) {
            try {
                mSerialPort.close();
            } catch (IOException e) {
            }
            mSerialPort = null;
        }
        super.onDestroy();
    }

    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (/* actionId == EditorInfo.IME_ACTION_DONE && */ mSerialPort != null) {
            try {
                String text = v.getText().toString();
                Log.d(TAG, "write: " + text);
                byte[] bytes = text.getBytes();
                mOutputBuffer.clear();
                mOutputBuffer.put(bytes);
                mSerialPort.write(mOutputBuffer, bytes.length);
            } catch (IOException e) {
                Log.e(TAG, "write failed", e);
            }
            v.setText("");
            return true;
        }
        Log.d(TAG, "onEditorAction " + actionId + " event: " + event);
        return false;
    }

    public void run() {
        Log.d(TAG, "run");
        int ret = 0;
        byte[] buffer = new byte[1024];
        while (ret >= 0) {
            try {
                Log.d(TAG, "calling read");
                mInputBuffer.clear();
                ret = mSerialPort.read(mInputBuffer);
                Log.d(TAG, "read returned " + ret);
                mInputBuffer.get(buffer, 0, ret);
            } catch (IOException e) {
                Log.e(TAG, "read failed", e);
                break;
            }

            if (ret > 0) {
                Message m = Message.obtain(mHandler, MESSAGE_LOG);
                String text = new String(buffer, 0, ret);
                Log.d(TAG, "chat: " + text);
                m.obj = text;
                mHandler.sendMessage(m);
            }
        }
        Log.d(TAG, "thread out");
    }

   Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_LOG:
                    mLog.setText(mLog.getText() + (String)msg.obj);
                    break;
             }
        }
    };
}


