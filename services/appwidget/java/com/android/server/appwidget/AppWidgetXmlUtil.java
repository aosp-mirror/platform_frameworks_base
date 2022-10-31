/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.appwidget;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.os.Build;
import android.text.TextUtils;
import android.util.Slog;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import java.io.IOException;
import java.util.Objects;

/**
 * @hide
 */
public class AppWidgetXmlUtil {

    private static final String TAG = "AppWidgetXmlUtil";

    private static final String ATTR_MIN_WIDTH = "min_width";
    private static final String ATTR_MIN_HEIGHT = "min_height";
    private static final String ATTR_MIN_RESIZE_WIDTH = "min_resize_width";
    private static final String ATTR_MIN_RESIZE_HEIGHT = "min_resize_height";
    private static final String ATTR_MAX_RESIZE_WIDTH = "max_resize_width";
    private static final String ATTR_MAX_RESIZE_HEIGHT = "max_resize_height";
    private static final String ATTR_TARGET_CELL_WIDTH = "target_cell_width";
    private static final String ATTR_TARGET_CELL_HEIGHT = "target_cell_height";
    private static final String ATTR_UPDATE_PERIOD_MILLIS = "update_period_millis";
    private static final String ATTR_INITIAL_LAYOUT = "initial_layout";
    private static final String ATTR_INITIAL_KEYGUARD_LAYOUT = "initial_keyguard_layout";
    private static final String ATTR_CONFIGURE = "configure";
    private static final String ATTR_LABEL = "label";
    private static final String ATTR_ICON = "icon";
    private static final String ATTR_PREVIEW_IMAGE = "preview_image";
    private static final String ATTR_PREVIEW_LAYOUT = "preview_layout";
    private static final String ATTR_AUTO_ADVANCED_VIEW_ID = "auto_advance_view_id";
    private static final String ATTR_RESIZE_MODE = "resize_mode";
    private static final String ATTR_WIDGET_CATEGORY = "widget_category";
    private static final String ATTR_WIDGET_FEATURES = "widget_features";
    private static final String ATTR_DESCRIPTION_RES = "description_res";
    private static final String ATTR_PROVIDER_INHERITANCE = "provider_inheritance";
    private static final String ATTR_OS_FINGERPRINT = "os_fingerprint";

    /**
     * @hide
     */
    public static void writeAppWidgetProviderInfoLocked(@NonNull final TypedXmlSerializer out,
            @NonNull final AppWidgetProviderInfo info) throws IOException {
        Objects.requireNonNull(out);
        Objects.requireNonNull(info);
        out.attributeInt(null, ATTR_MIN_WIDTH, info.minWidth);
        out.attributeInt(null, ATTR_MIN_HEIGHT, info.minHeight);
        out.attributeInt(null, ATTR_MIN_RESIZE_WIDTH, info.minResizeWidth);
        out.attributeInt(null, ATTR_MIN_RESIZE_HEIGHT, info.minResizeHeight);
        out.attributeInt(null, ATTR_MAX_RESIZE_WIDTH, info.maxResizeWidth);
        out.attributeInt(null, ATTR_MAX_RESIZE_HEIGHT, info.maxResizeHeight);
        out.attributeInt(null, ATTR_TARGET_CELL_WIDTH, info.targetCellWidth);
        out.attributeInt(null, ATTR_TARGET_CELL_HEIGHT, info.targetCellHeight);
        out.attributeInt(null, ATTR_UPDATE_PERIOD_MILLIS, info.updatePeriodMillis);
        out.attributeInt(null, ATTR_INITIAL_LAYOUT, info.initialLayout);
        out.attributeInt(null, ATTR_INITIAL_KEYGUARD_LAYOUT, info.initialKeyguardLayout);
        if (info.configure != null) {
            out.attribute(null, ATTR_CONFIGURE, info.configure.flattenToShortString());
        }
        if (info.label != null) {
            out.attribute(null, ATTR_LABEL, info.label);
        } else if (AppWidgetServiceImpl.DEBUG_PROVIDER_INFO_CACHE) {
            Slog.e(TAG, "Label is empty in " + info.provider);
        }
        out.attributeInt(null, ATTR_ICON, info.icon);
        out.attributeInt(null, ATTR_PREVIEW_IMAGE, info.previewImage);
        out.attributeInt(null, ATTR_PREVIEW_LAYOUT, info.previewLayout);
        out.attributeInt(null, ATTR_AUTO_ADVANCED_VIEW_ID, info.autoAdvanceViewId);
        out.attributeInt(null, ATTR_RESIZE_MODE, info.resizeMode);
        out.attributeInt(null, ATTR_WIDGET_CATEGORY, info.widgetCategory);
        out.attributeInt(null, ATTR_WIDGET_FEATURES, info.widgetFeatures);
        out.attributeInt(null, ATTR_DESCRIPTION_RES, info.descriptionRes);
        out.attributeBoolean(null, ATTR_PROVIDER_INHERITANCE, info.isExtendedFromAppWidgetProvider);
        out.attribute(null, ATTR_OS_FINGERPRINT, Build.FINGERPRINT);
    }

