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

import static android.Manifest.permission.INTERACT_ACROSS_USERS;

import static com.android.server.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.ViewNode;
import android.app.assist.AssistStructure.WindowNode;
import android.content.ComponentName;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.metrics.LogMaker;
import android.os.UserHandle;
import android.os.UserManager;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.service.autofill.InternalSanitizer;
import android.service.autofill.SaveInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.ArrayUtils;
import com.android.server.utils.Slogf;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Helper {

    private static final String TAG = "AutofillHelper";

    // TODO(b/117779333): get rid of sDebug / sVerbose and always use the service variables instead

    /**
     * Defines a logging flag that can be dynamically changed at runtime using
     * {@code cmd autofill set log_level debug} or through
     * {@link android.provider.Settings.Global#AUTOFILL_LOGGING_LEVEL}.
     */
    public static boolean sDebug = true;

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

    private static boolean checkRemoteViewUriPermissions(
            @UserIdInt int userId, @NonNull RemoteViews rView) {
        final AtomicBoolean permissionsOk = new AtomicBoolean(true);

        rView.visitUris(uri -> {
            int uriOwnerId = android.content.ContentProvider.getUserIdFromUri(uri);
            boolean allowed = uriOwnerId == userId;
            permissionsOk.set(allowed & permissionsOk.get());
        });

        return permissionsOk.get();
    }

    /**
     * Creates the context as the foreground user
     *
     * <p>Returns the current context as the current foreground user
     */
    @RequiresPermission(INTERACT_ACROSS_USERS)
    public static Context getUserContext(Context context) {
        int userId = ActivityManager.getCurrentUser();
        Context c = context.createContextAsUser(UserHandle.of(userId), /* flags= */ 0);
        if (sDebug) {
            Slog.d(
                    TAG,
                    "Current User: "
                            + userId
                            + ", context created as: "
                            + c.getContentResolver().getUserId());
        }
        return c;
    }

    /**
     * Checks the URI permissions of the remote view,
     * to see if the current userId is able to access it.
     *
     * Returns the RemoteView that is passed if user is able, null otherwise.
     *
     * TODO: instead of returning a null remoteview when
     * the current userId cannot access an URI,
     * return a new RemoteView with the URI removed.
     */
    public static @Nullable RemoteViews sanitizeRemoteView(RemoteViews rView) {
        if (rView == null) return null;

        int userId = ActivityManager.getCurrentUser();

        boolean ok = checkRemoteViewUriPermissions(userId, rView);
        if (!ok) {
            Slog.w(TAG,
                    "sanitizeRemoteView() user: " + userId
                    + " tried accessing resource that does not belong to them");
        }
        return (ok ? rView : null);
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
        // Remove activity name from logging
        final ComponentName sanitizedComponentName =
                new ComponentName(componentName.getPackageName(), "");
        return newLogMaker(category, servicePackageName, sessionId, compatMode)
                .setComponentName(sanitizedComponentName);
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
     * Gets the {@link AutofillId} of the autofillable nodes in the {@code structure}.
     */
    @NonNull
    static ArrayList<AutofillId> getAutofillIds(@NonNull AssistStructure structure,
            boolean autofillableOnly) {
        final ArrayList<AutofillId> ids = new ArrayList<>();
        final int size = structure.getWindowNodeCount();
        for (int i = 0; i < size; i++) {
            final WindowNode node = structure.getWindowNodeAt(i);
            addAutofillableIds(node.getRootViewNode(), ids, autofillableOnly);
        }
        return ids;
    }

    private static void addAutofillableIds(@NonNull ViewNode node,
            @NonNull ArrayList<AutofillId> ids, boolean autofillableOnly) {
        if (!autofillableOnly || node.getAutofillType() != View.AUTOFILL_TYPE_NONE) {
            ids.add(node.getAutofillId());
        }
        final int size = node.getChildCount();
        for (int i = 0; i < size; i++) {
            final ViewNode child = node.getChildAt(i);
            addAutofillableIds(child, ids, autofillableOnly);
        }
    }

    @Nullable
    static ArrayMap<AutofillId, InternalSanitizer> createSanitizers(@Nullable SaveInfo saveInfo) {
        if (saveInfo == null) return null;

        final InternalSanitizer[] sanitizerKeys = saveInfo.getSanitizerKeys();
        if (sanitizerKeys == null) return null;

        final int size = sanitizerKeys.length;
        final ArrayMap<AutofillId, InternalSanitizer> sanitizers = new ArrayMap<>(size);
        if (sDebug) Slog.d(TAG, "Service provided " + size + " sanitizers");
        final AutofillId[][] sanitizerValues = saveInfo.getSanitizerValues();
        for (int i = 0; i < size; i++) {
            final InternalSanitizer sanitizer = sanitizerKeys[i];
            final AutofillId[] ids = sanitizerValues[i];
            if (sDebug) {
                Slog.d(TAG, "sanitizer #" + i + " (" + sanitizer + ") for ids "
                        + Arrays.toString(ids));
            }
            for (AutofillId id : ids) {
                sanitizers.put(id, sanitizer);
            }
        }
        return sanitizers;
    }

    /**
     * Returns true if {@code s1} contains all characters of {@code s2}, in order.
     */
    static boolean containsCharsInOrder(String s1, String s2) {
        int prevIndex = -1;
        for (char ch : s2.toCharArray()) {
            int index = TextUtils.indexOf(s1, ch, prevIndex + 1);
            if (index == -1) {
                return false;
            }
            prevIndex = index;
        }
        return true;
    }

    /**
     * Gets a context with the proper display id.
     *
     * <p>For most cases it will return the provided context, but on devices that
     * {@link UserManager#isVisibleBackgroundUsersEnabled() support visible background users}, it
     * will return a context with the display pased as parameter.
     */
    static Context getDisplayContext(Context context, int displayId) {
        if (!UserManager.isVisibleBackgroundUsersEnabled()) {
            return context;
        }
        if (context.getDisplayId() == displayId) {
            if (sDebug) {
                Slogf.d(TAG, "getDisplayContext(): context %s already has displayId %d", context,
                        displayId);
            }
            return context;
        }
        if (sDebug) {
            Slogf.d(TAG, "Creating context for display %d", displayId);
        }
        Display display = context.getSystemService(DisplayManager.class).getDisplay(displayId);
        if (display == null) {
            Slogf.wtf(TAG, "Could not get context with displayId %d, Autofill operations will "
                    + "probably fail)", displayId);
            return context;
        }

        return context.createDisplayContext(display);
    }

    static <T> @Nullable T weakDeref(WeakReference<T> weakRef, String tag, String prefix) {
        T deref = weakRef.get();
        if (deref == null) {
            Slog.wtf(tag, prefix + "fail to deref " + weakRef);
        }
        return deref;
    }

    private interface ViewNodeFilter {
        boolean matches(ViewNode node);
    }

    public static class SaveInfoStats {
        public int saveInfoCount;
        public int saveDataTypeCount;

        public SaveInfoStats(int saveInfoCount, int saveDataTypeCount) {
            this.saveInfoCount = saveInfoCount;
            this.saveDataTypeCount = saveDataTypeCount;
        }
    }

    /**
     * Get statistic information of save info given a sparse array of fill responses.
     *
     * Specifically the statistic includes
     *   1. how many save info the current session has.
     *   2. How many distinct save data types current session has.
     *
     * @return SaveInfoStats returns the above two number in a SaveInfoStats object
     */
    public static SaveInfoStats getSaveInfoStatsFromFillResponses(
            SparseArray<FillResponse> fillResponses) {
        if (fillResponses == null) {
            if (sVerbose) {
                Slog.v(TAG, "getSaveInfoStatsFromFillResponses(): fillResponse sparse array is "
                        + "null");
            }
            return new SaveInfoStats(-1, -1);
        }
        int numSaveInfos = 0;
        int numSaveDataTypes = 0;
        ArraySet<Integer> saveDataTypeSeen = new ArraySet<>();
        final int numResponses = fillResponses.size();
        for (int responseNum = 0; responseNum < numResponses; responseNum++) {
            final FillResponse response = fillResponses.valueAt(responseNum);
            if (response != null && response.getSaveInfo() != null) {
                numSaveInfos += 1;
                int saveDataType = response.getSaveInfo().getType();
                if (!saveDataTypeSeen.contains(saveDataType)) {
                    saveDataTypeSeen.add(saveDataType);
                    numSaveDataTypes += 1;
                }
            }
        }
        return new SaveInfoStats(numSaveInfos, numSaveDataTypes);
    }
}
