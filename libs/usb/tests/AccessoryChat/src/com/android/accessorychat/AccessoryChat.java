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

package com.android.accessorychat;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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

import com.android.future.usb.UsbAccessory;
import com.android.future.usb.UsbManager;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public class AccessoryChat extends Activity implements Runnable, TextView.OnEditorActionListener {

    private static final String TAG = "AccessoryChat";
    TextView mLog;
    EditText mEditText;
    ParcelFileDescriptor mFileDescriptor;
    FileInputStream mInputStream;
    FileOutputStream mOutputStream;

    private static final int MESSAGE_LOG = 1;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.accessory_chat);
        mLog = (TextView)findViewById(R.id.log);
        mEditText = (EditText)findViewById(R.id.message);
        mEditText.setOnEditorActionListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();

        Intent intent = getIntent();
        Log.d(TAG, "intent: " + intent);
        UsbManager manager = UsbManager.getInstance();
        UsbAccessory[] accessories = manager.getAccessoryList();
        UsbAccessory accessory = (accessories == null ? null : accessories[0]);
        if (accessory != null) {
            mFileDescriptor = manager.openAccessory(accessory);
            if (mFileDescriptor != null) {
                FileDescriptor fd = mFileDescriptor.getFileDescriptor();
                mInputStream = new FileInputStream(fd);
                mOutputStream = new FileOutputStream(fd);
                Thread thread = new Thread(null, this, "AccessoryChat");
                thread.start();
            } else {
                Log.d(TAG, "openAccessory fail");
            }
        } else {
            Log.d(TAG, "mAccessory is null");
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mFileDescriptor != null) {
            try {
                mFileDescriptor.close();
            } catch (IOException e) {
            } finally {
                mFileDescriptor = null;
            }
        }
    }

    @Override
    public void onDestroy() {
       super.onDestroy();
    }

    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (actionId == EditorInfo.IME_ACTION_DONE && mOutputStream != null) {
            try {
                mOutputStream.write(v.getText().toString().getBytes());
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
        int ret = 0;
        byte[] buffer = new byte[16384];
        while (ret >= 0) {
            try {
                ret = mInputStream.read(buffer);
            } catch (IOException e) {
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
                    mLog.setText(mLog.getText() + "\n" + (String)msg.obj);
                    break;
             }
        }
    };
}


