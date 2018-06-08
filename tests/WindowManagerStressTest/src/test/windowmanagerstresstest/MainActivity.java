/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package test.windowmanagerstresstest;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.MergedConfiguration;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.IWindowSession;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.WindowManagerGlobal;
import android.widget.TextView;

import com.android.internal.view.BaseIWindow;

import java.util.ArrayList;

public class MainActivity extends Activity {

    private static final String TAG = "WmSlam";

    private TextView mOutput;
    private volatile boolean finished;
    private final ArrayList<BaseIWindow> mWindows = new ArrayList<>();
    private final LayoutParams mLayoutParams = new LayoutParams();
    private final Rect mTmpRect = new Rect();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mOutput = (TextView) findViewById(R.id.output);

        findViewById(R.id.run).setOnClickListener(view -> {
            view.setEnabled(false);
            mOutput.setText("");
            startBatch();
        });
        mLayoutParams.token = getActivityToken();
    }

    void startBatch() {
        new Thread(() -> {
            finished = false;
            addWindows();
            startCpuRunnables();
            for (int i = 0; i < 5; i++) {
                final long time = SystemClock.uptimeMillis();
                slamWm();
                log("Total: " + (SystemClock.uptimeMillis() - time) + " ms");
            }
            removeWindows();
            finished = true;
        }).start();
    }

    void startCpuRunnables() {
        for (int i = 0; i < 10; i++) {
            new Thread(mUseCpuRunnable).start();
        }
    }

    private final Runnable mUseCpuRunnable = new Runnable() {
        @Override
        public void run() {
            while (!finished) {
            }
        }
    };

    private void log(String text) {
        mOutput.post(() -> mOutput.append(text + "\n"));
        Log.d(TAG, text);
    }

    private void slamWm() {
        ArrayList<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            for (BaseIWindow window : mWindows) {
                Thread t = new Thread(() -> {
                    try {
                        WindowManagerGlobal.getWindowSession().relayout(window,
                                window.mSeq, mLayoutParams, -1, -1, View.VISIBLE, 0, -1, mTmpRect,
                                mTmpRect, mTmpRect, mTmpRect, mTmpRect, mTmpRect, mTmpRect,
                                new DisplayCutout.ParcelableWrapper(), new MergedConfiguration(),
                                new Surface());
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                });
                threads.add(t);
                t.start();
            }
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    void addWindows() {
        for (int i = 0; i < 50; i++) {
            final WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
            layoutParams.token = getActivityToken();
            final BaseIWindow window = new BaseIWindow();
            final IWindowSession session = WindowManagerGlobal.getWindowSession();
            final Rect tmpRect = new Rect();
            try {
                final int res = session.addToDisplayWithoutInputChannel(window, window.mSeq, layoutParams,
                        View.VISIBLE, Display.DEFAULT_DISPLAY, tmpRect, tmpRect);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            mWindows.add(window);
        }
    }

    void removeWindows() {
        for (BaseIWindow window : mWindows) {
            try {
                WindowManagerGlobal.getWindowSession().remove(window);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
}
