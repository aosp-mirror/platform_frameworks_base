/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.internal.globalactions;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import com.android.internal.R;

/**
 * A toggle action knows whether it is on or off, and displays an icon and status message
 * accordingly.
 */
public abstract class ToggleAction implements Action {
    private static final String TAG = "ToggleAction";

    public enum State {
        Off(false),
        TurningOn(true),
        TurningOff(true),
        On(false);

        private final boolean inTransition;

        State(boolean intermediate) {
            inTransition = intermediate;
        }

        public boolean inTransition() {
            return inTransition;
        }
    }

    protected State mState = State.Off;

    // prefs
    protected int mEnabledIconResId;
    protected int mDisabledIconResid;
    protected int mMessageResId;
    protected int mEnabledStatusMessageResId;
    protected int mDisabledStatusMessageResId;

    /**
     * @param enabledIconResId The icon for when this action is on.
     * @param disabledIconResid The icon for when this action is off.
     * @param message The general information message, e.g 'Silent Mode'
     * @param enabledStatusMessageResId The on status message, e.g 'sound disabled'
     * @param disabledStatusMessageResId The off status message, e.g. 'sound enabled'
     */
    public ToggleAction(int enabledIconResId,
            int disabledIconResid,
            int message,
            int enabledStatusMessageResId,
            int disabledStatusMessageResId) {
        mEnabledIconResId = enabledIconResId;
        mDisabledIconResid = disabledIconResid;
        mMessageResId = message;
        mEnabledStatusMessageResId = enabledStatusMessageResId;
        mDisabledStatusMessageResId = disabledStatusMessageResId;
    }

    /** Override to make changes to resource IDs just before creating the View. */
    void willCreate() {

    }

    @Override
    public CharSequence getLabelForAccessibility(Context context) {
        return context.getString(mMessageResId);
    }

    @Override
    public View create(Context context, View convertView, ViewGroup parent,
            LayoutInflater inflater) {
        willCreate();

        View v = inflater.inflate(R.layout.global_actions_item, parent, false);

        ImageView icon = v.findViewById(R.id.icon);
        TextView messageView = v.findViewById(R.id.message);
        TextView statusView = v.findViewById(R.id.status);
        final boolean enabled = isEnabled();

        if (messageView != null) {
            messageView.setText(mMessageResId);
            messageView.setEnabled(enabled);
        }

        boolean on = ((mState == State.On) || (mState == State.TurningOn));
        if (icon != null) {
            icon.setImageDrawable(context.getDrawable(
                    (on ? mEnabledIconResId : mDisabledIconResid)));
            icon.setEnabled(enabled);
        }

        if (statusView != null) {
            statusView.setText(on ? mEnabledStatusMessageResId : mDisabledStatusMessageResId);
            statusView.setVisibility(View.VISIBLE);
            statusView.setEnabled(enabled);
        }
        v.setEnabled(enabled);

        return v;
    }

    @Override
    public final void onPress() {
        if (mState.inTransition()) {
            Log.w(TAG, "shouldn't be able to toggle when in transition");
            return;
        }

        final boolean nowOn = !(mState == State.On);
        onToggle(nowOn);
        changeStateFromPress(nowOn);
    }

    @Override
    public boolean isEnabled() {
        return !mState.inTransition();
    }

    /**
     * Implementations may override this if their state can be in on of the intermediate
     * states until some notification is received (e.g airplane mode is 'turning off' until
     * we know the wireless connections are back online
     * @param buttonOn Whether the button was turned on or off
     */
    protected void changeStateFromPress(boolean buttonOn) {
        mState = buttonOn ? State.On : State.Off;
    }

    public abstract void onToggle(boolean on);

    public void updateState(State state) {
        mState = state;
    }
}
