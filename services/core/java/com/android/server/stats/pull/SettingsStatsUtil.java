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

package com.android.server.stats.pull;

import static com.android.internal.util.FrameworkStatsLog.SETTING_SNAPSHOT__TYPE__ASSIGNED_BOOL_TYPE;
import static com.android.internal.util.FrameworkStatsLog.SETTING_SNAPSHOT__TYPE__ASSIGNED_FLOAT_TYPE;
import static com.android.internal.util.FrameworkStatsLog.SETTING_SNAPSHOT__TYPE__ASSIGNED_INT_TYPE;
import static com.android.internal.util.FrameworkStatsLog.SETTING_SNAPSHOT__TYPE__ASSIGNED_STRING_TYPE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Slog;
import android.util.StatsEvent;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FrameworkStatsLog;
import com.android.service.nano.StringListParamProto;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods for creating {@link StatsEvent} data.
 */
final class SettingsStatsUtil {
    private static final String TAG = "SettingsStatsUtil";
    private static final FlagsData[] GLOBAL_SETTINGS = new FlagsData[]{
            new FlagsData("GlobalFeature__boolean_whitelist",
                    SETTING_SNAPSHOT__TYPE__ASSIGNED_BOOL_TYPE),
            new FlagsData("GlobalFeature__integer_whitelist",
                    SETTING_SNAPSHOT__TYPE__ASSIGNED_INT_TYPE),
            new FlagsData("GlobalFeature__float_whitelist",
                    SETTING_SNAPSHOT__TYPE__ASSIGNED_FLOAT_TYPE),
            new FlagsData("GlobalFeature__string_whitelist",
                    SETTING_SNAPSHOT__TYPE__ASSIGNED_STRING_TYPE)
    };
    private static final FlagsData[] SECURE_SETTINGS = new FlagsData[]{
            new FlagsData("SecureFeature__boolean_whitelist",
                    SETTING_SNAPSHOT__TYPE__ASSIGNED_BOOL_TYPE),
            new FlagsData("SecureFeature__integer_whitelist",
                    SETTING_SNAPSHOT__TYPE__ASSIGNED_INT_TYPE),
            new FlagsData("SecureFeature__float_whitelist",
                    SETTING_SNAPSHOT__TYPE__ASSIGNED_FLOAT_TYPE),
            new FlagsData("SecureFeature__string_whitelist",
                    SETTING_SNAPSHOT__TYPE__ASSIGNED_STRING_TYPE)
    };
    private static final FlagsData[] SYSTEM_SETTINGS = new FlagsData[]{
            new FlagsData("SystemFeature__boolean_whitelist",
                    SETTING_SNAPSHOT__TYPE__ASSIGNED_BOOL_TYPE),
            new FlagsData("SystemFeature__integer_whitelist",
                    SETTING_SNAPSHOT__TYPE__ASSIGNED_INT_TYPE),
            new FlagsData("SystemFeature__float_whitelist",
                    SETTING_SNAPSHOT__TYPE__ASSIGNED_FLOAT_TYPE),
            new FlagsData("SystemFeature__string_whitelist",
                    SETTING_SNAPSHOT__TYPE__ASSIGNED_STRING_TYPE)
    };

    @VisibleForTesting
    @NonNull
    static List<StatsEvent> logGlobalSettings(Context context, int atomTag, int userId) {
        final List<StatsEvent> output = new ArrayList<>();
        final ContentResolver resolver = context.getContentResolver();

        for (FlagsData flagsData : GLOBAL_SETTINGS) {
            StringListParamProto proto = getList(flagsData.mFlagName);
            if (proto == null) {
                continue;
            }
            for (String key : proto.element) {
                final String value = Settings.Global.getStringForUser(resolver, key, userId);
                output.add(createStatsEvent(atomTag, key, value, userId,
                        flagsData.mDataType));
            }
        }
        return output;
    }

