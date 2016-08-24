/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.printservice.recommendation.plugin.samsung;

import android.content.res.Resources;

import java.util.Arrays;

public final class VendorInfo {

    public final String mPackageName;
    public final String mVendorID;
    public final String[] mDNSValues;
    public final int mID;

    public VendorInfo(Resources resources, int vendor_info_id) {
        mID = vendor_info_id;
        String[] data = resources.getStringArray(vendor_info_id);
        if ((data == null) || (data.length < 2)) {
            data = new String[] { null, null };
        }
        mPackageName = data[0];
        mVendorID = data[1];
        mDNSValues = (data.length > 2) ? Arrays.copyOfRange(data, 2, data.length) : new String[]{};
    }
}
