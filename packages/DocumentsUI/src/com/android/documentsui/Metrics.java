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

package com.android.documentsui;

import static com.android.documentsui.Shared.DEBUG;
import static com.android.internal.util.Preconditions.checkArgument;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import com.android.documentsui.model.DocumentInfo;
import com.android.documentsui.model.RootInfo;
import com.android.documentsui.services.FileOperationService;
import com.android.documentsui.services.FileOperationService.OpType;
import com.android.internal.logging.MetricsLogger;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;

/** @hide */
public final class Metrics {
    private static final String TAG = "Metrics";

    // These are the native provider authorities that the metrics code is capable of recognizing and
    // explicitly counting.
    private static final String AUTHORITY_MEDIA = "com.android.providers.media.documents";
    private static final String AUTHORITY_STORAGE = "com.android.externalstorage.documents";
    private static final String AUTHORITY_DOWNLOADS = "com.android.providers.downloads.documents";
    private static final String AUTHORITY_MTP = "com.android.mtp.documents";

    // These strings have to be whitelisted in tron. Do not change them.
    private static final String COUNT_LAUNCH_ACTION = "docsui_launch_action";
    private static final String COUNT_ROOT_VISITED = "docsui_root_visited";
    private static final String COUNT_OPEN_MIME = "docsui_open_mime";
    private static final String COUNT_CREATE_MIME = "docsui_create_mime";
    private static final String COUNT_GET_CONTENT_MIME = "docsui_get_content_mime";
    private static final String COUNT_BROWSE_ROOT = "docsui_browse_root";
    private static final String COUNT_MANAGE_ROOT = "docsui_manage_root";
    private static final String COUNT_MULTI_WINDOW = "docsui_multi_window";
    private static final String COUNT_FILEOP_SYSTEM = "docsui_fileop_system";
    private static final String COUNT_FILEOP_EXTERNAL = "docsui_fileop_external";
    private static final String COUNT_FILEOP_CANCELED = "docsui_fileop_canceled";

    // Indices for bucketing roots in the roots histogram. "Other" is the catch-all index for any
    // root that is not explicitly recognized by the Metrics code (see {@link
    // #getSanitizedRootIndex}). Apps are also bucketed in this histogram using negative indices
    // (see below).
    // Do not change or rearrange these values, that will break historical data. Only add to the end
    // of the list.
    private static final int ROOT_NONE = 0;
    private static final int ROOT_OTHER = 1;
    private static final int ROOT_AUDIO = 2;
    private static final int ROOT_DEVICE_STORAGE = 3;
    private static final int ROOT_DOWNLOADS = 4;
    private static final int ROOT_HOME = 5;
    private static final int ROOT_IMAGES = 6;
    private static final int ROOT_RECENTS = 7;
    private static final int ROOT_VIDEOS = 8;
    private static final int ROOT_MTP = 9;
    // Apps aren't really "roots", but they are treated as such in the roots fragment UI and so they
    // are logged analogously to roots. Use negative numbers to identify apps.
    private static final int ROOT_THIRD_PARTY_APP = -1;