    /**
     * @hide
     */
    @Nullable
    public static AppWidgetProviderInfo readAppWidgetProviderInfoLocked(
            @NonNull final TypedXmlPullParser parser) {
        Objects.requireNonNull(parser);
        final String fingerprint = parser.getAttributeValue(null, ATTR_OS_FINGERPRINT);
        if (!Build.FINGERPRINT.equals(fingerprint)) {
            return null;
        }
        final AppWidgetProviderInfo info = new AppWidgetProviderInfo();
        info.minWidth = parser.getAttributeInt(null, ATTR_MIN_WIDTH, 0);
        info.minHeight = parser.getAttributeInt(null, ATTR_MIN_HEIGHT, 0);
        info.minResizeWidth = parser.getAttributeInt(null, ATTR_MIN_RESIZE_WIDTH, 0);
        info.minResizeWidth = parser.getAttributeInt(null, ATTR_MIN_RESIZE_HEIGHT, 0);
        info.maxResizeWidth = parser.getAttributeInt(null, ATTR_MAX_RESIZE_WIDTH, 0);
        info.maxResizeHeight = parser.getAttributeInt(null, ATTR_MAX_RESIZE_HEIGHT, 0);
        info.targetCellWidth = parser.getAttributeInt(null, ATTR_TARGET_CELL_WIDTH, 0);
        info.targetCellHeight = parser.getAttributeInt(null, ATTR_TARGET_CELL_HEIGHT, 0);
        info.updatePeriodMillis = parser.getAttributeInt(null, ATTR_UPDATE_PERIOD_MILLIS, 0);
        info.initialLayout = parser.getAttributeInt(null, ATTR_INITIAL_LAYOUT, 0);
        info.initialKeyguardLayout = parser.getAttributeInt(
                null, ATTR_INITIAL_KEYGUARD_LAYOUT, 0);
        final String configure = parser.getAttributeValue(null, ATTR_CONFIGURE);
        if (!TextUtils.isEmpty(configure)) {
            info.configure = ComponentName.unflattenFromString(configure);
        }
        info.label = parser.getAttributeValue(null, ATTR_LABEL);
        info.icon = parser.getAttributeInt(null, ATTR_ICON, 0);
        info.previewImage = parser.getAttributeInt(null, ATTR_PREVIEW_IMAGE, 0);
        info.previewLayout = parser.getAttributeInt(null, ATTR_PREVIEW_LAYOUT, 0);
        info.autoAdvanceViewId = parser.getAttributeInt(null, ATTR_AUTO_ADVANCED_VIEW_ID, 0);
        info.resizeMode = parser.getAttributeInt(null, ATTR_RESIZE_MODE, 0);
        info.widgetCategory = parser.getAttributeInt(null, ATTR_WIDGET_CATEGORY, 0);
        info.widgetFeatures = parser.getAttributeInt(null, ATTR_WIDGET_FEATURES, 0);
        info.descriptionRes = parser.getAttributeInt(null, ATTR_DESCRIPTION_RES, 0);
        info.isExtendedFromAppWidgetProvider = parser.getAttributeBoolean(null,
            ATTR_PROVIDER_INHERITANCE, false);
        return info;
    }
}
