/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.pip.tv;

import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.KEYCODE_BACK;
import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static android.view.KeyEvent.KEYCODE_ENTER;

import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.text.Annotation;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannedString;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A View that represents Pip Menu on TV. It's responsible for displaying 3 ever-present Pip Menu
 * actions: Fullscreen, Move and Close, but could also display "additional" actions, that may be set
 * via a {@link #setAdditionalActions(List, Handler)} call.
 */
public class TvPipMenuView extends FrameLayout implements View.OnClickListener {
    private static final String TAG = "TvPipMenuView";
    private static final boolean DEBUG = TvPipController.DEBUG;

    @Nullable
    private Listener mListener;

    private final LinearLayout mActionButtonsContainer;
    private final View mMenuFrameView;
    private final List<TvPipMenuActionButton> mAdditionalButtons = new ArrayList<>();
    private final View mPipFrameView;
    private final View mPipView;
    private final TextView mEduTextView;
    private final View mEduTextContainerView;
    private final int mPipMenuOuterSpace;
    private final int mPipMenuBorderWidth;
    private final int mEduTextFadeExitAnimationDurationMs;
    private final int mEduTextSlideExitAnimationDurationMs;
    private int mEduTextHeight;

    private final ImageView mArrowUp;
    private final ImageView mArrowRight;
    private final ImageView mArrowDown;
    private final ImageView mArrowLeft;

    private final ViewGroup mScrollView;
    private final ViewGroup mHorizontalScrollView;

    private Rect mCurrentPipBounds;

    private final TvPipMenuActionButton mExpandButton;
    private final TvPipMenuActionButton mCloseButton;

    private final int mPipMenuFadeAnimationDuration;

    public TvPipMenuView(@NonNull Context context) {
        this(context, null);
    }

    public TvPipMenuView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TvPipMenuView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public TvPipMenuView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        inflate(context, R.layout.tv_pip_menu, this);

        mActionButtonsContainer = findViewById(R.id.tv_pip_menu_action_buttons);
        mActionButtonsContainer.findViewById(R.id.tv_pip_menu_fullscreen_button)
                .setOnClickListener(this);

        mCloseButton = mActionButtonsContainer.findViewById(R.id.tv_pip_menu_close_button);
        mCloseButton.setOnClickListener(this);
        mCloseButton.setIsCustomCloseAction(true);

        mActionButtonsContainer.findViewById(R.id.tv_pip_menu_move_button)
                .setOnClickListener(this);
        mExpandButton = findViewById(R.id.tv_pip_menu_expand_button);
        mExpandButton.setOnClickListener(this);

        mScrollView = findViewById(R.id.tv_pip_menu_scroll);
        mHorizontalScrollView = findViewById(R.id.tv_pip_menu_horizontal_scroll);

        mMenuFrameView = findViewById(R.id.tv_pip_menu_frame);
        mPipFrameView = findViewById(R.id.tv_pip_border);
        mPipView = findViewById(R.id.tv_pip);

        mArrowUp = findViewById(R.id.tv_pip_menu_arrow_up);
        mArrowRight = findViewById(R.id.tv_pip_menu_arrow_right);
        mArrowDown = findViewById(R.id.tv_pip_menu_arrow_down);
        mArrowLeft = findViewById(R.id.tv_pip_menu_arrow_left);

        mEduTextView = findViewById(R.id.tv_pip_menu_edu_text);
        mEduTextContainerView = findViewById(R.id.tv_pip_menu_edu_text_container);

        mPipMenuFadeAnimationDuration = context.getResources()
                .getInteger(R.integer.pip_menu_fade_animation_duration);
        mPipMenuOuterSpace = context.getResources()
                .getDimensionPixelSize(R.dimen.pip_menu_outer_space);
        mPipMenuBorderWidth = context.getResources()
                .getDimensionPixelSize(R.dimen.pip_menu_border_width);
        mEduTextHeight = context.getResources()
                .getDimensionPixelSize(R.dimen.pip_menu_edu_text_view_height);
        mEduTextFadeExitAnimationDurationMs = context.getResources()
                .getInteger(R.integer.pip_edu_text_view_exit_animation_duration_ms);
        mEduTextSlideExitAnimationDurationMs = context.getResources()
                .getInteger(R.integer.pip_edu_text_window_exit_animation_duration_ms);

        initEduText();
    }

    void initEduText() {
        final SpannedString eduText = (SpannedString) getResources().getText(R.string.pip_edu_text);
        final SpannableString spannableString = new SpannableString(eduText);
        Arrays.stream(eduText.getSpans(0, eduText.length(), Annotation.class)).findFirst()
                .ifPresent(annotation -> {
                    final Drawable icon =
                            getResources().getDrawable(R.drawable.home_icon, mContext.getTheme());
                    if (icon != null) {
                        icon.mutate();
                        icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
                        spannableString.setSpan(new CenteredImageSpan(icon),
                                eduText.getSpanStart(annotation),
                                eduText.getSpanEnd(annotation),
                                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                });

        mEduTextView.setText(spannableString);
    }

    void setEduTextActive(boolean active) {
        mEduTextView.setSelected(active);
    }

    void hideEduText() {
        final ValueAnimator heightAnimation = ValueAnimator.ofInt(mEduTextHeight, 0);
        heightAnimation.setDuration(mEduTextSlideExitAnimationDurationMs);
        heightAnimation.setInterpolator(TvPipInterpolators.BROWSE);
        heightAnimation.addUpdateListener(animator -> {
            mEduTextHeight = (int) animator.getAnimatedValue();
        });
        mEduTextView.animate()
                .alpha(0f)
                .setInterpolator(TvPipInterpolators.EXIT)
                .setDuration(mEduTextFadeExitAnimationDurationMs)
                .withEndAction(() -> {
                    mEduTextContainerView.setVisibility(GONE);
                }).start();
        heightAnimation.start();
    }

    void updateLayout(Rect updatedPipBounds) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: update menu layout: %s", TAG, updatedPipBounds.toShortString());

        boolean previouslyVertical =
                mCurrentPipBounds != null && mCurrentPipBounds.height() > mCurrentPipBounds.width();
        boolean vertical = updatedPipBounds.height() > updatedPipBounds.width();

        mCurrentPipBounds = updatedPipBounds;

        updatePipFrameBounds();

        if (previouslyVertical == vertical) {
            if (DEBUG) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: no update for menu layout", TAG);
            }
            return;
        } else {
            if (DEBUG) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: change menu layout to vertical: %b", TAG, vertical);
            }
        }

        if (vertical) {
            mHorizontalScrollView.removeView(mActionButtonsContainer);
            mScrollView.addView(mActionButtonsContainer);
        } else {
            mScrollView.removeView(mActionButtonsContainer);
            mHorizontalScrollView.addView(mActionButtonsContainer);
        }
        mActionButtonsContainer.setOrientation(vertical ? LinearLayout.VERTICAL
                : LinearLayout.HORIZONTAL);

        mScrollView.setVisibility(vertical ? VISIBLE : GONE);
        mHorizontalScrollView.setVisibility(vertical ? GONE : VISIBLE);
    }

    Rect getPipMenuContainerBounds(Rect pipBounds) {
        final Rect menuUiBounds = new Rect(pipBounds);
        menuUiBounds.inset(-mPipMenuOuterSpace, -mPipMenuOuterSpace);
        menuUiBounds.bottom += mEduTextHeight;
        return menuUiBounds;
    }

    /**
     * Update mPipFrameView's bounds according to the new pip window bounds. We can't
     * make mPipFrameView match_parent, because the pip menu might contain other content around
     * the pip window (e.g. edu text).
     * TvPipMenuView needs to account for this so that it can draw a white border around the whole
     * pip menu when it gains focus.
     */
    private void updatePipFrameBounds() {
        final ViewGroup.LayoutParams pipFrameParams = mPipFrameView.getLayoutParams();
        if (pipFrameParams != null) {
            pipFrameParams.width = mCurrentPipBounds.width() + 2 * mPipMenuBorderWidth;
            pipFrameParams.height = mCurrentPipBounds.height() + 2 * mPipMenuBorderWidth;
            mPipFrameView.setLayoutParams(pipFrameParams);
        }

        final ViewGroup.LayoutParams pipViewParams = mPipView.getLayoutParams();
        if (pipViewParams != null) {
            pipViewParams.width = mCurrentPipBounds.width();
            pipViewParams.height = mCurrentPipBounds.height();
            mPipView.setLayoutParams(pipViewParams);
        }


    }

    void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    void setExpandedModeEnabled(boolean enabled) {
        mExpandButton.setVisibility(enabled ? VISIBLE : GONE);
    }

    void setIsExpanded(boolean expanded) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: setIsExpanded, expanded: %b", TAG, expanded);
        }
        mExpandButton.setImageResource(
                expanded ? R.drawable.pip_ic_collapse : R.drawable.pip_ic_expand);
        mExpandButton.setTextAndDescription(
                expanded ? R.string.pip_collapse : R.string.pip_expand);
    }

    /**
     * @param gravity for the arrow hints
     */
    void showMoveMenu(int gravity) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: showMoveMenu()", TAG);
        }
        showButtonsMenu(false);
        showMovementHints(gravity);
        setFrameHighlighted(true);
    }

    void showButtonsMenu() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: showButtonsMenu()", TAG);
        }
        showButtonsMenu(true);
        hideMovementHints();
        setFrameHighlighted(true);
    }

    /**
     * Hides all menu views, including the menu frame.
     */
    void hideAllUserControls() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: hideAllUserControls()", TAG);
        }
        showButtonsMenu(false);
        hideMovementHints();
        setFrameHighlighted(false);
    }

    private void animateAlphaTo(float alpha, View view) {
        if (view.getAlpha() == alpha) {
            return;
        }
        view.animate()
                .alpha(alpha)
                .setInterpolator(alpha == 0f ? TvPipInterpolators.EXIT : TvPipInterpolators.ENTER)
                .setDuration(mPipMenuFadeAnimationDuration)
                .withStartAction(() -> {
                    if (alpha != 0) {
                        view.setVisibility(VISIBLE);
                    }
                })
                .withEndAction(() -> {
                    if (alpha == 0) {
                        view.setVisibility(GONE);
                    }
                });
    }

    void setAdditionalActions(List<RemoteAction> actions, RemoteAction closeAction,
            Handler mainHandler) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: setAdditionalActions()", TAG);
        }

        // Replace system close action with custom close action if available
        if (closeAction != null) {
            setActionForButton(closeAction, mCloseButton, mainHandler);
        } else {
            mCloseButton.setTextAndDescription(R.string.pip_close);
            mCloseButton.setImageResource(R.drawable.pip_ic_close_white);
        }
        mCloseButton.setIsCustomCloseAction(closeAction != null);
        // Make sure the close action is always enabled
        mCloseButton.setEnabled(true);

        // Make sure we exactly as many additional buttons as we have actions to display.
        final int actionsNumber = actions.size();
        int buttonsNumber = mAdditionalButtons.size();
        if (actionsNumber > buttonsNumber) {
            // Add buttons until we have enough to display all of the actions.
            while (actionsNumber > buttonsNumber) {
                TvPipMenuActionButton button = new TvPipMenuActionButton(mContext);
                button.setOnClickListener(this);

                mActionButtonsContainer.addView(button,
                        mActionButtonsContainer.getChildCount() - 1);
                mAdditionalButtons.add(button);

                buttonsNumber++;
            }
        } else if (actionsNumber < buttonsNumber) {
            // Hide buttons until we as many as the actions.
            while (actionsNumber < buttonsNumber) {
                final View button = mAdditionalButtons.get(buttonsNumber - 1);
                button.setVisibility(View.GONE);
                button.setTag(null);

                buttonsNumber--;
            }
        }

        // "Assign" actions to the buttons.
        for (int index = 0; index < actionsNumber; index++) {
            final RemoteAction action = actions.get(index);
            final TvPipMenuActionButton button = mAdditionalButtons.get(index);

            // Remove action if it matches the custom close action.
            if (actionsMatch(action, closeAction)) {
                button.setVisibility(GONE);
                continue;
            }
            setActionForButton(action, button, mainHandler);
        }
    }

    /**
     * Checks whether title, description and intent match.
     * Comparing icons would be good, but using equals causes false negatives
     */
    private boolean actionsMatch(RemoteAction action1, RemoteAction action2) {
        if (action1 == action2) return true;
        if (action1 == null || action2 == null) return false;
        return Objects.equals(action1.getTitle(), action2.getTitle())
                && Objects.equals(action1.getContentDescription(), action2.getContentDescription())
                && Objects.equals(action1.getActionIntent(), action2.getActionIntent());
    }

    private void setActionForButton(RemoteAction action, TvPipMenuActionButton button,
            Handler mainHandler) {
        button.setVisibility(View.VISIBLE); // Ensure the button is visible.
        button.setTextAndDescription(action.getContentDescription());
        button.setEnabled(action.isEnabled());
        button.setTag(action);
        action.getIcon().loadDrawableAsync(mContext, button::setImageDrawable, mainHandler);
    }

    @Nullable
    SurfaceControl getWindowSurfaceControl() {
        final ViewRootImpl root = getViewRootImpl();
        if (root == null) {
            return null;
        }
        final SurfaceControl out = root.getSurfaceControl();
        if (out != null && out.isValid()) {
            return out;
        }
        return null;
    }

    @Override
    public void onClick(View v) {
        if (mListener == null) return;

        final int id = v.getId();
        if (id == R.id.tv_pip_menu_fullscreen_button) {
            mListener.onFullscreenButtonClick();
        } else if (id == R.id.tv_pip_menu_move_button) {
            mListener.onEnterMoveMode();
        } else if (id == R.id.tv_pip_menu_close_button) {
            mListener.onCloseButtonClick();
        } else if (id == R.id.tv_pip_menu_expand_button) {
            mListener.onToggleExpandedMode();
        } else {
            // This should be an "additional action"
            final RemoteAction action = (RemoteAction) v.getTag();
            if (action != null) {
                try {
                    action.getActionIntent().send();
                } catch (PendingIntent.CanceledException e) {
                    ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                            "%s: Failed to send action, %s", TAG, e);
                }
            } else {
                ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: RemoteAction is null", TAG);
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: dispatchKeyEvent, action: %d, keycode: %d",
                    TAG, event.getAction(), event.getKeyCode());
        }
        if (mListener != null && event.getAction() == ACTION_UP) {
            switch (event.getKeyCode()) {
                case KEYCODE_BACK:
                    mListener.onBackPress();
                    return true;
                case KEYCODE_DPAD_UP:
                case KEYCODE_DPAD_DOWN:
                case KEYCODE_DPAD_LEFT:
                case KEYCODE_DPAD_RIGHT:
                    return mListener.onPipMovement(event.getKeyCode()) || super.dispatchKeyEvent(
                            event);
                case KEYCODE_ENTER:
                case KEYCODE_DPAD_CENTER:
                    return mListener.onExitMoveMode() || super.dispatchKeyEvent(event);
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Shows user hints for moving the PiP, e.g. arrows.
     */
    public void showMovementHints(int gravity) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: showMovementHints(), position: %s", TAG, Gravity.toString(gravity));
        }

        animateAlphaTo(checkGravity(gravity, Gravity.BOTTOM) ? 1f : 0f, mArrowUp);
        animateAlphaTo(checkGravity(gravity, Gravity.TOP) ? 1f : 0f, mArrowDown);
        animateAlphaTo(checkGravity(gravity, Gravity.RIGHT) ? 1f : 0f, mArrowLeft);
        animateAlphaTo(checkGravity(gravity, Gravity.LEFT) ? 1f : 0f, mArrowRight);
    }

    private boolean checkGravity(int gravity, int feature) {
        return (gravity & feature) == feature;
    }

    /**
     * Hides user hints for moving the PiP, e.g. arrows.
     */
    public void hideMovementHints() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: hideMovementHints()", TAG);
        }
        animateAlphaTo(0, mArrowUp);
        animateAlphaTo(0, mArrowRight);
        animateAlphaTo(0, mArrowDown);
        animateAlphaTo(0, mArrowLeft);
    }

    /**
     * Show or hide the pip buttons menu.
     */
    public void showButtonsMenu(boolean show) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: showUserActions: %b", TAG, show);
        }
        animateAlphaTo(show ? 1 : 0, mActionButtonsContainer);
    }

    private void setFrameHighlighted(boolean highlighted) {
        mMenuFrameView.setActivated(highlighted);
    }

    interface Listener {

        void onBackPress();

        void onEnterMoveMode();

        /**
         * Called when a button for exiting move mode was pressed.
         *
         * @return true if the event was handled or false if the key event should be handled by the
         * next receiver.
         */
        boolean onExitMoveMode();

        /**
         * @return whether pip movement was handled.
         */
        boolean onPipMovement(int keycode);

        void onCloseButtonClick();

        void onFullscreenButtonClick();

        void onToggleExpandedMode();
    }
}
