/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.test.uibench;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Looper;
import android.os.MessageQueue;
import android.support.v7.app.AppCompatActivity;
import android.view.KeyEvent;
import android.widget.EditText;

import java.util.concurrent.Semaphore;

/**
 * Note: currently incomplete, complexity of input continuously grows, instead of looping
 * over a stable amount of work.
 *
 * Simulates typing continuously into an EditText.
 */
public class EditTextTypeActivity extends AppCompatActivity {
    Thread mThread;

    private static String sSeedText = "";
    static {
        final int count = 100;
        final String string = "hello ";

        StringBuilder builder = new StringBuilder(count * string.length());
        for (int i = 0; i < count; i++) {
            builder.append(string);
        }
        sSeedText = builder.toString();
    }

    final Object mLock = new Object();
    boolean mShouldStop = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EditText editText = new EditText(this);
        editText.setText(sSeedText);
        setContentView(editText);

        final Instrumentation instrumentation = new Instrumentation();
        final Semaphore sem = new Semaphore(0);
        MessageQueue.IdleHandler handler = new MessageQueue.IdleHandler() {
            @Override
            public boolean queueIdle() {
                // TODO: consider other signaling approaches
                sem.release();
                return true;
            }
        };
        Looper.myQueue().addIdleHandler(handler);
        synchronized (mLock) {
            mShouldStop = false;
        }
        mThread = new Thread(new Runnable() {
            int codes[] = { KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_L,
                    KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_SPACE };
            int i = 0;
            @Override
            public void run() {
                while (true) {
                    try {
                        sem.acquire();
                    } catch (InterruptedException e) {
                        // TODO, maybe
                    }
                    int code = codes[i % codes.length];
                    if (i % 100 == 99) code = KeyEvent.KEYCODE_ENTER;

                    synchronized (mLock) {
                        if (mShouldStop) break;
                    }

                    // TODO: bit of a race here, since the event can arrive after pause/stop.
                    // (Can't synchronize on key send, since it's synchronous.)
                    instrumentation.sendKeyDownUpSync(code);
                    i++;
                }
            }
        });
        mThread.start();
    }

    @Override
    protected void onPause() {
        synchronized (mLock) {
            mShouldStop = true;
        }
        super.onPause();
    }
}
