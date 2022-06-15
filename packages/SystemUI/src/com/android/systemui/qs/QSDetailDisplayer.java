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

import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.plugins.qs.DetailAdapter;

import javax.inject.Inject;

/**
 * Proxy class for talking with the QSPanel and showing custom content within it.
 */
@SysUISingleton
public class QSDetailDisplayer {
    private QSPanelController mQsPanelController;

    @Inject
    public QSDetailDisplayer() {
    }

    public void setQsPanelController(QSPanelController qsPanelController) {
        mQsPanelController = qsPanelController;
    }

    /** Show the supplied DetailAdapter in the Quick Settings. */
    public void showDetailAdapter(DetailAdapter detailAdapter, int x, int y) {
        if (mQsPanelController != null) {
            mQsPanelController.showDetailAdapter(detailAdapter, x, y);
        }
    }
}
