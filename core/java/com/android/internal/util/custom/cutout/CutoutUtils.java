/*
* Copyright (C) 2019 The Pixel Experience Project
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
package com.android.internal.util.custom.cutout;

import android.content.Context;

public class CutoutUtils {
    public static boolean hasCutout(Context context) {
        boolean hasCutout = context.getResources().getBoolean(com.android.internal.R.bool.config_physicalDisplayCutout);
        if (hasCutout){
            return !context.getResources().getBoolean(
                com.android.internal.R.bool.config_maskMainBuiltInDisplayCutout);
        }
        return false;
    }
    public static boolean hasBigCutout(Context context) {
        if (!hasCutout(context)){
            return false;
        }
        boolean hasBigCutout = context.getResources().getBoolean(com.android.internal.R.bool.config_bigPhysicalDisplayCutout);
        if (hasBigCutout){
            return !context.getResources().getBoolean(
                com.android.internal.R.bool.config_maskMainBuiltInDisplayCutout);
        }
        return false;
    }
}
