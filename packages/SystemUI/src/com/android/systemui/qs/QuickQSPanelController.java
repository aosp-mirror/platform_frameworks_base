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

import static com.android.systemui.qs.QuickQSPanel.NUM_QUICK_TILES;
import static com.android.systemui.qs.QuickQSPanel.parseNumTiles;

import com.android.systemui.dump.DumpManager;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import javax.inject.Inject;

/** Controller for {@link QuickQSPanel}. */
@QSScope
public class QuickQSPanelController extends QSPanelControllerBase<QuickQSPanel> {
    private final Tunable mNumTiles =
            (key, newValue) -> mView.setMaxTiles(parseNumTiles(newValue));
    private final TunerService mTunerService;

    @Inject
    QuickQSPanelController(QuickQSPanel view, TunerService tunerService, QSTileHost qsTileHost,
            DumpManager dumpManager) {
        super(view, qsTileHost, dumpManager);
        mTunerService = tunerService;
    }

    @Override
    protected void onViewAttached() {
        super.onViewAttached();
        mTunerService.addTunable(mNumTiles, NUM_QUICK_TILES);

    }

    @Override
    protected void onViewDetached() {
        super.onViewDetached();
        mTunerService.removeTunable(mNumTiles);
    }
}
