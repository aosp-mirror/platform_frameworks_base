/*
* Copyright (C) 2020 The Pixel Experience Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package com.android.internal.util.xtended.udfps;

import android.content.Context;

import com.android.internal.util.ArrayUtils;

public class UdfpsUtils {
    public static boolean hasUdfpsSupport(Context context) {
        int[] udfpsProps = context.getResources().getIntArray(
                com.android.internal.R.array.config_udfps_sensor_props);

        return !ArrayUtils.isEmpty(udfpsProps);
    }
}
