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
import android.app.Service;
import android.content.Context;
import android.service.quickaccesswallet.QuickAccessWalletClient;

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.qs.QsEventLogger;
import com.android.systemui.qs.pipeline.shared.TileSpec;
import com.android.systemui.qs.shared.model.TileCategory;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.tiles.QuickAccessWalletTile;
import com.android.systemui.qs.tiles.viewmodel.QSTileConfig;
import com.android.systemui.qs.tiles.viewmodel.QSTilePolicy;
import com.android.systemui.qs.tiles.viewmodel.QSTileUIConfig;
import com.android.systemui.res.R;
import com.android.systemui.wallet.controller.WalletContextualLocationsService;
import com.android.systemui.wallet.ui.WalletActivity;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.ClassKey;
import dagger.multibindings.IntoMap;
import dagger.multibindings.StringKey;

import java.util.concurrent.Executor;

/**
 * Module for injecting classes in Wallet.
 */
@Module
public abstract class WalletModule {

    public static final String WALLET_TILE_SPEC = "wallet";

    @Binds
    @IntoMap
    @ClassKey(WalletContextualLocationsService.class)
    abstract Service bindWalletContextualLocationsService(
        WalletContextualLocationsService service);

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

    @Provides
    @IntoMap
    @StringKey(WALLET_TILE_SPEC)
    public static QSTileConfig provideQuickAccessWalletTileConfig(QsEventLogger uiEventLogger) {
        TileSpec tileSpec = TileSpec.create(WALLET_TILE_SPEC);
        return new QSTileConfig(
                tileSpec,
                new QSTileUIConfig.Resource(
                        R.drawable.ic_wallet_lockscreen,
                        R.string.wallet_title
                ),
                uiEventLogger.getNewInstanceId(),
                TileCategory.UTILITIES,
                tileSpec.getSpec(),
                QSTilePolicy.NoRestrictions.INSTANCE
        );
    }
}
