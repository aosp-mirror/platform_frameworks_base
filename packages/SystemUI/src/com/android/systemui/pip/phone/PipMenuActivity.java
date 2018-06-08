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

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.provider.Settings.ACTION_PICTURE_IN_PICTURE_SETTINGS;

import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_ACTIONS;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_ALLOW_TIMEOUT;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_CONTROLLER_MESSENGER;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_WILL_RESIZE_MENU;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_DISMISS_FRACTION;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_MOVEMENT_BOUNDS;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_MENU_STATE;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_STACK_BOUNDS;

import static com.android.systemui.pip.phone.PipMenuActivityController.MENU_STATE_NONE;
import static com.android.systemui.pip.phone.PipMenuActivityController.MENU_STATE_CLOSE;
import static com.android.systemui.pip.phone.PipMenuActivityController.MENU_STATE_FULL;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.PendingIntent.CanceledException;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.graphics.Color;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;
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
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.component.HidePipMenuEvent;

import java.util.ArrayList;
import java.util.Collections;
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
    public static final int MESSAGE_ANIMATION_ENDED = 6;

    private static final long INITIAL_DISMISS_DELAY = 3500;
    private static final long POST_INTERACTION_DISMISS_DELAY = 2000;
    private static final long MENU_FADE_DURATION = 125;

    private static final float MENU_BACKGROUND_ALPHA = 0.3f;
    private static final float DISMISS_BACKGROUND_ALPHA = 0.6f;

    private static final float DISABLED_ACTION_ALPHA = 0.54f;

    private int mMenuState;
    private boolean mAllowMenuTimeout = true;
    private boolean mAllowTouches = true;

    private final List<RemoteAction> mActions = new ArrayList<>();

    private View mViewRoot;
    private Drawable mBackgroundDrawable;
    private View mMenuContainer;
    private LinearLayout mActionsGroup;
    private View mSettingsButton;
    private View mDismissButton;
    private ImageView mExpandButton;
    private int mBetweenActionPaddingLand;

    private AnimatorSet mMenuContainerAnimator;

    private ValueAnimator.AnimatorUpdateListener mMenuBgUpdateListener =
            new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    final float alpha = (float) animation.getAnimatedValue();
                    mBackgroundDrawable.setAlpha((int) (MENU_BACKGROUND_ALPHA*alpha*255));
                }
            };

    private PipTouchState mTouchState;
    private PointF mDownPosition = new PointF();
    private PointF mDownDelta = new PointF();
    private ViewConfiguration mViewConfig;
    private Handler mHandler = new Handler();
    private Messenger mToControllerMessenger;
    private Messenger mMessenger = new Messenger(new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_SHOW_MENU: {
                    final Bundle data = (Bundle) msg.obj;
                    showMenu(data.getInt(EXTRA_MENU_STATE),
                            data.getParcelable(EXTRA_STACK_BOUNDS),
                            data.getParcelable(EXTRA_MOVEMENT_BOUNDS),
                            data.getBoolean(EXTRA_ALLOW_TIMEOUT),
                            data.getBoolean(EXTRA_WILL_RESIZE_MENU));
                    break;
                }
                case MESSAGE_POKE_MENU:
                    cancelDelayedFinish();
                    break;
                case MESSAGE_HIDE_MENU:
                    hideMenu();
                    break;
                case MESSAGE_UPDATE_ACTIONS: {
                    final Bundle data = (Bundle) msg.obj;
                    final ParceledListSlice actions = data.getParcelable(EXTRA_ACTIONS);
                    setActions(data.getParcelable(EXTRA_STACK_BOUNDS), actions != null
                            ? actions.getList() : Collections.EMPTY_LIST);
                    break;
                }
                case MESSAGE_UPDATE_DISMISS_FRACTION: {
                    final Bundle data = (Bundle) msg.obj;
                    updateDismissFraction(data.getFloat(EXTRA_DISMISS_FRACTION));
                    break;
                }
                case MESSAGE_ANIMATION_ENDED: {
                    mAllowTouches = true;
                    break;
                }
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
        mTouchState = new PipTouchState(mViewConfig, mHandler, () -> {
            if (mMenuState == MENU_STATE_CLOSE) {
                showPipMenu();
            } else {
                expandPip();
            }
        });
        getWindow().addFlags(LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | LayoutParams.FLAG_SLIPPERY);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.pip_menu_activity);

        mBackgroundDrawable = new ColorDrawable(Color.BLACK);
        mBackgroundDrawable.setAlpha(0);
        mViewRoot = findViewById(R.id.background);
        mViewRoot.setBackground(mBackgroundDrawable);
        mMenuContainer = findViewById(R.id.menu_container);
        mMenuContainer.setAlpha(0);
        mMenuContainer.setOnTouchListener((v, event) -> {
            mTouchState.onTouchEvent(event);
            switch (event.getAction()) {
                case MotionEvent.ACTION_UP:
                    if (mTouchState.isDoubleTap() || mMenuState == MENU_STATE_FULL) {
                        // Expand to fullscreen if this is a double tap or we are already expanded
                        expandPip();
                    } else if (!mTouchState.isWaitingForDoubleTap()) {
                        // User has stalled long enough for this not to be a drag or a double tap,
                        // just expand the menu if necessary
                        if (mMenuState == MENU_STATE_CLOSE) {
                            showPipMenu();
                        }
                    } else {
                        // Next touch event _may_ be the second tap for the double-tap, schedule a
                        // fallback runnable to trigger the menu if no touch event occurs before the
                        // next tap
                        mTouchState.scheduleDoubleTapTimeoutCallback();
                    }
                    break;
            }
            return true;
        });
        mSettingsButton = findViewById(R.id.settings);
        mSettingsButton.setAlpha(0);
        mSettingsButton.setOnClickListener((v) -> {
            showSettings();
        });
        mDismissButton = findViewById(R.id.dismiss);
        mDismissButton.setAlpha(0);
        mDismissButton.setOnClickListener((v) -> {
            dismissPip();
        });
        mActionsGroup = findViewById(R.id.actions_group);
        mBetweenActionPaddingLand = getResources().getDimensionPixelSize(
                R.dimen.pip_between_action_padding_land);
        mExpandButton = findViewById(R.id.expand_button);

        updateFromIntent(getIntent());
        setTitle(R.string.pip_menu_title);
        setDisablePreviewScreenshots(true);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        updateFromIntent(intent);
    }

    @Override
    public void onUserInteraction() {
        if (mAllowMenuTimeout) {
            repostDelayedFinish(POST_INTERACTION_DISMISS_DELAY);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

        // If another task is starting on top of the menu, then hide and finish it so that it can be
        // recreated on the top next time it starts
        hideMenu();
    }

    @Override
    protected void onStop() {
        super.onStop();

        cancelDelayedFinish();
        EventBus.getDefault().unregister(this);
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
        if (!mAllowTouches) {
            return super.dispatchTouchEvent(ev);
        }

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
                if (mDownDelta.length() > mViewConfig.getScaledTouchSlop()
                        && mMenuState != MENU_STATE_NONE) {
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

    public final void onBusEvent(HidePipMenuEvent event) {
        if (mMenuState != MENU_STATE_NONE) {
            // If the menu is visible in either the closed or full state, then hide the menu and
            // trigger the animation trigger afterwards
            event.getAnimationTrigger().increment();
            hideMenu(() -> {
                mHandler.post(() -> {
                    event.getAnimationTrigger().decrement();
                });
            }, true /* notifyMenuVisibility */, false /* isDismissing */);
        }
    }

    private void showMenu(int menuState, Rect stackBounds, Rect movementBounds,
            boolean allowMenuTimeout, boolean resizeMenuOnShow) {
        mAllowMenuTimeout = allowMenuTimeout;
        if (mMenuState != menuState) {
            // Disallow touches if the menu needs to resize while showing, and we are transitioning
            // to/from a full menu state.
            boolean disallowTouchesUntilAnimationEnd = resizeMenuOnShow &&
                    (mMenuState == MENU_STATE_FULL || menuState == MENU_STATE_FULL);
            mAllowTouches = !disallowTouchesUntilAnimationEnd;
            cancelDelayedFinish();
            updateActionViews(stackBounds);
            if (mMenuContainerAnimator != null) {
                mMenuContainerAnimator.cancel();
            }
            notifyMenuStateChange(menuState);
            mMenuContainerAnimator = new AnimatorSet();
            ObjectAnimator menuAnim = ObjectAnimator.ofFloat(mMenuContainer, View.ALPHA,
                    mMenuContainer.getAlpha(), 1f);
            menuAnim.addUpdateListener(mMenuBgUpdateListener);
            ObjectAnimator settingsAnim = ObjectAnimator.ofFloat(mSettingsButton, View.ALPHA,
                    mSettingsButton.getAlpha(), 1f);
            ObjectAnimator dismissAnim = ObjectAnimator.ofFloat(mDismissButton, View.ALPHA,
                    mDismissButton.getAlpha(), 1f);
            if (menuState == MENU_STATE_FULL) {
                mMenuContainerAnimator.playTogether(menuAnim, settingsAnim, dismissAnim);
            } else {
                mMenuContainerAnimator.playTogether(dismissAnim);
            }
            mMenuContainerAnimator.setInterpolator(Interpolators.ALPHA_IN);
            mMenuContainerAnimator.setDuration(MENU_FADE_DURATION);
            if (allowMenuTimeout) {
                mMenuContainerAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        repostDelayedFinish(INITIAL_DISMISS_DELAY);
                    }
                });
            }
            mMenuContainerAnimator.start();
        } else {
            // If we are already visible, then just start the delayed dismiss and unregister any
            // existing input consumers from the previous drag
            if (allowMenuTimeout) {
                repostDelayedFinish(POST_INTERACTION_DISMISS_DELAY);
            }
            notifyUnregisterInputConsumer();
        }
    }

    private void hideMenu() {
        hideMenu(null /* animationFinishedRunnable */, true /* notifyMenuVisibility */,
                false /* isDismissing */);
    }

    private void hideMenu(final Runnable animationFinishedRunnable, boolean notifyMenuVisibility,
            boolean isDismissing) {
        if (mMenuState != MENU_STATE_NONE) {
            cancelDelayedFinish();
            if (notifyMenuVisibility) {
                notifyMenuStateChange(MENU_STATE_NONE);
            }
            mMenuContainerAnimator = new AnimatorSet();
            ObjectAnimator menuAnim = ObjectAnimator.ofFloat(mMenuContainer, View.ALPHA,
                    mMenuContainer.getAlpha(), 0f);
            menuAnim.addUpdateListener(mMenuBgUpdateListener);
            ObjectAnimator settingsAnim = ObjectAnimator.ofFloat(mSettingsButton, View.ALPHA,
                    mSettingsButton.getAlpha(), 0f);
            ObjectAnimator dismissAnim = ObjectAnimator.ofFloat(mDismissButton, View.ALPHA,
                    mDismissButton.getAlpha(), 0f);
            mMenuContainerAnimator.playTogether(menuAnim, settingsAnim, dismissAnim);
            mMenuContainerAnimator.setInterpolator(Interpolators.ALPHA_OUT);
            mMenuContainerAnimator.setDuration(MENU_FADE_DURATION);
            mMenuContainerAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animationFinishedRunnable != null) {
                        animationFinishedRunnable.run();
                    }

                    if (!isDismissing) {
                        // If we are dismissing the PiP, then don't try to pre-emptively finish the
                        // menu activity
                        finish();
                    }
                }
            });
            mMenuContainerAnimator.start();
        } else {
            // If the menu is not visible, just finish now
            finish();
        }
    }

    private void updateFromIntent(Intent intent) {
        mToControllerMessenger = intent.getParcelableExtra(EXTRA_CONTROLLER_MESSENGER);
        if (mToControllerMessenger == null) {
            Log.w(TAG, "Controller messenger is null. Stopping.");
            finish();
            return;
        }
        notifyActivityCallback(mMessenger);

        // Register for HidePipMenuEvents once we notify the controller of this activity
        EventBus.getDefault().register(this);

        ParceledListSlice actions = intent.getParcelableExtra(EXTRA_ACTIONS);
        if (actions != null) {
            mActions.clear();
            mActions.addAll(actions.getList());
        }

        final int menuState = intent.getIntExtra(EXTRA_MENU_STATE, MENU_STATE_NONE);
        if (menuState != MENU_STATE_NONE) {
            Rect stackBounds = intent.getParcelableExtra(EXTRA_STACK_BOUNDS);
            Rect movementBounds = intent.getParcelableExtra(EXTRA_MOVEMENT_BOUNDS);
            boolean allowMenuTimeout = intent.getBooleanExtra(EXTRA_ALLOW_TIMEOUT, true);
            boolean willResizeMenu = intent.getBooleanExtra(EXTRA_WILL_RESIZE_MENU, false);
            showMenu(menuState, stackBounds, movementBounds, allowMenuTimeout, willResizeMenu);
        }
    }

    private void setActions(Rect stackBounds, List<RemoteAction> actions) {
        mActions.clear();
        mActions.addAll(actions);
        updateActionViews(stackBounds);
    }

    private void updateActionViews(Rect stackBounds) {
        ViewGroup expandContainer = findViewById(R.id.expand_container);
        ViewGroup actionsContainer = findViewById(R.id.actions_container);
        actionsContainer.setOnTouchListener((v, ev) -> {
            // Do nothing, prevent click through to parent
            return true;
        });

        if (mActions.isEmpty() || mMenuState == MENU_STATE_CLOSE) {
            actionsContainer.setVisibility(View.INVISIBLE);
        } else {
            actionsContainer.setVisibility(View.VISIBLE);
            if (mActionsGroup != null) {
                // Ensure we have as many buttons as actions
                final LayoutInflater inflater = LayoutInflater.from(this);
                while (mActionsGroup.getChildCount() < mActions.size()) {
                    final ImageView actionView = (ImageView) inflater.inflate(
                            R.layout.pip_menu_action, mActionsGroup, false);
                    mActionsGroup.addView(actionView);
                }

                // Update the visibility of all views
                for (int i = 0; i < mActionsGroup.getChildCount(); i++) {
                    mActionsGroup.getChildAt(i).setVisibility(i < mActions.size()
                            ? View.VISIBLE
                            : View.GONE);
                }

                // Recreate the layout
                final boolean isLandscapePip = stackBounds != null &&
                        (stackBounds.width() > stackBounds.height());
                for (int i = 0; i < mActions.size(); i++) {
                    final RemoteAction action = mActions.get(i);
                    final ImageView actionView = (ImageView) mActionsGroup.getChildAt(i);

                    // TODO: Check if the action drawable has changed before we reload it
                    action.getIcon().loadDrawableAsync(this, d -> {
                        d.setTint(Color.WHITE);
                        actionView.setImageDrawable(d);
                    }, mHandler);
                    actionView.setContentDescription(action.getContentDescription());
                    if (action.isEnabled()) {
                        actionView.setOnClickListener(v -> {
                            try {
                                action.getActionIntent().send();
                            } catch (CanceledException e) {
                                Log.w(TAG, "Failed to send action", e);
                            }
                        });
                    }
                    actionView.setEnabled(action.isEnabled());
                    actionView.setAlpha(action.isEnabled() ? 1f : DISABLED_ACTION_ALPHA);

                    // Update the margin between actions
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                            actionView.getLayoutParams();
                    lp.leftMargin = (isLandscapePip && i > 0) ? mBetweenActionPaddingLand : 0;
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
        int alpha;
        final float menuAlpha = 1 - fraction;
        if (mMenuState == MENU_STATE_FULL) {
            mMenuContainer.setAlpha(menuAlpha);
            mSettingsButton.setAlpha(menuAlpha);
            mDismissButton.setAlpha(menuAlpha);
            final float interpolatedAlpha =
                    MENU_BACKGROUND_ALPHA * menuAlpha + DISMISS_BACKGROUND_ALPHA * fraction;
            alpha = (int) (interpolatedAlpha * 255);
        } else {
            if (mMenuState == MENU_STATE_CLOSE) {
                mDismissButton.setAlpha(menuAlpha);
            }
            alpha = (int) (fraction * DISMISS_BACKGROUND_ALPHA * 255);
        }
        mBackgroundDrawable.setAlpha(alpha);
    }

    private void notifyRegisterInputConsumer() {
        Message m = Message.obtain();
        m.what = PipMenuActivityController.MESSAGE_REGISTER_INPUT_CONSUMER;
        sendMessage(m, "Could not notify controller to register input consumer");
    }

    private void notifyUnregisterInputConsumer() {
        Message m = Message.obtain();
        m.what = PipMenuActivityController.MESSAGE_UNREGISTER_INPUT_CONSUMER;
        sendMessage(m, "Could not notify controller to unregister input consumer");
    }

    private void notifyMenuStateChange(int menuState) {
        mMenuState = menuState;
        Message m = Message.obtain();
        m.what = PipMenuActivityController.MESSAGE_MENU_STATE_CHANGED;
        m.arg1 = menuState;
        sendMessage(m, "Could not notify controller of PIP menu visibility");
    }

    private void expandPip() {
        // Do not notify menu visibility when hiding the menu, the controller will do this when it
        // handles the message
        hideMenu(() -> {
            sendEmptyMessage(PipMenuActivityController.MESSAGE_EXPAND_PIP,
                    "Could not notify controller to expand PIP");
        }, false /* notifyMenuVisibility */, false /* isDismissing */);
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
        }, false /* notifyMenuVisibility */, true /* isDismissing */);
    }

    private void showPipMenu() {
        Message m = Message.obtain();
        m.what = PipMenuActivityController.MESSAGE_SHOW_MENU;
        sendMessage(m, "Could not notify controller to show PIP menu");
    }

    private void showSettings() {
        final Pair<ComponentName, Integer> topPipActivityInfo =
                PipUtils.getTopPinnedActivity(this, ActivityManager.getService());
        if (topPipActivityInfo.first != null) {
            final UserHandle user = UserHandle.of(topPipActivityInfo.second);
            final Intent settingsIntent = new Intent(ACTION_PICTURE_IN_PICTURE_SETTINGS,
                    Uri.fromParts("package", topPipActivityInfo.first.getPackageName(), null));
            settingsIntent.putExtra(Intent.EXTRA_USER_HANDLE, user);
            settingsIntent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(settingsIntent);
        }
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
        if (mToControllerMessenger == null) {
            return;
        }
        try {
            mToControllerMessenger.send(m);
        } catch (RemoteException e) {
            Log.e(TAG, errorMsg, e);
        }
    }

    private void cancelDelayedFinish() {
        mHandler.removeCallbacks(mFinishRunnable);
    }

    private void repostDelayedFinish(long delay) {
        mHandler.removeCallbacks(mFinishRunnable);
        mHandler.postDelayed(mFinishRunnable, delay);
    }
}
