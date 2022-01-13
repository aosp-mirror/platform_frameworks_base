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

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManagerGlobal;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.R;

import java.util.ArrayList;
import java.util.List;

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
        mActionButtonsContainer.findViewById(R.id.tv_pip_menu_close_button)
                .setOnClickListener(this);
        mActionButtonsContainer.findViewById(R.id.tv_pip_menu_move_button)
                .setOnClickListener(this);

        mMenuFrameView = findViewById(R.id.tv_pip_menu_frame);

        mArrowUp = findViewById(R.id.tv_pip_menu_arrow_up);
        mArrowRight = findViewById(R.id.tv_pip_menu_arrow_right);
        mArrowDown = findViewById(R.id.tv_pip_menu_arrow_down);
        mArrowLeft = findViewById(R.id.tv_pip_menu_arrow_left);
    }

    void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    void show(boolean inMoveMode, int gravity) {
        if (DEBUG) Log.d(TAG, "show(), inMoveMode: " + inMoveMode);
        grantWindowFocus(true);

        if (inMoveMode) {
            showMovementHints(gravity);
        } else {
            animateAlphaTo(1, mActionButtonsContainer);
        }
        animateAlphaTo(1, mMenuFrameView);
    }

    void hide(boolean isInMoveMode) {
        if (DEBUG) Log.d(TAG, "hide()");
        animateAlphaTo(0, mActionButtonsContainer);
        animateAlphaTo(0, mMenuFrameView);
        hideMovementHints();

        if (!isInMoveMode) {
            grantWindowFocus(false);
        }
    }

    private void animateAlphaTo(float alpha, View view) {
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

    private void grantWindowFocus(boolean grantFocus) {
        if (DEBUG) Log.d(TAG, "grantWindowFocus(" + grantFocus + ")");

        try {
            WindowManagerGlobal.getWindowSession().grantEmbeddedWindowFocus(null /* window */,
                    getViewRootImpl().getInputToken(), grantFocus);
        } catch (Exception e) {
            Log.e(TAG, "Unable to update focus", e);
        }
    }

    void setAdditionalActions(List<RemoteAction> actions, Handler mainHandler) {
        if (DEBUG) Log.d(TAG, "setAdditionalActions()");

        // Make sure we exactly as many additional buttons as we have actions to display.
        final int actionsNumber = actions.size();
        int buttonsNumber = mAdditionalButtons.size();
        if (actionsNumber > buttonsNumber) {
            final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
            // Add buttons until we have enough to display all of the actions.
            while (actionsNumber > buttonsNumber) {
                TvPipMenuActionButton button = new TvPipMenuActionButton(mContext);
                button.setOnClickListener(this);

                mActionButtonsContainer.addView(button);
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
            button.setVisibility(View.VISIBLE); // Ensure the button is visible.
            button.setTextAndDescription(action.getContentDescription());
            button.setEnabled(action.isEnabled());
            button.setTag(action);
            action.getIcon().loadDrawableAsync(mContext, button::setImageDrawable, mainHandler);
        }
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
        } else {
            // This should be an "additional action"
            final RemoteAction action = (RemoteAction) v.getTag();
            if (action != null) {
                try {
                    action.getActionIntent().send();
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "Failed to send action", e);
                }
            } else {
                Log.w(TAG, "RemoteAction is null");
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (DEBUG) {
            Log.d(TAG, "dispatchKeyEvent, action: " + event.getAction()
                    + ", keycode: " + event.getKeyCode());
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
        if (DEBUG) Log.d(TAG, "showMovementHints(), position: " + Gravity.toString(gravity));

        animateAlphaTo((gravity & Gravity.BOTTOM) == Gravity.BOTTOM ? 1f : 0f, mArrowUp);
        animateAlphaTo((gravity & Gravity.TOP) == Gravity.TOP ? 1f : 0f, mArrowDown);
        animateAlphaTo((gravity & Gravity.RIGHT) == Gravity.RIGHT ? 1f : 0f, mArrowLeft);
        animateAlphaTo((gravity & Gravity.LEFT) == Gravity.LEFT ? 1f : 0f, mArrowRight);
    }

    /**
     * Hides user hints for moving the PiP, e.g. arrows.
     */
    public void hideMovementHints() {
        if (DEBUG) Log.d(TAG, "hideMovementHints()");
        animateAlphaTo(0, mArrowUp);
        animateAlphaTo(0, mArrowRight);
        animateAlphaTo(0, mArrowDown);
        animateAlphaTo(0, mArrowLeft);
    }

    /**
     * Show or hide the pip user actions.
     */
    public void showMenuButtons(boolean show) {
        if (DEBUG) Log.d(TAG, "showMenuButtons: " + show);
        animateAlphaTo(show ? 1 : 0, mActionButtonsContainer);
    }

    interface Listener {

        void onBackPress();

        void onEnterMoveMode();

        /**
         * @return whether move mode was exited
         */
        boolean onExitMoveMode();

        /**
         * @return whether pip movement was handled.
         */
        boolean onPipMovement(int keycode);

        void onCloseButtonClick();

        void onFullscreenButtonClick();
    }
}
