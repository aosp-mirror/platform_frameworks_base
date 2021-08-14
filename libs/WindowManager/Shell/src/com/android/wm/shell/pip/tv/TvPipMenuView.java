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

import static android.animation.AnimatorInflater.loadAnimator;
import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.KEYCODE_BACK;

import android.animation.Animator;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.WindowManagerGlobal;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.R;

import java.util.ArrayList;
import java.util.List;

/**
 * A View that represents Pip Menu on TV. It's responsible for displaying 2 ever-present Pip Menu
 * actions: Fullscreen and Close, but could also display "additional" actions, that may be set via
 * a {@link #setAdditionalActions(List, Handler)} call.
 */
public class TvPipMenuView extends FrameLayout implements View.OnClickListener {
    private static final String TAG = "TvPipMenuView";
    private static final boolean DEBUG = TvPipController.DEBUG;

    private static final float DISABLED_ACTION_ALPHA = 0.54f;

    private final Animator mFadeInAnimation;
    private final Animator mFadeOutAnimation;
    @Nullable private Listener mListener;

    private final LinearLayout mActionButtonsContainer;
    private final List<TvPipMenuActionButton> mAdditionalButtons = new ArrayList<>();

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

        mFadeInAnimation = loadAnimator(mContext, R.anim.tv_pip_menu_fade_in_animation);
        mFadeInAnimation.setTarget(mActionButtonsContainer);

        mFadeOutAnimation = loadAnimator(mContext, R.anim.tv_pip_menu_fade_out_animation);
        mFadeOutAnimation.setTarget(mActionButtonsContainer);
    }

    void setListener(@Nullable Listener listener) {
        mListener = listener;
    }

    void show() {
        if (DEBUG) Log.d(TAG, "show()");

        mFadeInAnimation.start();
        setAlpha(1.0f);
        grantWindowFocus(true);
    }

    void hide() {
        if (DEBUG) Log.d(TAG, "hide()");

        mFadeOutAnimation.start();
        setAlpha(0.0f);
        grantWindowFocus(false);
    }

    boolean isVisible() {
        return getAlpha() == 1.0f;
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
                final TvPipMenuActionButton button = (TvPipMenuActionButton) layoutInflater.inflate(
                        R.layout.tv_pip_menu_additional_action_button, mActionButtonsContainer,
                        false);
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
            button.setAlpha(action.isEnabled() ? 1f : DISABLED_ACTION_ALPHA);
            button.setTag(action);

            action.getIcon().loadDrawableAsync(mContext, drawable -> {
                drawable.setTint(Color.WHITE);
                button.setImageDrawable(drawable);
            }, mainHandler);
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
        if (event.getAction() == ACTION_UP && event.getKeyCode() == KEYCODE_BACK
                && mListener != null) {
            mListener.onBackPress();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    interface Listener {
        void onBackPress();
        void onCloseButtonClick();
        void onFullscreenButtonClick();
    }
}
