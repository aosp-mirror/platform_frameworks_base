/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.policy.impl;

import com.android.internal.R;
import com.google.android.collect.Lists;

import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.DialogInterface;
import android.media.AudioManager;
import android.os.LocalPowerManager;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Helper to show the global actions dialog.  Each item is an {@link Action} that
 * may show depending on whether the keyguard is showing, and whether the device
 * is provisioned.
 */
class GlobalActions implements DialogInterface.OnDismissListener, DialogInterface.OnClickListener  {

    private StatusBarManager mStatusBar;

    private final Context mContext;
    private final LocalPowerManager mPowerManager;
    private final AudioManager mAudioManager;
    private ArrayList<Action> mItems;
    private AlertDialog mDialog;

    private ToggleAction mSilentModeToggle;

    private MyAdapter mAdapter;

    private boolean mKeyguardShowing = false;
    private boolean mDeviceProvisioned = false;

    /**
     * @param context everything needs a context :)
     * @param powerManager used to turn the screen off (the lock action).
     */
    public GlobalActions(Context context, LocalPowerManager powerManager) {
        mContext = context;
        mPowerManager = powerManager;
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);

        // receive broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        context.registerReceiver(mBroadcastReceiver, filter);
    }

    /**
     * Show the global actions dialog (creating if necessary)
     * @param keyguardShowing True if keyguard is showing
     */
    public void showDialog(boolean keyguardShowing, boolean isDeviceProvisioned) {
        mKeyguardShowing = keyguardShowing;
        mDeviceProvisioned = isDeviceProvisioned;
        if (mDialog == null) {
            mStatusBar = (StatusBarManager)mContext.getSystemService(Context.STATUS_BAR_SERVICE);
            mDialog = createDialog();
        }
        prepareDialog();

        mStatusBar.disable(StatusBarManager.DISABLE_EXPAND);
        mDialog.show();
    }

    /**
     * Create the global actions dialog.
     * @return A new dialog.
     */
    private AlertDialog createDialog() {

        mSilentModeToggle = new ToggleAction(
                R.drawable.ic_lock_silent_mode,
                R.drawable.ic_lock_silent_mode_off,
                R.string.global_action_toggle_silent_mode,
                R.string.global_action_silent_mode_on_status,
                R.string.global_action_silent_mode_off_status) {

            void onToggle(boolean on) {
                mAudioManager.setRingerMode(on ? AudioManager.RINGER_MODE_SILENT
                        : AudioManager.RINGER_MODE_NORMAL);
            }

            public boolean showDuringKeyguard() {
                return true;
            }

            public boolean showBeforeProvisioning() {
                return false;
            }
        };

        mItems = Lists.newArrayList(
                /* Disabled pending bug 1304831 -- key or touch events wake up device before it
                 * can go to sleep.
                // first: lock screen
                new SinglePressAction(com.android.internal.R.drawable.ic_lock_lock, R.string.global_action_lock) {

                    public void onPress() {
                        mPowerManager.goToSleep(SystemClock.uptimeMillis() + 1);
                    }

                    public boolean showDuringKeyguard() {
                        return false;
                    }

                    public boolean showBeforeProvisioning() {
                        return false;
                    }
                },
                */
                // next: silent mode
                mSilentModeToggle,
                // last: power off
                new SinglePressAction(com.android.internal.R.drawable.ic_lock_power_off, R.string.global_action_power_off) {

                    public void onPress() {
                        // shutdown by making sure radio and power are handled accordingly.
                        ShutdownThread.shutdownAfterDisablingRadio(mContext, true);
                    }

                    public boolean showDuringKeyguard() {
                        return true;
                    }

                    public boolean showBeforeProvisioning() {
                        return true;
                    }
                });

        mAdapter = new MyAdapter();

        final AlertDialog.Builder ab = new AlertDialog.Builder(mContext);

        ab.setAdapter(mAdapter, this)
                .setInverseBackgroundForced(true)
                .setTitle(R.string.global_actions);

        final AlertDialog dialog = ab.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        dialog.getWindow().setFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                WindowManager.LayoutParams.FLAG_BLUR_BEHIND);        

        dialog.setOnDismissListener(this);

        return dialog;
    }

    private void prepareDialog() {
        // TODO: May need another 'Vibrate' toggle button, but for now treat them the same
        final boolean silentModeOn =
                mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
        mSilentModeToggle.updateState(silentModeOn);
        mAdapter.notifyDataSetChanged();
        if (mKeyguardShowing) {
            mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        } else {
            mDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);            
        }
    }

    /** {@inheritDoc} */
    public void onDismiss(DialogInterface dialog) {
        mStatusBar.disable(StatusBarManager.DISABLE_NONE);
    }

    /** {@inheritDoc} */
    public void onClick(DialogInterface dialog, int which) {
        dialog.dismiss();
        mAdapter.getItem(which).onPress();
    }


    /**
     * The adapter used for the list within the global actions dialog, taking
     * into account whether the keyguard is showing via
     * {@link GlobalActions#mKeyguardShowing} and whether the device is provisioned
     * via {@link GlobalActions#mDeviceProvisioned}.
     */
    private class MyAdapter extends BaseAdapter {

        public int getCount() {
            int count = 0;

            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);

                if (mKeyguardShowing && !action.showDuringKeyguard()) {
                    continue;
                }
                if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
                    continue;
                }
                count++;
            }
            return count;
        }

        public Action getItem(int position) {

            int filteredPos = 0;
            for (int i = 0; i < mItems.size(); i++) {
                final Action action = mItems.get(i);
                if (mKeyguardShowing && !action.showDuringKeyguard()) {
                    continue;
                }
                if (!mDeviceProvisioned && !action.showBeforeProvisioning()) {
                    continue;
                }
                if (filteredPos == position) {
                    return action;
                }
                filteredPos++;
            }

            throw new IllegalArgumentException("position " + position + " out of "
                    + "range of showable actions, filtered count = "
                    + "= " + getCount() + ", keyguardshowing=" + mKeyguardShowing
                    + ", provisioned=" + mDeviceProvisioned);
        }


        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            Action action = getItem(position);
            return action.create(mContext, (LinearLayout) convertView, LayoutInflater.from(mContext));
        }
    }

    // note: the scheme below made more sense when we were planning on having
    // 8 different things in the global actions dialog.  seems overkill with
    // only 3 items now, but may as well keep this flexible approach so it will
    // be easy should someone decide at the last minute to include something
    // else, such as 'enable wifi', or 'enable bluetooth'

    /**
     * What each item in the global actions dialog must be able to support.
     */
    private interface Action {
        LinearLayout create(Context context, LinearLayout convertView, LayoutInflater inflater);

        void onPress();

        /**
         * @return whether this action should appear in the dialog when the keygaurd
         *    is showing.
         */
        boolean showDuringKeyguard();

        /**
         * @return whether this action should appear in the dialog before the
         *   device is provisioned.
         */
        boolean showBeforeProvisioning();
    }

    /**
     * A single press action maintains no state, just responds to a press
     * and takes an action.
     */
    private static abstract class SinglePressAction implements Action {
        private final int mIconResId;
        private final int mMessageResId;

        protected SinglePressAction(int iconResId, int messageResId) {
            mIconResId = iconResId;
            mMessageResId = messageResId;
        }

        abstract public void onPress();

        public LinearLayout create(Context context, LinearLayout convertView, LayoutInflater inflater) {
            LinearLayout v = (LinearLayout) ((convertView != null) ?
                    convertView :
                    inflater.inflate(R.layout.global_actions_item, null));

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);

            v.findViewById(R.id.status).setVisibility(View.GONE);

            icon.setImageDrawable(context.getResources().getDrawable(mIconResId));
            messageView.setText(mMessageResId);

            return v;
        }
    }

    /**
     * A toggle action knows whether it is on or off, and displays an icon
     * and status message accordingly.
     */
    static abstract class ToggleAction implements Action {

        private boolean mOn = false;

        // prefs
        private final int mEnabledIconResId;
        private final int mDisabledIconResid;
        private final int mMessageResId;
        private final int mEnabledStatusMessageResId;
        private final int mDisabledStatusMessageResId;

        /**
         * @param enabledIconResId The icon for when this action is on.
         * @param disabledIconResid The icon for when this action is off.
         * @param essage The general information message, e.g 'Silent Mode'
         * @param enabledStatusMessageResId The on status message, e.g 'sound disabled'
         * @param disabledStatusMessageResId The off status message, e.g. 'sound enabled'
         */
        public ToggleAction(int enabledIconResId,
                int disabledIconResid,
                int essage,
                int enabledStatusMessageResId,
                int disabledStatusMessageResId) {
            mEnabledIconResId = enabledIconResId;
            mDisabledIconResid = disabledIconResid;
            mMessageResId = essage;
            mEnabledStatusMessageResId = enabledStatusMessageResId;
            mDisabledStatusMessageResId = disabledStatusMessageResId;
        }

        public LinearLayout create(Context context, LinearLayout convertView,
                LayoutInflater inflater) {
            LinearLayout v = (LinearLayout) ((convertView != null) ?
                    convertView :
                    inflater.inflate(R
                            .layout.global_actions_item, null));

            ImageView icon = (ImageView) v.findViewById(R.id.icon);
            TextView messageView = (TextView) v.findViewById(R.id.message);
            TextView statusView = (TextView) v.findViewById(R.id.status);

            messageView.setText(mMessageResId);

            icon.setImageDrawable(context.getResources().getDrawable(
                    (mOn ? mEnabledIconResId : mDisabledIconResid)));
            statusView.setText(mOn ? mEnabledStatusMessageResId : mDisabledStatusMessageResId);
            statusView.setVisibility(View.VISIBLE);

            return v;
        }

        public void onPress() {
            updateState(!mOn);
            onToggle(mOn);
        }

        abstract void onToggle(boolean on);

        public void updateState(boolean on) {
            mOn = on;
        }
    }

    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                String reason = intent.getStringExtra(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY);
                if (! PhoneWindowManager.SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS.equals(reason)) {
                    mHandler.sendEmptyMessage(MESSAGE_DISMISS);
                }
            }
        }
    };

    private static final int MESSAGE_DISMISS = 0;
    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == MESSAGE_DISMISS) {
                if (mDialog != null) {
                    mDialog.dismiss();
                }
            }
        }
    };
}
