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

package com.android.systemui.wallet.dagger;

import android.app.Activity;
import android.content.Context;
import android.service.quickaccesswallet.QuickAccessWalletClient;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tiles.QuickAccessWalletTile;
import com.android.systemui.wallet.ui.WalletActivity;

import java.util.concurrent.Executor;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;


/**
 * Module for injecting classes in Wallet.
 */
@Module
public abstract class WalletModule {

    /** */
    @Binds
    @IntoMap
    @ClassKey(WalletActivity.class)
    public abstract Activity provideWalletActivity(WalletActivity activity);

    /** */
    @SysUISingleton
    @Provides
    public static QuickAccessWalletClient provideQuickAccessWalletClient(Context context,
            @Background Executor bgExecutor) {
        return QuickAccessWalletClient.create(context, bgExecutor);
    }

    /** */
    @Binds
    @IntoMap
    @StringKey(QuickAccessWalletTile.TILE_SPEC)
    public abstract QSTileImpl<?> bindQuickAccessWalletTile(
            QuickAccessWalletTile quickAccessWalletTile);
}
