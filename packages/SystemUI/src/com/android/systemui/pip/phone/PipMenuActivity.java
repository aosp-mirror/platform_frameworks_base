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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.PendingIntent.CanceledException;
import android.app.RemoteAction;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.graphics.Color;
import android.graphics.PointF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager.LayoutParams;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.android.systemui.Interpolators;
import com.android.systemui.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Translucent activity that gets started on top of a task in PIP to allow the user to control it.
 */
public class PipMenuActivity extends Activity {

    private static final String TAG = "PipMenuActivity";

    public static final int MESSAGE_SHOW_MENU = 1;
    public static final int MESSAGE_POKE_MENU = 2;
    public static final int MESSAGE_HIDE_MENU = 3;
    public static final int MESSAGE_UPDATE_ACTIONS = 4;

    private static final long INITIAL_DISMISS_DELAY = 2000;
    private static final long POST_INTERACTION_DISMISS_DELAY = 1500;
    private static final long MENU_FADE_DURATION = 125;

    private boolean mMenuVisible;
    private final List<RemoteAction> mActions = new ArrayList<>();
    private View mMenuContainer;
    private LinearLayout mActionsGroup;
    private View mDismissButton;
    private int mBetweenActionPaddingLand;

    private ObjectAnimator mMenuContainerAnimator;

