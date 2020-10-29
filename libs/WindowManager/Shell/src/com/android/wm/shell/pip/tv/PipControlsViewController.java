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

import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import com.android.wm.shell.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * Controller for {@link PipControlsView}.
 */
public class PipControlsViewController {
    private static final String TAG = PipControlsViewController.class.getSimpleName();

    private static final float DISABLED_ACTION_ALPHA = 0.54f;

    private final PipController mPipController;

    private final Context mContext;
    private final Handler mUiThreadHandler;
    private final PipControlsView mView;
    private final List<PipControlButtonView> mAdditionalButtons = new ArrayList<>();

    private final List<RemoteAction> mCustomActions = new ArrayList<>();
    private final List<RemoteAction> mMediaActions = new ArrayList<>();

    public PipControlsViewController(PipControlsView view, PipController pipController) {
        mContext = view.getContext();
        mUiThreadHandler = new Handler(Looper.getMainLooper());
        mPipController = pipController;
        mView = view;

        mView.getFullscreenButton().setOnClickListener(v -> mPipController.movePipToFullscreen());
        mView.getCloseButton().setOnClickListener(v -> mPipController.closePip());

        mPipController.getPipMediaController().addActionListener(this::onMediaActionsChanged);
    }

    PipControlsView getView() {
        return mView;
    }

    /**
     * Updates the set of activity-defined actions.
     */
    void setCustomActions(List<? extends RemoteAction> actions) {
        if (mCustomActions.isEmpty() && actions.isEmpty()) {
            // Nothing changed - return early.
            return;
        }
        mCustomActions.clear();
        mCustomActions.addAll(actions);
        updateAdditionalActions();
    }

    private void onMediaActionsChanged(List<RemoteAction> actions) {
        if (mMediaActions.isEmpty() && actions.isEmpty()) {
            // Nothing changed - return early.
            return;
        }
        mMediaActions.clear();
        mMediaActions.addAll(actions);

        // Update the view only if there are no custom actions (media actions are only shown when
        // there no custom actions).
        if (mCustomActions.isEmpty()) {
            updateAdditionalActions();
        }
    }

    private void updateAdditionalActions() {
        final List<RemoteAction> actionsToDisplay;
        if (!mCustomActions.isEmpty()) {
            // If there are custom actions: show them.
            actionsToDisplay = mCustomActions;
        } else if (!mMediaActions.isEmpty()) {
            // If there are no custom actions, but there media actions: show them.
            actionsToDisplay = mMediaActions;
        } else {
            // If there no custom actions and no media actions: clean up all the additional buttons.
            actionsToDisplay = Collections.emptyList();
        }

        // Make sure we exactly as many additional buttons as we have actions to display.
        final int actionsNumber = actionsToDisplay.size();
        int buttonsNumber = mAdditionalButtons.size();
        if (actionsNumber > buttonsNumber) {
            final LayoutInflater layoutInflater = LayoutInflater.from(mContext);
            // Add buttons until we have enough to display all of the actions.
            while (actionsNumber > buttonsNumber) {
                final PipControlButtonView button = (PipControlButtonView) layoutInflater.inflate(
                        R.layout.tv_pip_custom_control, mView, false);
                mView.addView(button);
                mAdditionalButtons.add(button);

                buttonsNumber++;
            }
        } else if (actionsNumber < buttonsNumber) {
            // Hide buttons until we as many as the actions.
            while (actionsNumber < buttonsNumber) {
                final View button = mAdditionalButtons.get(buttonsNumber - 1);
                button.setVisibility(View.GONE);
                button.setOnClickListener(null);

                buttonsNumber--;
            }
        }

        // "Assign" actions to the buttons.
        for (int index = 0; index < actionsNumber; index++) {
            final RemoteAction action = actionsToDisplay.get(index);
            final PipControlButtonView button = mAdditionalButtons.get(index);
            button.setVisibility(View.VISIBLE); // Ensure the button is visible.
            button.setText(action.getContentDescription());
            button.setEnabled(action.isEnabled());
            button.setAlpha(action.isEnabled() ? 1f : DISABLED_ACTION_ALPHA);
            button.setOnClickListener(v -> {
                try {
                    action.getActionIntent().send();
                } catch (PendingIntent.CanceledException e) {
                    Log.w(TAG, "Failed to send action", e);
                }
            });

            action.getIcon().loadDrawableAsync(mContext, drawable -> {
                drawable.setTint(Color.WHITE);
                button.setImageDrawable(drawable);
            }, mUiThreadHandler);
        }
    }
}
