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

import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_ACTIONS;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_CONTROLLER_MESSENGER;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_MOVEMENT_BOUNDS;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_STACK_BOUNDS;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_SHOW_MENU;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.PendingIntent.CanceledException;
import android.app.RemoteAction;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityManager;
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
    public static final int MESSAGE_UPDATE_DISMISS_FRACTION = 5;

    private static final long INITIAL_DISMISS_DELAY = 2000;
    private static final long POST_INTERACTION_DISMISS_DELAY = 1500;
    private static final long MENU_FADE_DURATION = 125;

    private static final float MENU_BACKGROUND_ALPHA = 0.3f;
    private static final float DISMISS_BACKGROUND_ALPHA = 0.8f;

    private boolean mMenuVisible;
    private final List<RemoteAction> mActions = new ArrayList<>();
    private View mViewRoot;
    private Drawable mBackgroundDrawable;
    private View mMenuContainer;
    private LinearLayout mActionsGroup;
    private View mDismissButton;
    private ImageView mExpandButton;
    private int mBetweenActionPaddingLand;

    private ObjectAnimator mMenuContainerAnimator;
    private ValueAnimator.AnimatorUpdateListener mMenuBgUpdateListener =
            new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    final float alpha = (float) animation.getAnimatedValue();
                    mBackgroundDrawable.setAlpha((int) (MENU_BACKGROUND_ALPHA*alpha*255));
                }
            };

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
                    Pair<Rect, Rect> bounds = (Pair<Rect, Rect>) msg.obj;
                    showMenu(bounds.first, bounds.second);
                    break;
                case MESSAGE_POKE_MENU:
                    cancelDelayedFinish();
                    break;
                case MESSAGE_HIDE_MENU:
                    hideMenu();
                    break;
                case MESSAGE_UPDATE_ACTIONS:
                    Pair<Rect, ParceledListSlice> data = (Pair<Rect, ParceledListSlice>) msg.obj;
                    setActions(data.first, data.second.getList());
                    break;
                case MESSAGE_UPDATE_DISMISS_FRACTION:
                    float fraction = (float) msg.obj;
                    updateDismissFraction(fraction);
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

        mBackgroundDrawable = new ColorDrawable(Color.BLACK);
        mBackgroundDrawable.setAlpha(0);
        mViewRoot = findViewById(R.id.background);
        mViewRoot.setBackground(mBackgroundDrawable);
        mMenuContainer = findViewById(R.id.menu_container);
        mMenuContainer.setAlpha(0);
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
        mExpandButton = (ImageView) findViewById(R.id.expand_button);

        updateFromIntent(getIntent());
        setTitle(R.string.pip_menu_title);
        notifyActivityCallback(mMessenger);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        updateFromIntent(intent);
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

    private void showMenu(Rect stackBounds, Rect movementBounds) {
        if (!mMenuVisible) {
            setVisible(true);
            updateActionViews(stackBounds);
            if (mMenuContainerAnimator != null) {
                mMenuContainerAnimator.cancel();
            }
            notifyMenuVisibility(true);
            updateExpandButtonFromBounds(stackBounds, movementBounds);
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
            mMenuContainerAnimator.addUpdateListener(mMenuBgUpdateListener);
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
                    setVisible(false);
                }
            });
            mMenuContainerAnimator.addUpdateListener(mMenuBgUpdateListener);
            mMenuContainerAnimator.start();
        }
    }

    private void updateFromIntent(Intent intent) {
        mToControllerMessenger = intent.getParcelableExtra(EXTRA_CONTROLLER_MESSENGER);
        ParceledListSlice actions = intent.getParcelableExtra(EXTRA_ACTIONS);
        if (actions != null) {
            mActions.clear();
            mActions.addAll(actions.getList());
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_MENU, false)) {
            Rect stackBounds = Rect.unflattenFromString(intent.getStringExtra(EXTRA_STACK_BOUNDS));
            Rect movementBounds = Rect.unflattenFromString(intent.getStringExtra(
                    EXTRA_MOVEMENT_BOUNDS));
            showMenu(stackBounds, movementBounds);
        }
    }

    private void updateExpandButtonFromBounds(Rect stackBounds, Rect movementBounds) {
        if (stackBounds == null) {
            return;
        }

        boolean isLandscapePip = stackBounds.width() > stackBounds.height();
        boolean left = stackBounds.left < movementBounds.centerX();
        boolean top = stackBounds.top < movementBounds.centerY();
        boolean expandL = (left && top) || (!left && !top);
        int iconResId;
        if (isLandscapePip) {
            iconResId = expandL ? R.drawable.pip_expand_ll : R.drawable.pip_expand_lr;
        } else {
            iconResId = expandL ? R.drawable.pip_expand_pl : R.drawable.pip_expand_pr;
        }
        mExpandButton.setImageResource(iconResId);
    }

    private void setActions(Rect stackBounds, List<RemoteAction> actions) {
        mActions.clear();
        mActions.addAll(actions);
        updateActionViews(stackBounds);
    }

    private void updateActionViews(Rect stackBounds) {
        ViewGroup expandContainer = (ViewGroup) findViewById(R.id.expand_container);
        ViewGroup actionsContainer = (ViewGroup) findViewById(R.id.actions_container);
        actionsContainer.setOnTouchListener((v, ev) -> {
            // Do nothing, prevent click through to parent
            return true;
        });

        if (mActions.isEmpty()) {
            actionsContainer.setVisibility(View.INVISIBLE);
        } else {
            actionsContainer.setVisibility(View.VISIBLE);
            if (mActionsGroup != null) {
                mActionsGroup.removeAllViews();

                // Recreate the layout
                final boolean isLandscapePip = stackBounds != null &&
                        (stackBounds.width() > stackBounds.height());
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

            // Update the expand container margin to adjust the center of the expand button to
            // account for the existence of the action container
            FrameLayout.LayoutParams expandedLp =
                    (FrameLayout.LayoutParams) expandContainer.getLayoutParams();
            expandedLp.topMargin = getResources().getDimensionPixelSize(
                    R.dimen.pip_action_padding);
            expandedLp.bottomMargin = getResources().getDimensionPixelSize(
                    R.dimen.pip_expand_container_edge_margin);
            expandContainer.requestLayout();
        }
    }

    private void updateDismissFraction(float fraction) {
        setVisible(true);
        int alpha;
        if (mMenuVisible) {
            mMenuContainer.setAlpha(1-fraction);
            final float interpolatedAlpha =
                    MENU_BACKGROUND_ALPHA * (1.0f - fraction) + DISMISS_BACKGROUND_ALPHA * fraction;
            alpha = (int) (interpolatedAlpha*255);
        } else {
            alpha = (int) (fraction*DISMISS_BACKGROUND_ALPHA*255);
        }
        mBackgroundDrawable.setAlpha(alpha);
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
