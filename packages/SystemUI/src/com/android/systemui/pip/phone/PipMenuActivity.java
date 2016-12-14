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
import android.app.PendingIntent.CanceledException;
import android.app.RemoteAction;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Translucent activity that gets started on top of a task in PIP to allow the user to control it.
 */
public class PipMenuActivity extends Activity {

    private static final String TAG = "PipMenuActivity";

    public static final int MESSAGE_FINISH_SELF = 1;
    public static final int MESSAGE_UPDATE_ACTIONS = 2;

    private static final long INITIAL_DISMISS_DELAY = 2000;
    private static final long POST_INTERACTION_DISMISS_DELAY = 1500;

    private List<RemoteAction> mActions = new ArrayList<>();
    private View mDismissButton;
    private View mMinimizeButton;

    private Handler mHandler = new Handler();
    private Messenger mToControllerMessenger;
    private Messenger mMessenger = new Messenger(new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_UPDATE_ACTIONS:
                    setActions(((ParceledListSlice) msg.obj).getList());
                    break;
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
        setContentView(R.layout.pip_menu_activity);

        Intent startingIntent = getIntent();
        mToControllerMessenger = startingIntent.getParcelableExtra(
                PipMenuActivityController.EXTRA_CONTROLLER_MESSENGER);
        ParceledListSlice actions = startingIntent.getParcelableExtra(
                PipMenuActivityController.EXTRA_ACTIONS);
        if (actions != null) {
            setActions(actions.getList());
        }

        findViewById(R.id.menu).setOnClickListener((v) -> {
            expandPip();
        });
        mDismissButton = findViewById(R.id.dismiss);
        mDismissButton.setOnClickListener((v) -> {
            dismissPip();
        });
        mMinimizeButton = findViewById(R.id.minimize);
        mMinimizeButton.setOnClickListener((v) -> {
            minimizePip();
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

    private void setActions(List<RemoteAction> actions) {
        mActions.clear();
        mActions.addAll(actions);
        updateActionViews();
    }

    private void updateActionViews() {
        ViewGroup actionsContainer = (ViewGroup) findViewById(R.id.actions);
        if (actionsContainer != null) {
            actionsContainer.removeAllViews();

            // Recreate the layout
            final LayoutInflater inflater = LayoutInflater.from(this);
            for (int i = 0; i < mActions.size(); i++) {
                final RemoteAction action = mActions.get(i);
                final ViewGroup actionContainer = (ViewGroup) inflater.inflate(
                        R.layout.pip_menu_action, actionsContainer, false);
                actionContainer.setOnClickListener((v) -> {
                    action.sendActionInvoked();
                });

                final TextView title = (TextView) actionContainer.findViewById(R.id.title);
                title.setText(action.getTitle());
                title.setContentDescription(action.getContentDescription());

                final ImageView icon = (ImageView) actionContainer.findViewById(R.id.icon);
                action.getIcon().loadDrawableAsync(this, (d) -> {
                    icon.setImageDrawable(d);
                }, mHandler);
                actionsContainer.addView(actionContainer);
            }
        }
    }

    private void notifyActivityVisibility(boolean visible) {
        Message m = Message.obtain();
        m.what = PipMenuActivityController.MESSAGE_ACTIVITY_VISIBILITY_CHANGED;
        m.arg1 = visible ? 1 : 0;
        m.replyTo = visible ? mMessenger : null;
        sendMessage(m, "Could not notify controller of PIP menu visibility");
    }

    private void expandPip() {
        sendEmptyMessage(PipMenuActivityController.MESSAGE_EXPAND_PIP,
                "Could not notify controller to expand PIP");
    }

    private void minimizePip() {
        sendEmptyMessage(PipMenuActivityController.MESSAGE_MINIMIZE_PIP,
                "Could not notify controller to minimize PIP");
    }

    private void dismissPip() {
        sendEmptyMessage(PipMenuActivityController.MESSAGE_DISMISS_PIP,
                "Could not notify controller to dismiss PIP");
    }

    private void sendEmptyMessage(int what, String errorMsg) {
        Message m = Message.obtain();
        m.what = what;
        sendMessage(m, errorMsg);
    }

    private void sendMessage(Message m, String errorMsg) {
        try {
            mToControllerMessenger.send(m);
        } catch (RemoteException e) {
            Log.e(TAG, errorMsg, e);
        }
    }

    private void repostDelayedFinish(long delay) {
        View v = getWindow().getDecorView();
        v.removeCallbacks(mFinishRunnable);
        v.postDelayed(mFinishRunnable, delay);
    }
}
