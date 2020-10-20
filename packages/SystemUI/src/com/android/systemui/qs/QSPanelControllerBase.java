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

import com.android.systemui.dump.DumpManager;
import com.android.systemui.util.ViewController;

abstract class QSPanelControllerBase<T extends QSPanel> extends ViewController<T> {
    private final QSTileHost mHost;
    private final DumpManager mDumpManager;

    protected QSPanelControllerBase(T view, QSTileHost host, DumpManager dumpManager) {
        super(view);
        mHost = host;
        mDumpManager = dumpManager;
        mView.setHost(mHost);
    }

    @Override
    protected void onViewAttached() {
        mView.setTiles(mHost.getTiles());
        mDumpManager.registerDumpable(mView.getDumpableTag(), mView);
    }

    @Override
    protected void onViewDetached() {
        mHost.removeCallback(mView);
        mDumpManager.unregisterDumpable(mView.getDumpableTag());
    }
}
