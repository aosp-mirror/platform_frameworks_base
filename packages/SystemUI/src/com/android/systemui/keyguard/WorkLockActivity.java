/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.keyguard;

import static android.app.ActivityManager.TaskDescription;

import android.annotation.ColorInt;
import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;

/**
 * Bouncer between work activities and the activity used to confirm credentials before unlocking
 * a managed profile.
 * <p>
 * Shows a solid color when started, based on the organization color of the user it is supposed to
 * be blocking. Once focused, it switches to a screen to confirm credentials and auto-dismisses if
 * credentials are accepted.
 */
public class WorkLockActivity extends Activity {
    private static final String TAG = "WorkLockActivity";

    /**
     * ID of the locked user that this activity blocks access to.
     */
    @UserIdInt
    private int mUserId;

    /**
     * {@see KeyguardManager}
     */
    private KeyguardManager mKgm;

    /**
     * {@see DevicePolicyManager}
     */
    private DevicePolicyManager mDpm;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mUserId = getIntent().getIntExtra(Intent.EXTRA_USER_ID, UserHandle.myUserId());
        mDpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        mKgm = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        final IntentFilter lockFilter = new IntentFilter();
        lockFilter.addAction(Intent.ACTION_DEVICE_LOCKED_CHANGED);
        registerReceiverAsUser(mLockEventReceiver, UserHandle.ALL, lockFilter,
                /* permission */ null, /* scheduler */ null);

        // Once the receiver is registered, check whether anything happened between now and the time
        // when this activity was launched. If it did and the user is unlocked now, just quit.
        if (!mKgm.isDeviceLocked(mUserId)) {
            finish();
            return;
        }

        // Get the organization color; this is a 24-bit integer provided by a DPC, guaranteed to
        // be completely opaque.
        final @ColorInt int color = mDpm.getOrganizationColorForUser(mUserId);

        // Draw captions overlaid on the content view, so the whole window is one solid color.
        setOverlayWithDecorCaptionEnabled(true);

        // Blank out the activity. When it is on-screen it will look like a Recents thumbnail with
        // redaction switched on.
        final View blankView = new View(this);
        blankView.setBackgroundColor(color);
        setContentView(blankView);
    }

    /**
     * Respond to focus events by showing the prompt to confirm credentials.
     * <p>
     * We don't have anything particularly interesting to show here (just a solid-colored page) so
     * there is no sense in sitting in the foreground doing nothing.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        if (hasFocus) {
            showConfirmCredentialActivity();
        }
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(mLockEventReceiver);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Ignore back presses.
        return;
    }

    @Override
    public void setTaskDescription(TaskDescription taskDescription) {
        // Use the previous activity's task description.
    }

    private final BroadcastReceiver mLockEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, mUserId);
            if (userId == mUserId && !mKgm.isDeviceLocked(mUserId)) {
                finish();
            }
        }
    };

    private void showConfirmCredentialActivity() {
        if (isFinishing() || !mKgm.isDeviceLocked(mUserId)) {
            // Don't show the confirm credentials screen if we are already unlocked / unlocking.
            return;
        }

        final Intent credential = mKgm.createConfirmDeviceCredentialIntent(null, null, mUserId);
        if (credential == null) {
            return;
        }

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchTaskId(getTaskId());

        // Bring this activity back to the foreground after confirming credentials.
        final PendingIntent target = PendingIntent.getActivity(this, /* request */ -1, getIntent(),
                PendingIntent.FLAG_CANCEL_CURRENT |
                PendingIntent.FLAG_ONE_SHOT |
                PendingIntent.FLAG_IMMUTABLE, options.toBundle());

        credential.putExtra(Intent.EXTRA_INTENT, target.getIntentSender());
        try {
            ActivityManager.getService().startConfirmDeviceCredentialIntent(credential);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to start confirm credential intent", e);
        }
    }
}
