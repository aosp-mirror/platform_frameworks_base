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
import android.util.ArraySet;
import android.util.Log;
import android.util.SizeF;
import android.util.Slog;
import android.util.SparseArray;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    static final String TAG_BACKUP_RESTORE_CONTROLLER_STATE = "br";
    private static final String TAG_PRUNED_APPS = "pruned_apps";
    private static final String ATTR_TAG = "tag";
    private static final String ATTR_PACKAGE_NAMES = "pkgs";
    private static final String TAG_PROVIDER_UPDATES = "provider_updates";
    private static final String TAG_HOST_UPDATES = "host_updates";
    private static final String TAG_RECORD = "record";
    private static final String ATTR_OLD_ID = "old_id";
    private static final String ATTR_NEW_ID = "new_id";
    private static final String ATTR_NOTIFIED = "notified";
    private static final String SIZE_SEPARATOR = ",";

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
        info.minResizeHeight = parser.getAttributeInt(null, ATTR_MIN_RESIZE_HEIGHT, 0);
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

    @NonNull
    static String serializeWidgetSizes(@NonNull List<SizeF> sizes) {
        return sizes.stream().map(SizeF::toString)
                .collect(Collectors.joining(SIZE_SEPARATOR));
    }

    @Nullable
    static ArrayList<SizeF> deserializeWidgetSizesStr(@Nullable String sizesStr) {
        if (sizesStr == null || sizesStr.isEmpty()) {
            return null;
        }
        try {
            return Arrays.stream(sizesStr.split(SIZE_SEPARATOR))
                    .map(SizeF::parseSizeF)
                    .collect(Collectors.toCollection(ArrayList::new));
        } catch (NumberFormatException e) {
            Slog.e(TAG, "Error parsing widget sizes", e);
            return null;
        }
    }

    /**
     * Persists {@link AppWidgetServiceImpl.BackupRestoreController.State} to disk as XML.
     * See {@link #readBackupRestoreControllerState(TypedXmlPullParser)} for example XML.
     *
     * @param out XML serializer
     * @param state {@link AppWidgetServiceImpl.BackupRestoreController.State} of
     *      intermediate states to be persisted as xml to resume restore after reboot.
     */
    static void writeBackupRestoreControllerState(
            @NonNull final TypedXmlSerializer out,
            @NonNull final AppWidgetServiceImpl.BackupRestoreController.State state)
            throws IOException {
        Objects.requireNonNull(out);
        Objects.requireNonNull(state);
        out.startTag(null, TAG_BACKUP_RESTORE_CONTROLLER_STATE);
        final Set<String> prunedApps = state.getPrunedApps();
        if (prunedApps != null && !prunedApps.isEmpty()) {
            out.startTag(null, TAG_PRUNED_APPS);
            out.attribute(null, ATTR_PACKAGE_NAMES, String.join(",", prunedApps));
            out.endTag(null, TAG_PRUNED_APPS);
        }
        final SparseArray<List<AppWidgetServiceImpl.BackupRestoreController.RestoreUpdateRecord>>
                updatesByProvider = state.getUpdatesByProvider();
        if (updatesByProvider != null) {
            writeUpdateRecords(out, TAG_PROVIDER_UPDATES, updatesByProvider);
        }
        final SparseArray<List<AppWidgetServiceImpl.BackupRestoreController.RestoreUpdateRecord>>
                updatesByHost = state.getUpdatesByHost();
        if (updatesByHost != null) {
            writeUpdateRecords(out, TAG_HOST_UPDATES, updatesByHost);
        }
        out.endTag(null, TAG_BACKUP_RESTORE_CONTROLLER_STATE);
    }

    private static void writeUpdateRecords(@NonNull final TypedXmlSerializer out,
            @NonNull final String outerTag, @NonNull final SparseArray<List<
                    AppWidgetServiceImpl.BackupRestoreController.RestoreUpdateRecord>> records)
            throws IOException {
        for (int i = 0; i < records.size(); i++) {
            final int tag = records.keyAt(i);
            out.startTag(null, outerTag);
            out.attributeInt(null, ATTR_TAG, tag);
            final List<AppWidgetServiceImpl.BackupRestoreController.RestoreUpdateRecord> entries =
                    records.get(tag);
            for (AppWidgetServiceImpl.BackupRestoreController.RestoreUpdateRecord entry : entries) {
                out.startTag(null, TAG_RECORD);
                out.attributeInt(null, ATTR_OLD_ID, entry.oldId);
                out.attributeInt(null, ATTR_NEW_ID, entry.newId);
                out.attributeBoolean(null, ATTR_NOTIFIED, entry.notified);
                out.endTag(null, TAG_RECORD);
            }
            out.endTag(null, outerTag);
        }
    }

    /**
     * Parses {@link AppWidgetServiceImpl.BackupRestoreController.State} from xml.
     *
     * <pre>
     * {@code
     *     <?xml version="1.0"?>
     *     <br>
     *         <pruned_apps pkgs="com.example.app1,com.example.app2,com.example.app3" />
     *         <provider_updates tag="0">
     *             <record old_id="10" new_id="0" notified="false" />
     *         </provider_updates>
     *         <provider_updates tag="1">
     *             <record old_id="9" new_id="1" notified="true" />
     *         </provider_updates>
     *         <provider_updates tag="2">
     *             <record old_id="8" new_id="2" notified="false" />
     *         </provider_updates>
     *         <host_updates tag="0">
     *             <record old_id="10" new_id="0" notified="false" />
     *         </host_updates>
     *         <host_updates tag="1">
     *             <record old_id="9" new_id="1" notified="true" />
     *         </host_updates>
     *         <host_updates tag="2">
     *             <record old_id="8" new_id="2" notified="false" />
     *         </host_updates>
     *     </br>
     * }
     * </pre>
     *
     * @param parser XML parser
     * @return {@link AppWidgetServiceImpl.BackupRestoreController.State} of intermediate states
     * in {@link AppWidgetServiceImpl.BackupRestoreController}, so that backup & restore can be
     * resumed after reboot.
     */
    @Nullable
    static AppWidgetServiceImpl.BackupRestoreController.State
            readBackupRestoreControllerState(@NonNull final TypedXmlPullParser parser) {
        Objects.requireNonNull(parser);
        int type;
        String tag = null;
        final Set<String> prunedApps = new ArraySet<>(1);
        final SparseArray<List<AppWidgetServiceImpl.BackupRestoreController.RestoreUpdateRecord>>
                updatesByProviders = new SparseArray<>();
        final SparseArray<List<AppWidgetServiceImpl.BackupRestoreController.RestoreUpdateRecord>>
                updatesByHosts = new SparseArray<>();

        try {
            do {
                type = parser.next();
                if (type != XmlPullParser.START_TAG) {
                    continue;
                }
                tag = parser.getName();
                switch (tag) {
                    case TAG_PRUNED_APPS:
                        final String packages =
                                parser.getAttributeValue(null, ATTR_PACKAGE_NAMES);
                        prunedApps.addAll(Arrays.asList(packages.split(",")));
                        break;
                    case TAG_PROVIDER_UPDATES:
                        updatesByProviders.put(parser.getAttributeInt(null, ATTR_TAG),
                                parseRestoreUpdateRecords(parser));
                        break;
                    case TAG_HOST_UPDATES:
                        updatesByHosts.put(parser.getAttributeInt(null, ATTR_TAG),
                                parseRestoreUpdateRecords(parser));
                        break;
                    default:
                        break;
                }
            } while (type != XmlPullParser.END_DOCUMENT
                    && (!TAG_BACKUP_RESTORE_CONTROLLER_STATE.equals(tag)
                            || type != XmlPullParser.END_TAG));
        } catch (IOException | XmlPullParserException e) {
            Log.e(TAG, "error parsing state", e);
            return null;
        }
        return new AppWidgetServiceImpl.BackupRestoreController.State(
                prunedApps, updatesByProviders, updatesByHosts);
    }

    @NonNull
    private static List<
            AppWidgetServiceImpl.BackupRestoreController.RestoreUpdateRecord
            > parseRestoreUpdateRecords(@NonNull final TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        int type;
        String tag;
        final List<AppWidgetServiceImpl.BackupRestoreController.RestoreUpdateRecord> ret =
                new ArrayList<>();
        do {
            type = parser.next();
            tag = parser.getName();
            if (tag.equals(TAG_RECORD) && type == XmlPullParser.START_TAG) {
                final int oldId = parser.getAttributeInt(null, ATTR_OLD_ID);
                final int newId = parser.getAttributeInt(null, ATTR_NEW_ID);
                final boolean notified = parser.getAttributeBoolean(
                        null, ATTR_NOTIFIED);
                final AppWidgetServiceImpl.BackupRestoreController.RestoreUpdateRecord record =
                        new AppWidgetServiceImpl.BackupRestoreController.RestoreUpdateRecord(
                                oldId, newId);
                record.notified = notified;
                ret.add(record);
            }
        } while (tag.equals(TAG_RECORD));
        return ret;
    }
}
