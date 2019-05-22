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

package com.android.systemui.statusbar.car.privacy;

import android.content.Context;
import android.graphics.drawable.Drawable;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Helper class to build the {@link OngoingPrivacyDialog}
 */
public class PrivacyDialogBuilder {

    private Map<PrivacyType, List<PrivacyItem>> mItemsByType;
    private PrivacyApplication mApplication;
    private Context mContext;

    public PrivacyDialogBuilder(Context context, List<PrivacyItem> itemsList) {
        mContext = context;
        mItemsByType = itemsList.stream().filter(Objects::nonNull).collect(
                Collectors.groupingBy(PrivacyItem::getPrivacyType));
        List<PrivacyApplication> apps = itemsList.stream().filter(Objects::nonNull).map(
                PrivacyItem::getPrivacyApplication).distinct().collect(Collectors.toList());
        mApplication = apps.size() == 1 ? apps.get(0) : null;
    }

    /**
     * Gets the icon id for all the {@link PrivacyItem} in the same order as of itemList.
     */
    public List<Drawable> generateIcons() {
        return mItemsByType.keySet().stream().map(item -> item.getIconId(mContext)).collect(
                Collectors.toList());
    }

    /**
     * Gets the application object.
     */
    public PrivacyApplication getApplication() {
        return mApplication;
    }
}
