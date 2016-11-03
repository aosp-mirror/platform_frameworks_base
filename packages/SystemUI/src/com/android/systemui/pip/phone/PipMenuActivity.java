/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.pip.phone;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import com.android.systemui.R;

/**
 * Translucent activity that gets started on top of a task in PIP to allow the user to control it.
 */
public class PipMenuActivity extends Activity {

    private static final String TAG = "PipMenuActivity";

    public static final int MESSAGE_FINISH_SELF = 2;

    private static final long INITIAL_DISMISS_DELAY = 2000;
    private static final long POST_INTERACTION_DISMISS_DELAY = 1500;

    private Messenger mToControllerMessenger;
    private Messenger mMessenger = new Messenger(new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_FINISH_SELF:
                    finish();
                    break;
            }
        }
    });

    private final Runnable mFinishRunnable = new Runnable() {
        @Override
        public void run() {
            finish();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent startingIntet = getIntent();
        mToControllerMessenger = startingIntet.getParcelableExtra(
                PipMenuActivityController.EXTRA_CONTROLLER_MESSENGER);

        setContentView(R.layout.pip_menu_activity);
        findViewById(R.id.expand_pip).setOnClickListener((view) -> {
            finish();
            notifyExpandPip();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        notifyActivityVisibility(true);
        repostDelayedFinish(INITIAL_DISMISS_DELAY);
    }

    @Override
    public void onUserInteraction() {
        repostDelayedFinish(POST_INTERACTION_DISMISS_DELAY);
    }

    @Override
    protected void onStop() {
        super.onStop();
        finish();
    }

    @Override
    public void finish() {
        View v = getWindow().getDecorView();
        v.removeCallbacks(mFinishRunnable);
        notifyActivityVisibility(false);
        super.finish();
        overridePendingTransition(0, R.anim.forced_resizable_exit);
    }

    @Override
    public void setTaskDescription(ActivityManager.TaskDescription taskDescription) {
        // Do nothing
    }

    private void notifyActivityVisibility(boolean visible) {
        Message m = Message.obtain();
        m.what = PipMenuActivityController.MESSAGE_ACTIVITY_VISIBILITY_CHANGED;
        m.arg1 = visible ? 1 : 0;
        m.replyTo = visible ? mMessenger : null;
        try {
            mToControllerMessenger.send(m);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not notify controller of PIP menu visibility", e);
        }
    }

    private void notifyExpandPip() {
        Message m = Message.obtain();
        m.what = PipMenuActivityController.MESSAGE_EXPAND_PIP;
        try {
            mToControllerMessenger.send(m);
        } catch (RemoteException e) {
            Log.e(TAG, "Could not notify controller to expand PIP", e);
        }
    }

    private void repostDelayedFinish(long delay) {
        View v = getWindow().getDecorView();
        v.removeCallbacks(mFinishRunnable);
        v.postDelayed(mFinishRunnable, delay);
    }
}
