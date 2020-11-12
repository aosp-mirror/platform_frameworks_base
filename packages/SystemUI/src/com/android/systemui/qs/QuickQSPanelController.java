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

package com.android.systemui.qs;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSScope;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/** Controller for {@link QuickQSPanel}. */
@QSScope
public class QuickQSPanelController extends QSPanelControllerBase<QuickQSPanel> {

    private List<QSTile> mAllTiles = new ArrayList<>();

    private final QSPanel.OnConfigurationChangedListener mOnConfigurationChangedListener =
            newConfig -> {
                int newMaxTiles = getResources().getInteger(R.integer.quick_qs_panel_max_columns);
                if (newMaxTiles != mView.getNumQuickTiles()) {
                    setMaxTiles(newMaxTiles);
                }
            };

    @Inject
    QuickQSPanelController(QuickQSPanel view, QSTileHost qsTileHost,
            QSCustomizerController qsCustomizerController,
            MetricsLogger metricsLogger, UiEventLogger uiEventLogger,
            DumpManager dumpManager) {
        super(view, qsTileHost, qsCustomizerController, metricsLogger, uiEventLogger, dumpManager);
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        mView.addOnConfigurationChangedListener(mOnConfigurationChangedListener);
    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mView.removeOnConfigurationChangedListener(mOnConfigurationChangedListener);
    }

    public boolean isListening() {
        return mView.isListening();
    }

    private void setMaxTiles(int parseNumTiles) {
        mView.setMaxTiles(parseNumTiles);
        setTiles();
    }

    @Override
    public void setTiles() {
        mAllTiles.clear();
        for (QSTile tile : mHost.getTiles()) {
            mAllTiles.add(tile);
            if (mAllTiles.size() == QuickQSPanel.DEFAULT_MAX_TILES) {
                break;
            }
        }
        super.setTiles(mAllTiles.subList(0, mView.getNumQuickTiles()), true);
    }

    public int getNumQuickTiles() {
        return mView.getNumQuickTiles();
    }
}