    @IntDef(flag = true, value = {
            ROOT_NONE,
            ROOT_OTHER,
            ROOT_AUDIO,
            ROOT_DEVICE_STORAGE,
            ROOT_DOWNLOADS,
            ROOT_HOME,
            ROOT_IMAGES,
            ROOT_RECENTS,
            ROOT_VIDEOS,
            ROOT_MTP,
            ROOT_THIRD_PARTY_APP
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Root {}

    // Indices for bucketing mime types.
    private static final int MIME_OTHER = -2; // anything not enumerated below
    private static final int MIME_NONE = -1; // null mime
    private static final int MIME_ANY = 0; // */*
    private static final int MIME_APPLICATION = 1; // application/*
    private static final int MIME_AUDIO = 2; // audio/*
    private static final int MIME_IMAGE = 3; // image/*
    private static final int MIME_MESSAGE = 4; // message/*
    private static final int MIME_MULTIPART = 5; // multipart/*
    private static final int MIME_TEXT = 6; // text/*
    private static final int MIME_VIDEO = 7; // video/*

    @IntDef(flag = true, value = {
            MIME_OTHER,
            MIME_NONE,
            MIME_ANY,
            MIME_APPLICATION,
            MIME_AUDIO,
            MIME_IMAGE,
            MIME_MESSAGE,
            MIME_MULTIPART,
            MIME_TEXT,
            MIME_VIDEO
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Mime {}

    // Codes representing different kinds of file operations. These are used for bucketing
    // operations in the COUNT_FILEOP_{SYSTEM|EXTERNAL} histograms.
    private static final int FILEOP_OTHER = 0; // any file operation not listed below
    private static final int FILEOP_COPY_INTRA_PROVIDER = 1; // Copy within a provider
    private static final int FILEOP_COPY_SYSTEM_PROVIDER = 2; // Copy to a system provider.
    private static final int FILEOP_COPY_EXTERNAL_PROVIDER = 3; // Copy to a 3rd-party provider.
    private static final int FILEOP_MOVE_INTRA_PROVIDER = 4; // Move within a provider.
    private static final int FILEOP_MOVE_SYSTEM_PROVIDER = 5; // Move to a system provider.
    private static final int FILEOP_MOVE_EXTERNAL_PROVIDER = 6; // Move to a 3rd-party provider.
    private static final int FILEOP_DELETE = 7;
    private static final int FILEOP_OTHER_ERROR = -1;
    private static final int FILEOP_COPY_ERROR = -2;
    private static final int FILEOP_MOVE_ERROR = -3;
    private static final int FILEOP_DELETE_ERROR = -4;

    @IntDef(flag = true, value = {
            FILEOP_OTHER,
            FILEOP_COPY_INTRA_PROVIDER,
            FILEOP_COPY_SYSTEM_PROVIDER,
            FILEOP_COPY_EXTERNAL_PROVIDER,
            FILEOP_MOVE_INTRA_PROVIDER,
            FILEOP_MOVE_SYSTEM_PROVIDER,
            FILEOP_MOVE_EXTERNAL_PROVIDER,
            FILEOP_DELETE,
            FILEOP_OTHER_ERROR,
            FILEOP_COPY_ERROR,
            FILEOP_MOVE_ERROR,
            FILEOP_DELETE_ERROR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FileOp {}

    // Codes representing different provider types.  Used for sorting file operations when logging.
    private static final int PROVIDER_INTRA = 0;
    private static final int PROVIDER_SYSTEM = 1;
    private static final int PROVIDER_EXTERNAL = 2;

    @IntDef(flag = true, value = {
            PROVIDER_INTRA,
            PROVIDER_SYSTEM,
            PROVIDER_EXTERNAL
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Provider {}

    /**
     * Logs when DocumentsUI is started, and how. Call this when DocumentsUI first starts up.
     *
     * @param context
     * @param state
     * @param intent
     */
    public static void logActivityLaunch(Context context, State state, Intent intent) {
        // Log the launch action.
        logHistogram(context, COUNT_LAUNCH_ACTION, state.action);
        // Then log auxiliary data (roots/mime types) associated with some actions.
        Uri uri = intent.getData();
        switch (state.action) {
            case State.ACTION_OPEN:
                logHistogram(context, COUNT_OPEN_MIME, sanitizeMime(intent.getType()));
                break;
            case State.ACTION_CREATE:
                logHistogram(context, COUNT_CREATE_MIME, sanitizeMime(intent.getType()));
                break;
            case State.ACTION_GET_CONTENT:
                logHistogram(context, COUNT_GET_CONTENT_MIME, sanitizeMime(intent.getType()));
                break;
            case State.ACTION_MANAGE:
                logHistogram(context, COUNT_MANAGE_ROOT, sanitizeRoot(uri));
                break;
            case State.ACTION_BROWSE:
                logHistogram(context, COUNT_BROWSE_ROOT, sanitizeRoot(uri));
                break;
            default:
                break;
        }
    }

    /**
     * Logs a root visited event. Call this when the user clicks on a root in the RootsFragment.
     *
     * @param context
     * @param info
     */
    public static void logRootVisited(Context context, RootInfo info) {
        logHistogram(context, COUNT_ROOT_VISITED, sanitizeRoot(info));
    }

    /**
     * Logs an app visited event. Call this when the user clicks on an app in the RootsFragment.
     *
     * @param context
     * @param info
     */
    public static void logAppVisited(Context context, ResolveInfo info) {
        logHistogram(context, COUNT_ROOT_VISITED, sanitizeRoot(info));
    }

    /**
     * Logs a multi-window start. Call this when the user spawns a new DocumentsUI window.
     *
     * @param context
     */
    public static void logMultiWindow(Context context) {
        logCount(context, COUNT_MULTI_WINDOW);
    }

    /**
     * Logs file operation stats. Call this when a file operation has completed. The given
     * DocumentInfo is only used to distinguish broad categories of actions (e.g. copying from one
     * provider to another vs copying within a given provider).  No PII is logged.
     *
     * @param context
     * @param operationType
     * @param srcs
     * @param dst
     */
    public static void logFileOperation(
            Context context,
            @OpType int operationType,
            List<DocumentInfo> srcs,
            @Nullable DocumentInfo dst) {
        ProviderCounts counts = countProviders(srcs, dst);

        if (counts.intraProvider > 0) {
            logIntraProviderFileOps(context, dst.authority, operationType);
        }
        if (counts.systemProvider > 0) {
            // Log file operations on system providers.
            logInterProviderFileOps(context, COUNT_FILEOP_SYSTEM, dst, operationType);
        }
        if (counts.externalProvider > 0) {
            // Log file operations on external providers.
            logInterProviderFileOps(context, COUNT_FILEOP_EXTERNAL, dst, operationType);
        }
    }

    /**
     * Logs some kind of file operation error. Call this when a file operation (e.g. copy, delete)
     * fails.
     *
     * @param context
     * @param operationType
     * @param failedFiles
     */
    public static void logFileOperationErrors(Context context, @OpType int operationType,
            List<DocumentInfo> failedFiles) {
        ProviderCounts counts = countProviders(failedFiles, null);

        @FileOp int opCode = FILEOP_OTHER_ERROR;
        switch (operationType) {
            case FileOperationService.OPERATION_COPY:
                opCode = FILEOP_COPY_ERROR;
                break;
            case FileOperationService.OPERATION_DELETE:
                opCode = FILEOP_DELETE_ERROR;
                break;
            case FileOperationService.OPERATION_MOVE:
                opCode = FILEOP_MOVE_ERROR;
                break;
        }
        if (counts.systemProvider > 0) {
            logHistogram(context, COUNT_FILEOP_SYSTEM, opCode);
        }
        if (counts.externalProvider > 0) {
            logHistogram(context, COUNT_FILEOP_EXTERNAL, opCode);
        }
    }

    /**
     * Log the cancellation of a file operation.  Call this when a Job is canceled.
     * @param context
     * @param operationType
     */
    public static void logFileOperationCancelled(Context context, @OpType int operationType) {
        logHistogram(context, COUNT_FILEOP_CANCELED, operationType);
    }

    private static void logInterProviderFileOps(
            Context context,
            String histogram,
            DocumentInfo dst,
            @OpType int operationType) {
        if (operationType == FileOperationService.OPERATION_DELETE) {
            logHistogram(context, histogram, FILEOP_DELETE);
        } else {
            checkArgument(dst != null);
            @Provider int providerType =
                    isSystemProvider(dst.authority) ? PROVIDER_SYSTEM : PROVIDER_EXTERNAL;
            logHistogram(context, histogram, getOpCode(operationType, providerType));
        }
    }

    private static void logIntraProviderFileOps(
            Context context, String authority, @OpType int operationType) {
        // Find the right histogram to log to, then log the operation.
        String histogram = isSystemProvider(authority) ? COUNT_FILEOP_SYSTEM : COUNT_FILEOP_EXTERNAL;
        logHistogram(context, histogram, getOpCode(operationType, PROVIDER_INTRA));
    }

    /**
     * Internal method for making a MetricsLogger.count call. Increments the given counter by 1.
     *
     * @param context
     * @param name The counter to increment.
     */
    private static void logCount(Context context, String name) {
        if (DEBUG) Log.d(TAG, name + ": " + 1);
        MetricsLogger.count(context, name, 1);
    }

    /**
     * Internal method for making a MetricsLogger.histogram call.
     *
     * @param context
     * @param name The name of the histogram.
     * @param bucket The bucket to increment.
     */
    private static void logHistogram(Context context, String name, int bucket) {
        if (DEBUG) Log.d(TAG, name + ": " + bucket);
        MetricsLogger.histogram(context, name, bucket);
    }

    /**
     * Generates an integer identifying the given root. For privacy, this function only recognizes a
     * small set of hard-coded roots (ones provided by the system). Other roots are all grouped into
     * a single ROOT_OTHER bucket.
     */
    private static @Root int sanitizeRoot(Uri uri) {
        if (LauncherActivity.isLaunchUri(uri)) {
            return ROOT_NONE;
        }

        switch (uri.getAuthority()) {
            case AUTHORITY_MEDIA:
                switch (DocumentsContract.getRootId(uri)) {
                    case "audio_root":
                        return ROOT_AUDIO;
                    case "images_root":
                        return ROOT_IMAGES;
                    case "videos_root":
                        return ROOT_VIDEOS;
                    default:
                        return ROOT_OTHER;
                }
            case AUTHORITY_STORAGE:
                if ("home".equals(DocumentsContract.getRootId(uri))) {
                    return ROOT_HOME;
                } else {
                    return ROOT_DEVICE_STORAGE;
                }
            case AUTHORITY_DOWNLOADS:
                return ROOT_DOWNLOADS;
            case AUTHORITY_MTP:
                return ROOT_MTP;
            default:
                return ROOT_OTHER;
        }
    }

    /** @see #sanitizeRoot(Uri) */
    private static @Root int sanitizeRoot(RootInfo root) {
        if (root.isRecents()) {
            // Recents root is special and only identifiable via this method call. Other roots are
            // identified by URI.
            return ROOT_RECENTS;
        } else {
            return sanitizeRoot(root.getUri());
        }
    }

    /** @see #sanitizeRoot(Uri) */
    private static @Root int sanitizeRoot(ResolveInfo info) {
        // Log all apps under a single bucket in the roots histogram.
        return ROOT_THIRD_PARTY_APP;
    }

    /**
     * Generates an int identifying a mime type. For privacy, this function only recognizes a small
     * set of hard-coded types. For any other type, this function returns "other".
     *
     * @param mimeType
     * @return
     */
    private static @Mime int sanitizeMime(String mimeType) {
        if (mimeType == null) {
            return MIME_NONE;
        } else if ("*/*".equals(mimeType)) {
            return MIME_ANY;
        } else {
            String type = mimeType.substring(0, mimeType.indexOf('/'));
            switch (type) {
                case "application":
                    return MIME_APPLICATION;
                case "audio":
                    return MIME_AUDIO;
                case "image":
                    return MIME_IMAGE;
                case "message":
                    return MIME_MESSAGE;
                case "multipart":
                    return MIME_MULTIPART;
                case "text":
                    return MIME_TEXT;
                case "video":
                    return MIME_VIDEO;
            }
        }
        // Bucket all other types into one bucket.
        return MIME_OTHER;
    }

    private static boolean isSystemProvider(String authority) {
        switch (authority) {
            case AUTHORITY_MEDIA:
            case AUTHORITY_STORAGE:
            case AUTHORITY_DOWNLOADS:
                return true;
            default:
                return false;
        }
    }

    /**
     * @param operation
     * @param providerType
     * @return An opcode, suitable for use as histogram bucket, for the given operation/provider
     *         combination.
     */
    private static @FileOp int getOpCode(@OpType int operation, @Provider int providerType) {
        switch (operation) {
            case FileOperationService.OPERATION_COPY:
                switch (providerType) {
                    case PROVIDER_INTRA:
                        return FILEOP_COPY_INTRA_PROVIDER;
                    case PROVIDER_SYSTEM:
                        return FILEOP_COPY_SYSTEM_PROVIDER;
                    case PROVIDER_EXTERNAL:
                        return FILEOP_COPY_EXTERNAL_PROVIDER;
                }
            case FileOperationService.OPERATION_MOVE:
                switch (providerType) {
                    case PROVIDER_INTRA:
                        return FILEOP_MOVE_INTRA_PROVIDER;
                    case PROVIDER_SYSTEM:
                        return FILEOP_MOVE_SYSTEM_PROVIDER;
                    case PROVIDER_EXTERNAL:
                        return FILEOP_MOVE_EXTERNAL_PROVIDER;
                }
            case FileOperationService.OPERATION_DELETE:
                return FILEOP_DELETE;
            default:
                Log.w(TAG, "Unrecognized operation type when logging a file operation");
                return FILEOP_OTHER;
        }
    }

    /**
     * Count the given src documents and provide a tally of how many come from the same provider as
     * the dst document (if a dst is provided), how many come from system providers, and how many
     * come from external 3rd-party providers.
     */
    private static ProviderCounts countProviders(
            List<DocumentInfo> srcs, @Nullable DocumentInfo dst) {
        ProviderCounts counts = new ProviderCounts();
        for (DocumentInfo doc: srcs) {
            if (dst != null && doc.authority.equals(dst.authority)) {
                counts.intraProvider++;
            } else if (isSystemProvider(doc.authority)){
                counts.systemProvider++;
            } else {
                counts.externalProvider++;
            }
        }
        return counts;
    }

    private static class ProviderCounts {
        int intraProvider;
        int systemProvider;
        int externalProvider;
    }
}
