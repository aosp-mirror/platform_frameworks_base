/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.wallet.ui;

import static android.provider.Settings.ACTION_LOCKSCREEN_SETTINGS;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toolbar;

import androidx.annotation.NonNull;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.LifecycleActivity;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * Displays Wallet carousel screen inside an activity.
 */
public class WalletActivity extends LifecycleActivity {

    private final QuickAccessWalletClient mQuickAccessWalletClient;
    private final KeyguardStateController mKeyguardStateController;
    private final KeyguardDismissUtil mKeyguardDismissUtil;
    private final ActivityStarter mActivityStarter;
    private final Executor mExecutor;
    private final Handler mHandler;
    private final FalsingManager mFalsingManager;
    private final UserTracker mUserTracker;
    private final StatusBarKeyguardViewManager mKeyguardViewManager;
    private WalletScreenController mWalletScreenController;

    @Inject
    public WalletActivity(
            QuickAccessWalletClient quickAccessWalletClient,
            KeyguardStateController keyguardStateController,
            KeyguardDismissUtil keyguardDismissUtil,
            ActivityStarter activityStarter,
            @Background Executor executor,
            @Main Handler handler,
            FalsingManager falsingManager,
            UserTracker userTracker,
            StatusBarKeyguardViewManager keyguardViewManager) {
        mQuickAccessWalletClient = quickAccessWalletClient;
        mKeyguardStateController = keyguardStateController;
        mKeyguardDismissUtil = keyguardDismissUtil;
        mActivityStarter = activityStarter;
        mExecutor = executor;
        mHandler = handler;
        mFalsingManager = falsingManager;
        mUserTracker = userTracker;
        mKeyguardViewManager = keyguardViewManager;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.quick_access_wallet);

        Toolbar toolbar = findViewById(R.id.action_bar);
        if (toolbar != null) {
            setActionBar(toolbar);
        }
        setTitle("");
        getActionBar().setDisplayHomeAsUpEnabled(true);
        getActionBar().setHomeAsUpIndicator(getHomeIndicatorDrawable());
        getActionBar().setHomeActionContentDescription(R.string.accessibility_desc_close);
        WalletView walletView = requireViewById(R.id.wallet_view);

        mWalletScreenController = new WalletScreenController(
                this,
                walletView,
                mQuickAccessWalletClient,
                mActivityStarter,
                mExecutor,
                mHandler,
                mUserTracker,
                mFalsingManager,
                mKeyguardStateController);

        walletView.getAppButton().setOnClickListener(
                v -> {
                    if (!mKeyguardStateController.isUnlocked()
                            && mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                        return;
                    }

                    if (mKeyguardStateController.isUnlocked()) {
                        mActivityStarter.startActivity(
                                mQuickAccessWalletClient.createWalletIntent(), true);
                        finish();
                    } else {
                        mKeyguardDismissUtil.executeWhenUnlocked(() -> {
                            mActivityStarter.startActivity(
                                    mQuickAccessWalletClient.createWalletIntent(), true);
                            finish();
                            return false;
                        }, false, true);
                    }
                });

        // Click the action button to re-render the screen when the device is unlocked.
        walletView.setDeviceLockedActionOnClickListener(
                v -> {
                    if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                        return;
                    }

                    mKeyguardDismissUtil.executeWhenUnlocked(() -> false, false,
                            false);
                });
    }

    @Override
    protected void onStart() {
        super.onStart();
        mKeyguardStateController.addCallback(mWalletScreenController);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mWalletScreenController.queryWalletCards();
        mKeyguardViewManager.requestFp(true, Color.BLACK);
        mKeyguardViewManager.requestFace(true);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mKeyguardViewManager.requestFp(false, -1);
        mKeyguardViewManager.requestFace(false);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.wallet_activity_options_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            finish();
            return true;
        } else if (itemId == R.id.wallet_lockscreen_settings) {
            Intent intent =
                    new Intent(ACTION_LOCKSCREEN_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mActivityStarter.startActivity(intent, true);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        mKeyguardStateController.removeCallback(mWalletScreenController);
        mWalletScreenController.onDismissed();
        super.onDestroy();
    }

    private Drawable getHomeIndicatorDrawable() {
        Drawable drawable = getDrawable(R.drawable.ic_close);
        drawable.setTint(getColor(com.android.internal.R.color.system_neutral1_300));
        return drawable;
    }
}
