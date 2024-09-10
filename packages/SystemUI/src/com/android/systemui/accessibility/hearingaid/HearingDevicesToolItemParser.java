/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Utility class for managing and parsing tool items related to hearing devices.
 */
public class HearingDevicesToolItemParser {
    private static final String TAG = "HearingDevicesToolItemParser";
    private static final String SPLIT_DELIMITER = "/";
    private static final String RES_TYPE = "drawable";
    @VisibleForTesting
    static final int MAX_NUM = 3;

    /**
     * Parses the string arrays to create a list of {@link ToolItem}.
     *
     * This method validates the structure of {@code toolNameArray} and {@code toolIconArray}.
     * If {@code toolIconArray} is empty or mismatched in length with {@code toolNameArray}, the
     * icon from {@link ActivityInfo#loadIcon(PackageManager)} will be used instead.
     *
     * @param context A valid context.
     * @param toolNameArray An array of tool names in the format of {@link ComponentName}.
     * @param toolIconArray An optional array of resource names for tool icons (can be empty).
     * @return A list of {@link ToolItem} or an empty list if there are errors during parsing.
     */
    public static ImmutableList<ToolItem> parseStringArray(Context context, String[] toolNameArray,
            String[] toolIconArray) {
        if (toolNameArray.length == 0) {
            Log.i(TAG, "Empty hearing device related tool name in array.");
            return ImmutableList.of();
        }
        // For the performance concern, especially `getIdentifier` in `parseValidIcon`, we will
        // limit the maximum number.
        String[] nameArrayCpy = Arrays.copyOfRange(toolNameArray, 0,
                Math.min(toolNameArray.length, MAX_NUM));
        String[] iconArrayCpy = Arrays.copyOfRange(toolIconArray, 0,
                Math.min(toolIconArray.length, MAX_NUM));

        final PackageManager packageManager = context.getPackageManager();
        final ImmutableList.Builder<ToolItem> toolItemList = ImmutableList.builder();
        final List<ActivityInfo> activityInfoList = parseValidActivityInfo(context, nameArrayCpy);
        final List<Drawable> iconList = parseValidIcon(context, iconArrayCpy);
        final int size = activityInfoList.size();
        // Only use custom icon if provided icon's list size is equal to provided name's list size.
        final boolean useCustomIcons = (size == iconList.size());

        for (int i = 0; i < size; i++) {
            toolItemList.add(new ToolItem(
                    activityInfoList.get(i).loadLabel(packageManager).toString(),
                    useCustomIcons ? iconList.get(i)
                            : activityInfoList.get(i).loadIcon(packageManager),
                    new Intent(Intent.ACTION_MAIN).setComponent(
                            activityInfoList.get(i).getComponentName())
            ));
        }

        return toolItemList.build();
    }

    private static List<ActivityInfo> parseValidActivityInfo(Context context,
            String[] toolNameArray) {
        final PackageManager packageManager = context.getPackageManager();
        final List<ActivityInfo> activityInfoList = new ArrayList<>();
        for (String toolName : toolNameArray) {
            String[] nameParts = toolName.split(SPLIT_DELIMITER);
            if (nameParts.length == 2) {
                ComponentName componentName = ComponentName.unflattenFromString(toolName);
                try {
                    ActivityInfo activityInfo = packageManager.getActivityInfo(
                            componentName, /* flags= */ 0);
                    activityInfoList.add(activityInfo);
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "Unable to find hearing device related tool: "
                            + componentName.flattenToString());
                }
            } else {
                Log.e(TAG, "Malformed hearing device related tool name item in array: "
                        + toolName);
            }
        }
        return activityInfoList;
    }

    private static List<Drawable> parseValidIcon(Context context, String[] toolIconArray) {
        final List<Drawable> drawableList = new ArrayList<>();
        for (String icon : toolIconArray) {
            int resId = context.getResources().getIdentifier(icon, RES_TYPE,
                    context.getPackageName());
            try {
                drawableList.add(context.getDrawable(resId));
            } catch (Resources.NotFoundException e) {
                Log.e(TAG, "Resource does not exist: " + icon);
            }
        }
        return drawableList;
    }
}