    @NonNull
    static List<StatsEvent> logSystemSettings(Context context, int atomTag, int userId) {
        final List<StatsEvent> output = new ArrayList<>();
        final ContentResolver resolver = context.getContentResolver();

        for (FlagsData flagsData : SYSTEM_SETTINGS) {
            StringListParamProto proto = getList(flagsData.mFlagName);
            if (proto == null) {
                continue;
            }
            for (String key : proto.element) {
                final String value = Settings.System.getStringForUser(resolver, key, userId);
                final String telemetryValue = RawSettingsTelemetryUtils
                        .getTelemetrySettingFromRawVal(context, key, value);
                output.add(createStatsEvent(atomTag, key, telemetryValue, userId,
                        flagsData.mDataType));
            }
        }
        return output;
    }

    @NonNull
    static List<StatsEvent> logSecureSettings(Context context, int atomTag, int userId) {
        final List<StatsEvent> output = new ArrayList<>();
        final ContentResolver resolver = context.getContentResolver();

        for (FlagsData flagsData : SECURE_SETTINGS) {
            StringListParamProto proto = getList(flagsData.mFlagName);
            if (proto == null) {
                continue;
            }
            for (String key : proto.element) {
                final String value = Settings.Secure.getStringForUser(resolver, key, userId);
                output.add(createStatsEvent(atomTag, key, value, userId,
                        flagsData.mDataType));
            }
        }
        return output;
    }

    @VisibleForTesting
    @Nullable
    static StringListParamProto getList(String flag) {
        final String base64 = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_SETTINGS_STATS, flag);
        if (TextUtils.isEmpty(base64)) {
            return null;
        }
        final byte[] decode = Base64.decode(base64, Base64.NO_PADDING | Base64.NO_WRAP);
        StringListParamProto list = null;
        try {
            list = StringListParamProto.parseFrom(decode);
        } catch (Exception e) {
            Slog.e(TAG, "Error parsing string list proto", e);
        }
        return list;
    }

    /**
     * Create {@link StatsEvent} for SETTING_SNAPSHOT atom
     */
    @NonNull
    private static StatsEvent createStatsEvent(int atomTag, String key, String value, int userId,
            int type) {
        final StatsEvent.Builder builder = StatsEvent.newBuilder()
                .setAtomId(atomTag)
                .writeString(key);
        boolean booleanValue = false;
        int intValue = 0;
        float floatValue = 0;
        String stringValue = "";
        if (TextUtils.isEmpty(value)) {
            builder.writeInt(FrameworkStatsLog.SETTING_SNAPSHOT__TYPE__NOTASSIGNED)
                    .writeBoolean(booleanValue)
                    .writeInt(intValue)
                    .writeFloat(floatValue)
                    .writeString(stringValue)
                    .writeInt(userId);
        } else {
            switch (type) {
                case SETTING_SNAPSHOT__TYPE__ASSIGNED_BOOL_TYPE:
                    booleanValue = "1".equals(value);
                    break;
                case FrameworkStatsLog.SETTING_SNAPSHOT__TYPE__ASSIGNED_INT_TYPE:
                    try {
                        intValue = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        Slog.w(TAG, "Can not parse value to float: " + value);
                    }
                    break;
                case SETTING_SNAPSHOT__TYPE__ASSIGNED_FLOAT_TYPE:
                    try {
                        floatValue = Float.parseFloat(value);
                    } catch (NumberFormatException e) {
                        Slog.w(TAG, "Can not parse value to float: " + value);
                    }
                    break;
                case FrameworkStatsLog.SETTING_SNAPSHOT__TYPE__ASSIGNED_STRING_TYPE:
                    stringValue = value;
                    break;
                default:
                    Slog.w(TAG, "Unexpected value type " + type);
            }
            builder.writeInt(type)
                    .writeBoolean(booleanValue)
                    .writeInt(intValue)
                    .writeFloat(floatValue)
                    .writeString(stringValue)
                    .writeInt(userId);
        }
        return builder.build();
    }

    /** Class for defining flag name and its data type. */
    static final class FlagsData {
        /** {@link DeviceConfig} flag name, value of the flag is {@link StringListParamProto} */
        String mFlagName;
        /** Data type of the value getting from {@link Settings} keys. */
        int mDataType;

        FlagsData(String flagName, int dataType) {
            mFlagName = flagName;
            mDataType = dataType;
        }
    }
}
