/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import android.content.Context;

import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.external.TileServices;

import java.util.Collection;

public interface QSHost {
    void warn(String message, Throwable t);
    void collapsePanels();
    void forceCollapsePanels();
    void openPanels();
    Context getContext();
    Collection<QSTile> getTiles();
    void addCallback(Callback callback);
    void removeCallback(Callback callback);
    TileServices getTileServices();
    void removeTile(String tileSpec);
    void unmarkTileAsAutoAdded(String tileSpec);

    int indexOf(String tileSpec);

    interface Callback {
        void onTilesChanged();
    }
}
