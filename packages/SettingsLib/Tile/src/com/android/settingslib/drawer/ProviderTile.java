/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.settingslib.drawer;

import static com.android.settingslib.drawer.TileUtils.META_DATA_PREFERENCE_KEYHINT;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;

import java.util.List;
import java.util.Objects;

/**
 * Description of a single dashboard tile which is generated from a content provider.
 */
public class ProviderTile extends Tile {
    private static final String TAG = "ProviderTile";

    private static final boolean DEBUG_TIMING = false;

    private String mAuthority;
    private String mKey;

    public ProviderTile(ProviderInfo info, String category, Bundle metaData) {
        super(info, category, metaData);
        mAuthority = info.authority;
        mKey = metaData.getString(META_DATA_PREFERENCE_KEYHINT);
    }

    ProviderTile(Parcel in) {
        super(in);
        mAuthority = ((ProviderInfo) mComponentInfo).authority;
        mKey = getMetaData().getString(META_DATA_PREFERENCE_KEYHINT);
    }

    @Override
    public int getId() {
        return Objects.hash(mAuthority, mKey);
    }

    @Override
    public String getDescription() {
        return mAuthority + "/" + mKey;
    }

    @Override
    protected ComponentInfo getComponentInfo(Context context) {
        if (mComponentInfo == null) {
            final long startTime = System.currentTimeMillis();
            final PackageManager pm = context.getApplicationContext().getPackageManager();
            final Intent intent = getIntent();
            final List<ResolveInfo> infoList =
                    pm.queryIntentContentProviders(intent, 0 /* flags */);
            if (infoList != null && !infoList.isEmpty()) {
                final ProviderInfo providerInfo = infoList.get(0).providerInfo;
                mComponentInfo = providerInfo;
                setMetaData(TileUtils.getEntryDataFromProvider(context, providerInfo.authority,
                        mKey));
            } else {
                Log.e(TAG, "Cannot find package info for "
                        + intent.getComponent().flattenToString());
            }

            if (DEBUG_TIMING) {
                Log.d(TAG, "getComponentInfo took "
                        + (System.currentTimeMillis() - startTime) + " ms");
            }
        }
        return mComponentInfo;
    }

    @Override
    protected CharSequence getComponentLabel(Context context) {
        // Getting provider label for a tile title isn't supported.
        return null;
    }

    @Override
    protected int getComponentIcon(ComponentInfo info) {
        // Getting provider icon for a tile title isn't supported.
        return 0;
    }
}
