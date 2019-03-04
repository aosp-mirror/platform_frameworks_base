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

package android.util;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;

/**
 * DocumentsStatsLog provides APIs to send DocumentsUI related events to statsd.
 * @hide
 */
@SystemApi
public class DocumentsStatsLog {

    private DocumentsStatsLog() {}

    /**
     * Logs when DocumentsUI is started, and how. Call this when DocumentsUI first starts up.
     *
     * @param action action that launches DocumentsUI.
     * @param hasInitialUri is DocumentsUI launched with
     *                      {@link DocumentsContract#EXTRA_INITIAL_URI}.
     * @param mimeType the requested mime type.
     * @param rootUri the resolved rootUri, or {@code null} if the provider doesn't
     *                support {@link DocumentsProvider#findDocumentPath(String, String)}
     */
    public static void logActivityLaunch(
            int action, boolean hasInitialUri, int mimeType, int rootUri) {
        StatsLog.write(StatsLog.DOCS_UI_LAUNCH_REPORTED, action, hasInitialUri, mimeType, rootUri);
    }

    /**
     * Logs root visited event.
     *
     * @param scope whether it's in FILES or PICKER mode.
     * @param root the root that user visited
     */
    public static void logRootVisited(int scope, int root) {
        StatsLog.write(StatsLog.DOCS_UI_ROOT_VISITED, scope, root);
    }

    /**
     * Logs file operation stats. Call this when a file operation has completed.
     *
     * @param provider whether it's system or external provider
     * @param fileOp the file operation
     */
    public static void logFileOperation(int provider, int fileOp) {
        StatsLog.write(StatsLog.DOCS_UI_PROVIDER_FILE_OP, provider, fileOp);
    }

    /**
     * Logs file operation stats. Call this when a copy/move operation has completed with a specific
     * mode.
     *
     * @param fileOp copy or move file operation
     * @param mode the mode for copy and move operation
     */
    public static void logFileOperationCopyMoveMode(int fileOp, int mode) {
        StatsLog.write(StatsLog.DOCS_UI_FILE_OP_COPY_MOVE_MODE_REPORTED, fileOp, mode);
    }

    /**
     * Logs file sub operation stats. Call this when a file operation has failed.
     *
     * @param authority the authority of the source document
     * @param subOp the sub-file operation
     */
    public static void logFileOperationFailure(int authority, int subOp) {
        StatsLog.write(StatsLog.DOCS_UI_FILE_OP_FAILURE, authority, subOp);
    }

    /**
     * Logs the cancellation of a file operation. Call this when a job is canceled
     *
     * @param fileOp the file operation.
     */
    public static void logFileOperationCanceled(int fileOp) {
        StatsLog.write(StatsLog.DOCS_UI_FILE_OP_CANCELED, fileOp);
    }

    /**
     * Logs startup time in milliseconds.
     *
     * @param startupMs
     */
    public static void logStartupMs(int startupMs) {
        StatsLog.write(StatsLog.DOCS_UI_STARTUP_MS, startupMs);
    }

    /**
     * Logs the action that was started by user.
     *
     * @param userAction
     */
    public static void logUserAction(int userAction) {
        StatsLog.write(StatsLog.DOCS_UI_USER_ACTION_REPORTED, userAction);
    }

    /**
     * Logs the invalid type when invalid scoped access is requested.
     *
     * @param type the type of invalid scoped access request.
     */
    public static void logInvalidScopedAccessRequest(int type) {
        StatsLog.write(StatsLog.DOCS_UI_INVALID_SCOPED_ACCESS_REQUEST, type);
    }

    /**
     * Logs the package name that launches docsui picker mode.
     *
     * @param packageName
     */
    public static void logPickerLaunchedFrom(@Nullable String packageName) {
        StatsLog.write(StatsLog.DOCS_UI_PICKER_LAUNCHED_FROM_REPORTED, packageName);
    }

    /**
     * Logs the search type.
     *
     * @param searchType
     */
    public static void logSearchType(int searchType) {
        StatsLog.write(StatsLog.DOCS_UI_SEARCH_TYPE_REPORTED, searchType);
    }

    /**
     * Logs the search mode.
     *
     * @param searchMode
     */
    public static void logSearchMode(int searchMode) {
        StatsLog.write(StatsLog.DOCS_UI_SEARCH_MODE_REPORTED, searchMode);
    }

    /**
     * Logs the pick result information.
     *
     * @param actionCount total user action count during pick process.
     * @param duration total time spent on pick process.
     * @param fileCount number of picked files.
     * @param isSearching are the picked files found by search.
     * @param root the root where the picked files located.
     * @param mimeType the mime type of the picked file. Only for single-select case.
     * @param repeatedlyPickTimes number of times that the file has been picked before. Only for
     *                            single-select case.
     */
    public static void logFilePick(int actionCount, long duration, int fileCount,
            boolean isSearching, int root, int mimeType, int repeatedlyPickTimes) {
        StatsLog.write(StatsLog.DOCS_UI_PICK_RESULT_REPORTED, actionCount, duration, fileCount,
                isSearching, root, mimeType, repeatedlyPickTimes);
    }
}
