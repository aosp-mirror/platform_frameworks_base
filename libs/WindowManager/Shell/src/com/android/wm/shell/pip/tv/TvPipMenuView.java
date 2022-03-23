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

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;
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

    private final ImageView mArrowUp;
    private final ImageView mArrowRight;
    private final ImageView mArrowDown;
    private final ImageView mArrowLeft;

    private final ViewGroup mScrollView;
    private final ViewGroup mHorizontalScrollView;

    private Rect mCurrentBounds;

    private final TvPipMenuActionButton mExpandButton;
    private final TvPipMenuActionButton mCloseButton;

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

        mArrowUp = findViewById(R.id.tv_pip_menu_arrow_up);
        mArrowRight = findViewById(R.id.tv_pip_menu_arrow_right);
        mArrowDown = findViewById(R.id.tv_pip_menu_arrow_down);
        mArrowLeft = findViewById(R.id.tv_pip_menu_arrow_left);
    }

    void updateLayout(Rect updatedBounds) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "%s: update menu layout: %s", TAG, updatedBounds.toShortString());
        boolean previouslyVertical =
                mCurrentBounds != null && mCurrentBounds.height() > mCurrentBounds.width();
        boolean vertical = updatedBounds.height() > updatedBounds.width();

        mCurrentBounds = updatedBounds;
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
        showMenuButtons(false);
        showMovementHints(gravity);
        showMenuFrame(true);
    }

    void showButtonMenu() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: showButtonMenu()", TAG);
        }
        showMenuButtons(true);
        hideMovementHints();
        showMenuFrame(true);
    }

    /**
     * Hides all menu views, including the menu frame.
     */
    void hideAll() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE, "%s: hideAll()", TAG);
        }
        showMenuButtons(false);
        hideMovementHints();
        showMenuFrame(false);
    }

    private void animateAlphaTo(float alpha, View view) {
        if (view.getAlpha() == alpha) {
            return;
        }
        view.animate()
                .alpha(alpha)
                .setInterpolator(alpha == 0f ? TvPipInterpolators.EXIT : TvPipInterpolators.ENTER)
                .setDuration(500)
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

    boolean isVisible() {
        return mMenuFrameView.getAlpha() != 0f
                || mActionButtonsContainer.getAlpha() != 0f
                || mArrowUp.getAlpha() != 0f
                || mArrowRight.getAlpha() != 0f
                || mArrowDown.getAlpha() != 0f
                || mArrowLeft.getAlpha() != 0f;
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
        if (action1 == null) return false;
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
     * Show or hide the pip user actions.
     */
    public void showMenuButtons(boolean show) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: showMenuButtons: %b", TAG, show);
        }
        animateAlphaTo(show ? 1 : 0, mActionButtonsContainer);
    }

    private void showMenuFrame(boolean show) {
        animateAlphaTo(show ? 1 : 0, mMenuFrameView);
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