    private PointF mDownPosition = new PointF();
    private PointF mDownDelta = new PointF();
    private ViewConfiguration mViewConfig;
    private Handler mHandler = new Handler();
    private Messenger mToControllerMessenger;
    private Messenger mMessenger = new Messenger(new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_SHOW_MENU:
                    showMenu();
                    break;
                case MESSAGE_POKE_MENU:
                    cancelDelayedFinish();
                    break;
                case MESSAGE_HIDE_MENU:
                    hideMenu();
                    break;
                case MESSAGE_UPDATE_ACTIONS:
                    setActions(((ParceledListSlice) msg.obj).getList());
                    break;
            }
        }
    });

    private final Runnable mFinishRunnable = new Runnable() {
        @Override
        public void run() {
            hideMenu();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Set the flags to allow us to watch for outside touches and also hide the menu and start
        // manipulating the PIP in the same touch gesture
        mViewConfig = ViewConfiguration.get(this);
        getWindow().addFlags(LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | LayoutParams.FLAG_SLIPPERY);

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

        mMenuContainer = findViewById(R.id.menu);
        mMenuContainer.setOnClickListener((v) -> {
            expandPip();
        });
        mDismissButton = findViewById(R.id.dismiss);
        mDismissButton.setOnClickListener((v) -> {
            dismissPip();
        });
        mActionsGroup = (LinearLayout) findViewById(R.id.actions_group);
        mBetweenActionPaddingLand = getResources().getDimensionPixelSize(
                R.dimen.pip_between_action_padding_land);

        notifyActivityCallback(mMessenger);
        showMenu();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        showMenu();
    }

    @Override
    public void onUserInteraction() {
        repostDelayedFinish(POST_INTERACTION_DISMISS_DELAY);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

        // If another task is starting on top of the menu, then finish it so that it can be
        // recreated on the top next time it starts
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Fallback, if we are destroyed for any other reason (like when the task is being reset),
        // also reset the callback.
        notifyActivityCallback(null);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        if (!isInPictureInPictureMode) {
            finish();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // On the first action outside the window, hide the menu
        switch (ev.getAction()) {
            case MotionEvent.ACTION_OUTSIDE:
                hideMenu();
                break;
            case MotionEvent.ACTION_DOWN:
                mDownPosition.set(ev.getX(), ev.getY());
                mDownDelta.set(0f, 0f);
                break;
            case MotionEvent.ACTION_MOVE:
                mDownDelta.set(ev.getX() - mDownPosition.x, ev.getY() - mDownPosition.y);
                if (mDownDelta.length() > mViewConfig.getScaledTouchSlop() && mMenuVisible) {
                    // Restore the input consumer and let that drive the movement of this menu
                    notifyRegisterInputConsumer();
                    cancelDelayedFinish();
                }
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void finish() {
        notifyActivityCallback(null);
        super.finish();
        // Hide without an animation (the menu should already be invisible at this point)
        overridePendingTransition(0, 0);
    }

    @Override
    public void setTaskDescription(ActivityManager.TaskDescription taskDescription) {
        // Do nothing
    }

    private void showMenu() {
        if (!mMenuVisible) {
            if (mMenuContainerAnimator != null) {
                mMenuContainerAnimator.cancel();
            }

            notifyMenuVisibility(true);
            mMenuContainerAnimator = ObjectAnimator.ofFloat(mMenuContainer, View.ALPHA,
                    mMenuContainer.getAlpha(), 1f);
            mMenuContainerAnimator.setInterpolator(Interpolators.ALPHA_IN);
            mMenuContainerAnimator.setDuration(MENU_FADE_DURATION);
            mMenuContainerAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    repostDelayedFinish(INITIAL_DISMISS_DELAY);
                }
            });
            mMenuContainerAnimator.start();
        } else {
            repostDelayedFinish(POST_INTERACTION_DISMISS_DELAY);
        }
    }

    private void hideMenu() {
        hideMenu(null /* animationFinishedRunnable */, true /* notifyMenuVisibility */);
    }

    private void hideMenu(final Runnable animationFinishedRunnable, boolean notifyMenuVisibility) {
        if (mMenuVisible) {
            cancelDelayedFinish();
            if (notifyMenuVisibility) {
                notifyMenuVisibility(false);
            }
            mMenuContainerAnimator = ObjectAnimator.ofFloat(mMenuContainer, View.ALPHA,
                    mMenuContainer.getAlpha(), 0f);
            mMenuContainerAnimator.setInterpolator(Interpolators.ALPHA_OUT);
            mMenuContainerAnimator.setDuration(MENU_FADE_DURATION);
            mMenuContainerAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animationFinishedRunnable != null) {
                        animationFinishedRunnable.run();
                    }
                }
            });
            mMenuContainerAnimator.start();
        }
    }

    private void setActions(List<RemoteAction> actions) {
        mActions.clear();
        mActions.addAll(actions);
        updateActionViews();
    }

    private void updateActionViews() {
        ViewGroup expandContainer = (ViewGroup) findViewById(R.id.expand_container);
        ViewGroup actionsContainer = (ViewGroup) findViewById(R.id.actions_container);
        actionsContainer.setOnTouchListener((v, ev) -> {
            // Do nothing, prevent click through to parent
            return true;
        });

        int actionsContainerHeight = 0;
        if (mActions.isEmpty()) {
            actionsContainer.setVisibility(View.INVISIBLE);
        } else {
            actionsContainer.setVisibility(View.VISIBLE);
            if (mActionsGroup != null) {
                mActionsGroup.removeAllViews();

                // Recreate the layout
                final View decorView = getWindow().getDecorView();
                final boolean isLandscapePip = decorView.getMeasuredWidth()
                        > decorView.getMeasuredHeight();
                final LayoutInflater inflater = LayoutInflater.from(this);
                for (int i = 0; i < mActions.size(); i++) {
                    final RemoteAction action = mActions.get(i);
                    final ImageView actionView = (ImageView) inflater.inflate(
                            R.layout.pip_menu_action, mActionsGroup, false);
                    action.getIcon().loadDrawableAsync(this, d -> {
                        d.setTint(Color.WHITE);
                        actionView.setImageDrawable(d);
                    }, mHandler);
                    actionView.setContentDescription(action.getContentDescription());
                    actionView.setOnClickListener(v -> {
                        try {
                            action.getActionIntent().send();
                        } catch (CanceledException e) {
                            Log.w(TAG, "Failed to send action", e);
                        }
                    });
                    if (isLandscapePip && i > 0) {
                        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                                actionView.getLayoutParams();
                        lp.leftMargin = mBetweenActionPaddingLand;
                    }
                    mActionsGroup.addView(actionView);
                }
            }
            actionsContainerHeight = actionsContainer.getLayoutParams().height;
        }

        // Update the expand container margin to account for the existence of the action container
        ((FrameLayout.LayoutParams) expandContainer.getLayoutParams()).bottomMargin =
                actionsContainerHeight;
        expandContainer.requestLayout();
    }

    private void notifyRegisterInputConsumer() {
        Message m = Message.obtain();
        m.what = PipMenuActivityController.MESSAGE_REGISTER_INPUT_CONSUMER;
        sendMessage(m, "Could not notify controller to register input consumer");
    }

    private void notifyMenuVisibility(boolean visible) {
        mMenuVisible = visible;
        Message m = Message.obtain();
        m.what = PipMenuActivityController.MESSAGE_MENU_VISIBILITY_CHANGED;
        m.arg1 = visible ? 1 : 0;
        sendMessage(m, "Could not notify controller of PIP menu visibility");
    }

    private void expandPip() {
        // Do not notify menu visibility when hiding the menu, the controller will do this when it
        // handles the message
        hideMenu(() -> {
            sendEmptyMessage(PipMenuActivityController.MESSAGE_EXPAND_PIP,
                    "Could not notify controller to expand PIP");
        }, false /* notifyMenuVisibility */);
    }

    private void minimizePip() {
        sendEmptyMessage(PipMenuActivityController.MESSAGE_MINIMIZE_PIP,
                "Could not notify controller to minimize PIP");
    }

    private void dismissPip() {
        // Do not notify menu visibility when hiding the menu, the controller will do this when it
        // handles the message
        hideMenu(() -> {
            sendEmptyMessage(PipMenuActivityController.MESSAGE_DISMISS_PIP,
                    "Could not notify controller to dismiss PIP");
        }, false /* notifyMenuVisibility */);
    }

    private void notifyActivityCallback(Messenger callback) {
        Message m = Message.obtain();
        m.what = PipMenuActivityController.MESSAGE_UPDATE_ACTIVITY_CALLBACK;
        m.replyTo = callback;
        sendMessage(m, "Could not notify controller of activity finished");
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

    private void cancelDelayedFinish() {
        View v = getWindow().getDecorView();
        v.removeCallbacks(mFinishRunnable);
    }

    private void repostDelayedFinish(long delay) {
        View v = getWindow().getDecorView();
        v.removeCallbacks(mFinishRunnable);
        v.postDelayed(mFinishRunnable, delay);
    }
}
