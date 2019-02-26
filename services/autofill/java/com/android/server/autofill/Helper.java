/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.autofill;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.ViewNode;
import android.content.ComponentName;
import android.metrics.LogMaker;
import android.provider.DeviceConfig;
import android.service.autofill.Dataset;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Slog;
import android.view.WindowManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.ArrayUtils;

import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;

public final class Helper {

    private static final String TAG = "AutofillHelper";

    // TODO(b/117779333): get rid of sDebug / sVerbose and always use the service variables instead

    /**
     * Defines a logging flag that can be dynamically changed at runtime using
     * {@code cmd autofill set log_level debug} or through
     * {@link android.provider.Settings.Global#AUTOFILL_LOGGING_LEVEL}.
     */
    public static boolean sDebug = false;

    /**
     * Defines a logging flag that can be dynamically changed at runtime using
     * {@code cmd autofill set log_level verbose} or through
     * {@link android.provider.Settings.Global#AUTOFILL_LOGGING_LEVEL}.
     */
    public static boolean sVerbose = false;

    /**
     * When non-null, overrides whether the UI should be shown on full-screen mode.
     *
     * <p>Note: access to this variable is not synchronized because it's "final" on real usage -
     * it's only set by Shell cmd, for development purposes.
     */
    public static Boolean sFullScreenMode = null;

    private Helper() {
        throw new UnsupportedOperationException("contains static members only");
    }

    @Nullable
    static AutofillId[] toArray(@Nullable ArraySet<AutofillId> set) {
        if (set == null) return null;

        final AutofillId[] array = new AutofillId[set.size()];
        for (int i = 0; i < set.size(); i++) {
            array[i] = set.valueAt(i);
        }
        return array;
    }

    @NonNull
    public static String paramsToString(@NonNull WindowManager.LayoutParams params) {
        final StringBuilder builder = new StringBuilder(25);
        params.dumpDimensions(builder);
        return builder.toString();
    }

    @NonNull
    static ArrayMap<AutofillId, AutofillValue> getFields(@NonNull Dataset dataset) {
        final ArrayList<AutofillId> ids = dataset.getFieldIds();
        final ArrayList<AutofillValue> values = dataset.getFieldValues();
        final int size = ids == null ? 0 : ids.size();
        final ArrayMap<AutofillId, AutofillValue> fields = new ArrayMap<>(size);
        for (int i = 0; i < size; i++) {
            fields.put(ids.get(i), values.get(i));
        }
        return fields;
    }

    @NonNull
    private static LogMaker newLogMaker(int category, @NonNull String servicePackageName,
            int sessionId, boolean compatMode) {
        final LogMaker log = new LogMaker(category)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE, servicePackageName)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SESSION_ID, Integer.toString(sessionId));
        if (compatMode) {
            log.addTaggedData(MetricsEvent.FIELD_AUTOFILL_COMPAT_MODE, 1);
        }
        return log;
    }

    @NonNull
    public static LogMaker newLogMaker(int category, @NonNull String packageName,
            @NonNull String servicePackageName, int sessionId, boolean compatMode) {
        return newLogMaker(category, servicePackageName, sessionId, compatMode)
                .setPackageName(packageName);
    }

    @NonNull
    public static LogMaker newLogMaker(int category, @NonNull ComponentName componentName,
            @NonNull String servicePackageName, int sessionId, boolean compatMode) {
        return newLogMaker(category, servicePackageName, sessionId, compatMode)
                .setComponentName(componentName);
    }

    public static void printlnRedactedText(@NonNull PrintWriter pw, @Nullable CharSequence text) {
        if (text == null) {
            pw.println("null");
        } else {
            pw.print(text.length()); pw.println("_chars");
        }
    }

    /**
     * Finds the {@link ViewNode} that has the requested {@code autofillId}, if any.
     */
    @Nullable
    public static ViewNode findViewNodeByAutofillId(@NonNull AssistStructure structure,
            @NonNull AutofillId autofillId) {
        return findViewNode(structure, (node) -> {
            return autofillId.equals(node.getAutofillId());
        });
    }

    private static ViewNode findViewNode(@NonNull AssistStructure structure,
            @NonNull ViewNodeFilter filter) {
        final ArrayDeque<ViewNode> nodesToProcess = new ArrayDeque<>();
        final int numWindowNodes = structure.getWindowNodeCount();
        for (int i = 0; i < numWindowNodes; i++) {
            nodesToProcess.add(structure.getWindowNodeAt(i).getRootViewNode());
        }
        while (!nodesToProcess.isEmpty()) {
            final ViewNode node = nodesToProcess.removeFirst();
            if (filter.matches(node)) {
                return node;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                nodesToProcess.addLast(node.getChildAt(i));
            }
        }

        return null;
    }

    /**
     * Sanitize the {@code webDomain} property of the URL bar node on compat mode.
     *
     * @param structure Assist structure
     * @param urlBarIds list of ids; only the first id found will be sanitized.
     *
     * @return the node containing the URL bar
     */
    @Nullable
    public static ViewNode sanitizeUrlBar(@NonNull AssistStructure structure,
            @NonNull String[] urlBarIds) {
        final ViewNode urlBarNode = findViewNode(structure, (node) -> {
            return ArrayUtils.contains(urlBarIds, node.getIdEntry());
        });
        if (urlBarNode != null) {
            final String domain = urlBarNode.getText().toString();
            if (domain.isEmpty()) {
                if (sDebug) Slog.d(TAG, "sanitizeUrlBar(): empty on " + urlBarNode.getIdEntry());
                return null;
            }
            urlBarNode.setWebDomain(domain);
            if (sDebug) {
                Slog.d(TAG, "sanitizeUrlBar(): id=" + urlBarNode.getIdEntry() + ", domain="
                        + urlBarNode.getWebDomain());
            }
        }
        return urlBarNode;
    }

    /**
     * Gets the value of a metric tag, or {@code 0} if not found or NaN.
     */
    static int getNumericValue(@NonNull LogMaker log, int tag) {
        final Object value = log.getTaggedData(tag);
        if (!(value instanceof Number)) {
            return 0;
        } else {
            return ((Number) value).intValue();
        }
    }

    /**
     * Gets the value of a device config property from the Autofill namespace.
     */
    static int getIntDeviceConfigProperty(@NonNull String key, int defaultValue) {
        final String value = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_AUTOFILL, key);
        if (value == null) return defaultValue;

        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            Log.w(TAG, "error parsing value (" + value + ") of property " + key + ": " + e);
            return defaultValue;
        }
    }

    private interface ViewNodeFilter {
        boolean matches(ViewNode node);
    }
}
