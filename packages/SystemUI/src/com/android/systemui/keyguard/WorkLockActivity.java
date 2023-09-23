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

import android.annotation.UserIdInt;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.widget.ImageView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.res.R;
import com.android.systemui.broadcast.BroadcastDispatcher;

import javax.inject.Inject;

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

    private static final int REQUEST_CODE_CONFIRM_CREDENTIALS = 1;

    /**
     * Cached keyguard manager instance populated by {@link #getKeyguardManager}.
     * @see KeyguardManager
     */
    private KeyguardManager mKgm;
    private UserManager mUserManager;
    private PackageManager mPackageManager;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final OnBackInvokedCallback mBackCallback = this::onBackInvoked;

    @Inject
    public WorkLockActivity(BroadcastDispatcher broadcastDispatcher, UserManager userManager,
            PackageManager packageManager) {
        super();
        mBroadcastDispatcher = broadcastDispatcher;
        mUserManager = userManager;
        mPackageManager = packageManager;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mBroadcastDispatcher.registerReceiver(mLockEventReceiver,
                new IntentFilter(Intent.ACTION_DEVICE_LOCKED_CHANGED), null /* handler */,
                UserHandle.ALL);

        // Once the receiver is registered, check whether anything happened between now and the time
        // when this activity was launched. If it did and the user is unlocked now, just quit.
        if (!getKeyguardManager().isDeviceLocked(getTargetUserId())) {
            finish();
            return;
        }

        // Draw captions overlaid on the content view, so the whole window is one solid color.
        setOverlayWithDecorCaptionEnabled(true);

        // Add background protection that contains a badged icon of the app being opened.
        setContentView(R.layout.auth_biometric_background);
        Drawable badgedIcon = getBadgedIcon();
        if (badgedIcon != null) {
            ((ImageView) findViewById(R.id.icon)).setImageDrawable(badgedIcon);
        }

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                mBackCallback);
    }

    @VisibleForTesting
    protected Drawable getBadgedIcon() {
        String packageName = getIntent().getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        if (!packageName.isEmpty()) {
            try {
                return mUserManager.getBadgedIconForUser(mPackageManager.getApplicationIcon(
                        mPackageManager.getApplicationInfoAsUser(packageName,
                                PackageManager.ApplicationInfoFlags.of(0), getTargetUserId())),
                        UserHandle.of(getTargetUserId()));
            } catch (PackageManager.NameNotFoundException e) {
                // Unable to set the badged icon, show the background protection without an icon.
            }
        }
        return null;
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

    @VisibleForTesting
    protected void unregisterBroadcastReceiver() {
        mBroadcastDispatcher.unregisterReceiver(mLockEventReceiver);
    }

    @Override
    public void onDestroy() {
        unregisterBroadcastReceiver();
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(mBackCallback);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        onBackInvoked();
    }

    private void onBackInvoked() {
        // Ignore back presses.
    }

    @Override
    public void setTaskDescription(TaskDescription taskDescription) {
        // Leave unset so we use the previous activity's task description.
    }

    private final BroadcastReceiver mLockEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int targetUserId = getTargetUserId();
            final int userId = intent.getIntExtra(Intent.EXTRA_USER_HANDLE, targetUserId);
            if (userId == targetUserId && !getKeyguardManager().isDeviceLocked(targetUserId)) {
                finish();
            }
        }
    };

    private void showConfirmCredentialActivity() {
        if (isFinishing() || !getKeyguardManager().isDeviceLocked(getTargetUserId())) {
            // Don't show the confirm credentials screen if we are already unlocked / unlocking.
            return;
        }

        final Intent confirmCredentialIntent = getKeyguardManager()
                .createConfirmDeviceCredentialIntent(null, null, getTargetUserId(),
                true /* disallowBiometricsIfPolicyExists */);
        if (confirmCredentialIntent == null) {
            return;
        }

        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchTaskId(getTaskId());

        // Bring this activity back to the foreground after confirming credentials.
        final PendingIntent target = PendingIntent.getActivity(this, /* request */ -1, getIntent(),
                PendingIntent.FLAG_CANCEL_CURRENT |
                PendingIntent.FLAG_ONE_SHOT |
                PendingIntent.FLAG_IMMUTABLE, options.toBundle());

        if (target != null) {
            confirmCredentialIntent.putExtra(Intent.EXTRA_INTENT, target.getIntentSender());
        }

        // WorkLockActivity is started as a task overlay, so unless credential confirmation is also
        // started as an overlay, it won't be visible.
        final ActivityOptions launchOptions = ActivityOptions.makeBasic();
        launchOptions.setLaunchTaskId(getTaskId());
        launchOptions.setTaskOverlay(true /* taskOverlay */, true /* canResume */);
        // Propagate it in case more than one activity is launched.
        confirmCredentialIntent.putExtra(KeyguardManager.EXTRA_FORCE_TASK_OVERLAY, true);

        startActivityForResult(confirmCredentialIntent, REQUEST_CODE_CONFIRM_CREDENTIALS,
                launchOptions.toBundle());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_CONFIRM_CREDENTIALS &&  resultCode != RESULT_OK) {
            // The user dismissed the challenge, don't show it again.
            goToHomeScreen();
        }
    }

    private void goToHomeScreen() {
        final Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
    }

    private KeyguardManager getKeyguardManager() {
        if (mKgm == null) {
            mKgm = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        }
        return mKgm;
    }

    @VisibleForTesting
    @UserIdInt
    final int getTargetUserId() {
        return getIntent().getIntExtra(Intent.EXTRA_USER_ID, UserHandle.myUserId());
    }
}
