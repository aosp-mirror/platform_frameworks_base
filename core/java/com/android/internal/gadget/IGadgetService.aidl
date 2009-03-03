/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.gadget;

import android.content.ComponentName;
import android.gadget.GadgetProviderInfo;
import com.android.internal.gadget.IGadgetHost;
import android.widget.RemoteViews;

/** {@hide} */
interface IGadgetService {
    
    //
    // for GadgetHost
    //
    int[] startListening(IGadgetHost host, String packageName, int hostId,
            out List<RemoteViews> updatedViews);
    void stopListening(int hostId);
    int allocateGadgetId(String packageName, int hostId);
    void deleteGadgetId(int gadgetId);
    void deleteHost(int hostId);
    void deleteAllHosts();
    RemoteViews getGadgetViews(int gadgetId);

    //
    // for GadgetManager
    //
    void updateGadgetIds(in int[] gadgetIds, in RemoteViews views);
    void updateGadgetProvider(in ComponentName provider, in RemoteViews views);
    List<GadgetProviderInfo> getInstalledProviders();
    GadgetProviderInfo getGadgetInfo(int gadgetId);
    void bindGadgetId(int gadgetId, in ComponentName provider);
    int[] getGadgetIds(in ComponentName provider);

}

