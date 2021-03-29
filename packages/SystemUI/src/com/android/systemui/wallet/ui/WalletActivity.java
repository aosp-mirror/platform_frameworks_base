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

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.view.MenuItem;
import android.view.Window;

import androidx.annotation.NonNull;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.settings.UserTracker;
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
    private final ActivityStarter mActivityStarter;
    private final Executor mExecutor;
    private final Handler mHandler;
    private final UserTracker mUserTracker;
    private WalletScreenController mWalletScreenController;

    @Inject
    public WalletActivity(
            QuickAccessWalletClient quickAccessWalletClient,
            KeyguardStateController keyguardStateController,
            ActivityStarter activityStarter,
            @Background Executor executor,
            @Background Handler handler,
            UserTracker userTracker) {
        mQuickAccessWalletClient = quickAccessWalletClient;
        mKeyguardStateController = keyguardStateController;
        mActivityStarter = activityStarter;
        mExecutor = executor;
        mHandler = handler;
        mUserTracker = userTracker;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        getWindow().requestFeature(Window.FEATURE_ACTION_BAR_OVERLAY);
        super.onCreate(savedInstanceState);

        setContentView(R.layout.quick_access_wallet);

        getWindow().getDecorView().setBackgroundColor(getColor(R.color.wallet_white));
        setTitle("");
        getActionBar().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getActionBar().setDisplayHomeAsUpEnabled(true);
        getActionBar().setHomeAsUpIndicator(R.drawable.ic_close);
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
                !mKeyguardStateController.isUnlocked());
        walletView.getWalletButton().setOnClickListener(
                v -> mActivityStarter.startActivity(
                        mQuickAccessWalletClient.createWalletIntent(), true));
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        mWalletScreenController.onDismissed();
        super.onDestroy();
    }
}
