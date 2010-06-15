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

import android.app.ActivityManager;
import android.app.Dialog;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.widget.TextView;

import java.util.List;

public class RecentApplicationsDialog extends Dialog implements OnClickListener {
    // Elements for debugging support
//  private static final String LOG_TAG = "RecentApplicationsDialog";
    private static final boolean DBG_FORCE_EMPTY_LIST = false;

    static private StatusBarManager sStatusBar;

    private static final int NUM_BUTTONS = 8;
    private static final int MAX_RECENT_TASKS = NUM_BUTTONS * 2;    // allow for some discards

    final TextView[] mIcons = new TextView[NUM_BUTTONS];
    View mNoAppsText;
    IntentFilter mBroadcastIntentFilter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);

    private int mIconSize;

    public RecentApplicationsDialog(Context context) {
        super(context, com.android.internal.R.style.Theme_Dialog_RecentApplications);

        final Resources resources = context.getResources();
        mIconSize = (int) resources.getDimension(android.R.dimen.app_icon_size);
    }

    /**
     * We create the recent applications dialog just once, and it stays around (hidden)
     * until activated by the user.
     *
     * @see PhoneWindowManager#showRecentAppsDialog
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = getContext();

        if (sStatusBar == null) {
            sStatusBar = (StatusBarManager)context.getSystemService(Context.STATUS_BAR_SERVICE);
        }

        Window window = getWindow();
        window.requestFeature(Window.FEATURE_NO_TITLE);
        window.setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        window.setFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM);
        window.setTitle("Recents");

        setContentView(com.android.internal.R.layout.recent_apps_dialog);

        final WindowManager.LayoutParams params = window.getAttributes();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        window.setAttributes(params);
        window.setFlags(0, WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        mIcons[0] = (TextView)findViewById(com.android.internal.R.id.button0);
        mIcons[1] = (TextView)findViewById(com.android.internal.R.id.button1);
        mIcons[2] = (TextView)findViewById(com.android.internal.R.id.button2);
        mIcons[3] = (TextView)findViewById(com.android.internal.R.id.button3);
        mIcons[4] = (TextView)findViewById(com.android.internal.R.id.button4);
        mIcons[5] = (TextView)findViewById(com.android.internal.R.id.button5);
        mIcons[6] = (TextView)findViewById(com.android.internal.R.id.button6);
        mIcons[7] = (TextView)findViewById(com.android.internal.R.id.button7);
        mNoAppsText = findViewById(com.android.internal.R.id.no_applications_message);

        for (TextView b: mIcons) {
            b.setOnClickListener(this);
        }
    }

    /**
     * Handler for user clicks.  If a button was clicked, launch the corresponding activity.
     */
    public void onClick(View v) {

        for (TextView b: mIcons) {
            if (b == v) {
                // prepare a launch intent and send it
                Intent intent = (Intent)b.getTag();
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY);
                    try {
                        getContext().startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        Log.w("Recent", "Unable to launch recent task", e);
                    }
                }
                break;
            }
        }
        dismiss();
    }

    /**
     * Set up and show the recent activities dialog.
     */
    @Override
    public void onStart() {
        super.onStart();
        reloadButtons();
        if (sStatusBar != null) {
            sStatusBar.disable(StatusBarManager.DISABLE_EXPAND);
        }

        // receive broadcasts
        getContext().registerReceiver(mBroadcastReceiver, mBroadcastIntentFilter);
    }

    /**
     * Dismiss the recent activities dialog.
     */
    @Override
    public void onStop() {
        super.onStop();

        // dump extra memory we're hanging on to
        for (TextView icon: mIcons) {
            icon.setCompoundDrawables(null, null, null, null);
            icon.setTag(null);
        }

        if (sStatusBar != null) {
            sStatusBar.disable(StatusBarManager.DISABLE_NONE);
        }

        // stop receiving broadcasts
        getContext().unregisterReceiver(mBroadcastReceiver);
     }

    /**
     * Reload the 6 buttons with recent activities
     */
    private void reloadButtons() {

        final Context context = getContext();
        final PackageManager pm = context.getPackageManager();
        final ActivityManager am = (ActivityManager)
                context.getSystemService(Context.ACTIVITY_SERVICE);
        final List<ActivityManager.RecentTaskInfo> recentTasks =
                am.getRecentTasks(MAX_RECENT_TASKS, ActivityManager.RECENT_IGNORE_UNAVAILABLE);

        ActivityInfo homeInfo = 
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .resolveActivityInfo(pm, 0);

        IconUtilities iconUtilities = new IconUtilities(getContext());

        // Performance note:  Our android performance guide says to prefer Iterator when
        // using a List class, but because we know that getRecentTasks() always returns
        // an ArrayList<>, we'll use a simple index instead.
        int index = 0;
        int numTasks = recentTasks.size();
        for (int i = 0; i < numTasks && (index < NUM_BUTTONS); ++i) {
            final ActivityManager.RecentTaskInfo info = recentTasks.get(i);

            // for debug purposes only, disallow first result to create empty lists
            if (DBG_FORCE_EMPTY_LIST && (i == 0)) continue;

            Intent intent = new Intent(info.baseIntent);
            if (info.origActivity != null) {
                intent.setComponent(info.origActivity);
            }

            // Skip the current home activity.
            if (homeInfo != null) {
                if (homeInfo.packageName.equals(
                        intent.getComponent().getPackageName())
                        && homeInfo.name.equals(
                                intent.getComponent().getClassName())) {
                    continue;
                }
            }

            intent.setFlags((intent.getFlags()&~Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            final ResolveInfo resolveInfo = pm.resolveActivity(intent, 0);
            if (resolveInfo != null) {
                final ActivityInfo activityInfo = resolveInfo.activityInfo;
                final String title = activityInfo.loadLabel(pm).toString();
                Drawable icon = activityInfo.loadIcon(pm);

                if (title != null && title.length() > 0 && icon != null) {
                    final TextView tv = mIcons[index];
                    tv.setText(title);
                    icon = iconUtilities.createIconDrawable(icon);
                    tv.setCompoundDrawables(null, icon, null, null);
                    tv.setTag(intent);
                    tv.setVisibility(View.VISIBLE);
                    tv.setPressed(false);
                    tv.clearFocus();
                    ++index;
                }
            }
        }

        // handle the case of "no icons to show"
        mNoAppsText.setVisibility((index == 0) ? View.VISIBLE : View.GONE);

        // hide the rest
        for (; index < NUM_BUTTONS; ++index) {
            mIcons[index].setVisibility(View.GONE);
        }
    }

    /**
     * This is the listener for the ACTION_CLOSE_SYSTEM_DIALOGS intent.  It's an indication that
     * we should close ourselves immediately, in order to allow a higher-priority UI to take over
     * (e.g. phone call received).
     */
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(action)) {
                String reason = intent.getStringExtra(PhoneWindowManager.SYSTEM_DIALOG_REASON_KEY);
                if (! PhoneWindowManager.SYSTEM_DIALOG_REASON_RECENT_APPS.equals(reason)) {
                    dismiss();
                }
            }
        }
    };
}
