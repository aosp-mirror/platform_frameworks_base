/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.tv;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.media.tv.flags.Flags;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The contract between the TV provider and applications. Contains definitions for the supported
 * URIs and columns.
 * <h3>Overview</h3>
 *
 * <p>TvContract defines a basic database of TV content metadata such as channel and program
 * information. The information is stored in {@link Channels} and {@link Programs} tables.
 *
 * <ul>
 *     <li>A row in the {@link Channels} table represents information about a TV channel. The data
 *         format can vary greatly from standard to standard or according to service provider, thus
 *         the columns here are mostly comprised of basic entities that are usually seen to users
 *         regardless of standard such as channel number and name.</li>
 *     <li>A row in the {@link Programs} table represents a set of data describing a TV program such
 *         as program title and start time.</li>
 * </ul>
 */
public final class TvContract {
    /** The authority for the TV provider. */
    public static final String AUTHORITY = "android.media.tv";

    /**
     * Permission to read TV listings. This is required to read all the TV channel and program
     * information available on the system.
     * @hide
     */
    public static final String PERMISSION_READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";

    private static final String PATH_CHANNEL = "channel";
    private static final String PATH_PROGRAM = "program";
    private static final String PATH_RECORDED_PROGRAM = "recorded_program";
    private static final String PATH_PREVIEW_PROGRAM = "preview_program";
    private static final String PATH_WATCH_NEXT_PROGRAM = "watch_next_program";
    private static final String PATH_PASSTHROUGH = "passthrough";

    /**
     * Broadcast Action: sent when an application requests the system to make the given channel
     * browsable.  The operation is performed in the background without user interaction. This
     * is only relevant to channels with {@link Channels#TYPE_PREVIEW} type.
     *
     * <p>The intent must contain the following bundle parameters:
     * <ul>
     *     <li>{@link #EXTRA_CHANNEL_ID}: ID for the {@link Channels#TYPE_PREVIEW} channel as a long
     *     integer.</li>
     *     <li>{@link #EXTRA_PACKAGE_NAME}: the package name of the requesting application.</li>
     * </ul>
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_CHANNEL_BROWSABLE_REQUESTED =
            "android.media.tv.action.CHANNEL_BROWSABLE_REQUESTED";

    /**
     * Activity Action: sent by an application telling the system to make the given channel
     * browsable with user interaction. The system may show UI to ask user to approve the channel.
     * This is only relevant to channels with {@link Channels#TYPE_PREVIEW} type. Use
     * {@link Activity#startActivityForResult} to get the result of the request.
     *
     * <p>The intent must contain the following bundle parameters:
     * <ul>
     *     <li>{@link #EXTRA_CHANNEL_ID}: ID for the {@link Channels#TYPE_PREVIEW} channel as a long
     *     integer.</li>
     * </ul>
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_REQUEST_CHANNEL_BROWSABLE =
            "android.media.tv.action.REQUEST_CHANNEL_BROWSABLE";

    /**
     * Broadcast Action: sent by the system to tell the target TV input that one of its preview
     * program's browsable state is disabled, i.e., it will no longer be shown to users, which, for
     * example, might be a result of users' interaction with UI. The input is expected to delete the
     * preview program from the content provider.
     *
     * <p>The intent must contain the following bundle parameter:
     * <ul>
     *     <li>{@link #EXTRA_PREVIEW_PROGRAM_ID}: the disabled preview program ID.</li>
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED =
            "android.media.tv.action.PREVIEW_PROGRAM_BROWSABLE_DISABLED";

    /**
     * Broadcast Action: sent by the system to tell the target TV input that one of its "watch next"
     * program's browsable state is disabled, i.e., it will no longer be shown to users, which, for
     * example, might be a result of users' interaction with UI. The input is expected to delete the
     * "watch next" program from the content provider.
     *
     * <p>The intent must contain the following bundle parameter:
     * <ul>
     *     <li>{@link #EXTRA_WATCH_NEXT_PROGRAM_ID}: the disabled "watch next" program ID.</li>
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED =
            "android.media.tv.action.WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED";

    /**
     * Broadcast Action: sent by the system to tell the target TV input that one of its existing
     * preview programs is added to the watch next programs table by user.
     *
     * <p>The intent must contain the following bundle parameters:
     * <ul>
     *     <li>{@link #EXTRA_PREVIEW_PROGRAM_ID}: the ID of the existing preview program.</li>
     *     <li>{@link #EXTRA_WATCH_NEXT_PROGRAM_ID}: the ID of the new watch next program.</li>
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT =
            "android.media.tv.action.PREVIEW_PROGRAM_ADDED_TO_WATCH_NEXT";

    /**
     * Broadcast Action: sent to the target TV input after it is first installed to notify the input
     * to initialize its channels and programs to the system content provider.
     *
     * <p>Note that this intent is sent only on devices with
     * {@link android.content.pm.PackageManager#FEATURE_LEANBACK} enabled. Besides that, in order
     * to receive this intent, the target TV input must:
     * <ul>
     *     <li>Declare a broadcast receiver for this intent in its
     *         <code>AndroidManifest.xml</code>.</li>
     *     <li>Declare appropriate permissions to write channel and program data in its
     *         <code>AndroidManifest.xml</code>.</li>
     * </ul>
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_INITIALIZE_PROGRAMS =
            "android.media.tv.action.INITIALIZE_PROGRAMS";

    /**
     * The key for a bundle parameter containing a channel ID as a long integer
     */
    public static final String EXTRA_CHANNEL_ID = "android.media.tv.extra.CHANNEL_ID";

    /**
     * The key for a bundle parameter containing a package name as a string.
     * @hide
     */
    @SystemApi
    public static final String EXTRA_PACKAGE_NAME = "android.media.tv.extra.PACKAGE_NAME";

    /** The key for a bundle parameter containing a program ID as a long integer. */
    public static final String EXTRA_PREVIEW_PROGRAM_ID =
            "android.media.tv.extra.PREVIEW_PROGRAM_ID";

    /** The key for a bundle parameter containing a watch next program ID as a long integer. */
    public static final String EXTRA_WATCH_NEXT_PROGRAM_ID =
            "android.media.tv.extra.WATCH_NEXT_PROGRAM_ID";

    /**
     * The key for a bundle parameter containing the result code of a method call as an integer.
     *
     * @see #RESULT_OK
     * @see #RESULT_ERROR_IO
     * @see #RESULT_ERROR_INVALID_ARGUMENT
     * @hide
     */
    @SystemApi
    public static final String EXTRA_RESULT_CODE = "android.media.tv.extra.RESULT_CODE";

    /**
     * The result code for a successful execution without error.
     * @hide
     */
    @SystemApi
    public static final int RESULT_OK = 0;

    /**
     * The result code for a failure from I/O operation.
     * @hide
     */
    @SystemApi
    public static final int RESULT_ERROR_IO = 1;

    /**
     * The result code for a failure from invalid argument.
     * @hide
     */
    @SystemApi
    public static final int RESULT_ERROR_INVALID_ARGUMENT = 2;

    /**
     * The method name to get existing columns in the given table of the specified content provider.
     *
     * <p>The method caller must provide the following parameter:
     * <ul>
     *     <li>{@code arg}: The content URI of the target table as a {@link String}.</li>
     * </ul>

     * <p>On success, the returned {@link android.os.Bundle} will include existing column names
     * with the key {@link #EXTRA_EXISTING_COLUMN_NAMES}. Otherwise, the return value will be {@code null}.
     *
     * @see ContentResolver#call(Uri, String, String, Bundle)
     * @see #EXTRA_EXISTING_COLUMN_NAMES
     * @hide
     */
    @SystemApi
    public static final String METHOD_GET_COLUMNS = "get_columns";

    /**
     * The method name to add a new column in the given table of the specified content provider.
     *
     * <p>The method caller must provide the following parameter:
     * <ul>
     *     <li>{@code arg}: The content URI of the target table as a {@link String}.</li>
     *     <li>{@code extra}: Name, data type, and default value of the new column in a Bundle:
     *         <ul>
     *             <li>{@link #EXTRA_COLUMN_NAME} the column name as a {@link String}.</li>
     *             <li>{@link #EXTRA_DATA_TYPE} the data type as a {@link String}.</li>
     *             <li>{@link #EXTRA_DEFAULT_VALUE} the default value as a {@link String}.
     *                 (optional)</li>
     *         </ul>
     *     </li>
     * </ul>
     *
     * <p>On success, the returned {@link android.os.Bundle} will include current colum names after
     * the addition operation with the key {@link #EXTRA_EXISTING_COLUMN_NAMES}. Otherwise, the
     * return value will be {@code null}.
     *
     * @see ContentResolver#call(Uri, String, String, Bundle)
     * @see #EXTRA_COLUMN_NAME
     * @see #EXTRA_DATA_TYPE
     * @see #EXTRA_DEFAULT_VALUE
     * @see #EXTRA_EXISTING_COLUMN_NAMES
     * @hide
     */
    @SystemApi
    public static final String METHOD_ADD_COLUMN = "add_column";

    /**
     * The method name to get all the blocked packages. When a package is blocked, all the data for
     * preview programs/channels and watch next programs belonging to this package in the content
     * provider will be cleared. Once a package is blocked, {@link SecurityException} will be thrown
     * for all the requests to preview programs/channels and watch next programs via
     * {@link android.content.ContentProvider} from it.
     *
     * <p>The returned {@link android.os.Bundle} will include all the blocked package names with the
     * key {@link #EXTRA_BLOCKED_PACKAGES}.
     *
     * @see ContentResolver#call(Uri, String, String, Bundle)
     * @see #EXTRA_BLOCKED_PACKAGES
     * @see #METHOD_BLOCK_PACKAGE
     * @see #METHOD_UNBLOCK_PACKAGE
     * @hide
     */
    @SystemApi
    public static final String METHOD_GET_BLOCKED_PACKAGES = "get_blocked_packages";

    /**
     * The method name to block the access from the given package. When a package is blocked, all
     * the data for preview programs/channels and watch next programs belonging to this package in
     * the content provider will be cleared. Once a package is blocked, {@link SecurityException}
     * will be thrown for all the requests to preview programs/channels and watch next programs via
     * {@link android.content.ContentProvider} from it.
     *
     * <p>The method caller must provide the following parameter:
     * <ul>
     *     <li>{@code arg}: The package name to be added as blocked package {@link String}.</li>
     * </ul>
     *
     * <p>The returned {@link android.os.Bundle} will include an integer code denoting whether the
     * execution is successful or not with the key {@link #EXTRA_RESULT_CODE}. If {@code arg} is
     * empty, the result code will be {@link #RESULT_ERROR_INVALID_ARGUMENT}. If success, the result
     * code will be {@link #RESULT_OK}. Otherwise, the result code will be {@link #RESULT_ERROR_IO}.
     *
     * @see ContentResolver#call(Uri, String, String, Bundle)
     * @see #EXTRA_RESULT_CODE
     * @see #METHOD_GET_BLOCKED_PACKAGES
     * @see #METHOD_UNBLOCK_PACKAGE
     * @hide
     */
    @SystemApi
    public static final String METHOD_BLOCK_PACKAGE = "block_package";

    /**
     * The method name to unblock the access from the given package. When a package is blocked, all
     * the data for preview programs/channels and watch next programs belonging to this package in
     * the content provider will be cleared. Once a package is blocked, {@link SecurityException}
     * will be thrown for all the requests to preview programs/channels and watch next programs via
     * {@link android.content.ContentProvider} from it.
     *
     * <p>The method caller must provide the following parameter:
     * <ul>
     *     <li>{@code arg}: The package name to be removed from blocked list as a {@link String}.
     *     </li>
     * </ul>
     *
     * <p>The returned {@link android.os.Bundle} will include an integer code denoting whether the
     * execution is successful or not with the key {@link #EXTRA_RESULT_CODE}. If {@code arg} is
     * empty, the result code will be {@link #RESULT_ERROR_INVALID_ARGUMENT}. If success, the result
     * code will be {@link #RESULT_OK}. Otherwise, the result code will be {@link #RESULT_ERROR_IO}.
     *
     * @see ContentResolver#call(Uri, String, String, Bundle)
     * @see #EXTRA_RESULT_CODE
     * @see #METHOD_GET_BLOCKED_PACKAGES
     * @see #METHOD_BLOCK_PACKAGE
     * @hide
     */
    @SystemApi
    public static final String METHOD_UNBLOCK_PACKAGE = "unblock_package";

    /**
     * The key for a returned {@link Bundle} value containing existing column names in the given
     * table as an {@link ArrayList} of {@link String}.
     *
     * @see #METHOD_GET_COLUMNS
     * @see #METHOD_ADD_COLUMN
     * @hide
     */
    @SystemApi
    public static final String EXTRA_EXISTING_COLUMN_NAMES =
            "android.media.tv.extra.EXISTING_COLUMN_NAMES";

    /**
     * The key for a {@link Bundle} parameter containing the new column name to be added in the
     * given table as a non-empty {@link CharSequence}.
     *
     * @see #METHOD_ADD_COLUMN
     * @hide
     */
    @SystemApi
    public static final String EXTRA_COLUMN_NAME = "android.media.tv.extra.COLUMN_NAME";

    /**
     * The key for a {@link Bundle} parameter containing the data type of the new column to be added
     * in the given table as a non-empty {@link CharSequence}, which should be one of the following
     * values: {@code "TEXT"}, {@code "INTEGER"}, {@code "REAL"}, or {@code "BLOB"}.
     *
     * @see #METHOD_ADD_COLUMN
     * @hide
     */
    @SystemApi
    public static final String EXTRA_DATA_TYPE = "android.media.tv.extra.DATA_TYPE";

    /**
     * The key for a {@link Bundle} parameter containing the default value of the new column to be
     * added in the given table as a {@link CharSequence}, which represents a valid default value
     * according to the data type provided with {@link #EXTRA_DATA_TYPE}.
     *
     * @see #METHOD_ADD_COLUMN
     * @hide
     */
    @SystemApi
    public static final String EXTRA_DEFAULT_VALUE = "android.media.tv.extra.DEFAULT_VALUE";

    /**
     * The key for a returned {@link Bundle} value containing all the blocked package names as an
     * {@link ArrayList} of {@link String}.
     *
     * @see #METHOD_GET_BLOCKED_PACKAGES
     * @hide
     */
    @SystemApi
    public static final String EXTRA_BLOCKED_PACKAGES = "android.media.tv.extra.BLOCKED_PACKAGES";

    /**
     * An optional query, update or delete URI parameter that allows the caller to specify TV input
     * ID to filter channels.
     * @hide
     */
    public static final String PARAM_INPUT = "input";

    /**
     * An optional query, update or delete URI parameter that allows the caller to specify channel
     * ID to filter programs.
     * @hide
     */
    public static final String PARAM_CHANNEL = "channel";

    /**
     * An optional query, update or delete URI parameter that allows the caller to specify start
     * time (in milliseconds since the epoch) to filter programs.
     * @hide
     */
    public static final String PARAM_START_TIME = "start_time";

    /**
     * An optional query, update or delete URI parameter that allows the caller to specify end time
     * (in milliseconds since the epoch) to filter programs.
     * @hide
     */
    public static final String PARAM_END_TIME = "end_time";

    /**
     * A query, update or delete URI parameter that allows the caller to operate on all or
     * browsable-only channels. If set to "true", the rows that contain non-browsable channels are
     * not affected.
     * @hide
     */
    public static final String PARAM_BROWSABLE_ONLY = "browsable_only";

    /**
     * An optional query, update or delete URI parameter that allows the caller to specify canonical
     * genre to filter programs.
     * @hide
     */
    public static final String PARAM_CANONICAL_GENRE = "canonical_genre";

    /**
     * A query, update or delete URI parameter that allows the caller to operate only on preview or
     * non-preview channels. If set to "true", the operation affects the rows for preview channels
     * only. If set to "false", the operation affects the rows for non-preview channels only.
     * @hide
     */
    public static final String PARAM_PREVIEW = "preview";

    /**
     * An optional query, update or delete URI parameter that allows the caller to specify package
     * name to filter channels.
     * @hide
     */
    public static final String PARAM_PACKAGE = "package";

    /**
     * Builds an ID that uniquely identifies a TV input service.
     *
     * @param name The {@link ComponentName} of the TV input service to build ID for.
     * @return the ID for the given TV input service.
     */
    public static String buildInputId(ComponentName name) {
        return name.flattenToShortString();
    }

    /**
     * Builds a URI that points to a specific channel.
     *
     * @param channelId The ID of the channel to point to.
     */
    public static Uri buildChannelUri(long channelId) {
        return ContentUris.withAppendedId(Channels.CONTENT_URI, channelId);
    }

    /**
     * Build a special channel URI intended to be used with pass-through inputs. (e.g. HDMI)
     *
     * @param inputId The ID of the pass-through input to build a channels URI for.
     * @see TvInputInfo#isPassthroughInput()
     */
    public static Uri buildChannelUriForPassthroughInput(String inputId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY)
                .appendPath(PATH_PASSTHROUGH).appendPath(inputId).build();
    }

    /**
     * Builds a URI that points to a channel logo. See {@link Channels.Logo}.
     *
     * @param channelId The ID of the channel whose logo is pointed to.
     */
    public static Uri buildChannelLogoUri(long channelId) {
        return buildChannelLogoUri(buildChannelUri(channelId));
    }

    /**
     * Builds a URI that points to a channel logo. See {@link Channels.Logo}.
     *
     * @param channelUri The URI of the channel whose logo is pointed to.
     */
    public static Uri buildChannelLogoUri(Uri channelUri) {
        if (!isChannelUriForTunerInput(channelUri)) {
            throw new IllegalArgumentException("Not a channel: " + channelUri);
        }
        return Uri.withAppendedPath(channelUri, Channels.Logo.CONTENT_DIRECTORY);
    }

    /**
     * Builds a URI that points to all channels from a given TV input.
     *
     * @param inputId The ID of the TV input to build a channels URI for. If {@code null}, builds a
     *            URI for all the TV inputs.
     */
    public static Uri buildChannelsUriForInput(@Nullable String inputId) {
        return buildChannelsUriForInput(inputId, false);
    }

    /**
     * Builds a URI that points to all or browsable-only channels from a given TV input.
     *
     * @param inputId The ID of the TV input to build a channels URI for. If {@code null}, builds a
     *            URI for all the TV inputs.
     * @param browsableOnly If set to {@code true} the URI points to only browsable channels. If set
     *            to {@code false} the URI points to all channels regardless of whether they are
     *            browsable or not.
     * @hide
     */
    @SystemApi
    public static Uri buildChannelsUriForInput(@Nullable String inputId,
            boolean browsableOnly) {
        Uri.Builder builder = Channels.CONTENT_URI.buildUpon();
        if (inputId != null) {
            builder.appendQueryParameter(PARAM_INPUT, inputId);
        }
        return builder.appendQueryParameter(PARAM_BROWSABLE_ONLY, String.valueOf(browsableOnly))
                .build();
    }

    /**
     * Builds a URI that points to all or browsable-only channels which have programs with the given
     * genre from the given TV input.
     *
     * @param inputId The ID of the TV input to build a channels URI for. If {@code null}, builds a
     *            URI for all the TV inputs.
     * @param genre {@link Programs.Genres} to search. If {@code null}, builds a URI for all genres.
     * @param browsableOnly If set to {@code true} the URI points to only browsable channels. If set
     *            to {@code false} the URI points to all channels regardless of whether they are
     *            browsable or not.
     * @hide
     */
    @SystemApi
    public static Uri buildChannelsUriForInput(@Nullable String inputId,
            @Nullable String genre, boolean browsableOnly) {
        if (genre == null) {
            return buildChannelsUriForInput(inputId, browsableOnly);
        }
        if (!Programs.Genres.isCanonical(genre)) {
            throw new IllegalArgumentException("Not a canonical genre: '" + genre + "'");
        }
        return buildChannelsUriForInput(inputId, browsableOnly).buildUpon()
                .appendQueryParameter(PARAM_CANONICAL_GENRE, genre).build();
    }

    /**
     * Builds a URI that points to a specific program.
     *
     * @param programId The ID of the program to point to.
     */
    public static Uri buildProgramUri(long programId) {
        return ContentUris.withAppendedId(Programs.CONTENT_URI, programId);
    }

    /**
     * Builds a URI that points to all programs on a given channel.
     *
     * @param channelId The ID of the channel to return programs for.
     */
    public static Uri buildProgramsUriForChannel(long channelId) {
        return Programs.CONTENT_URI.buildUpon()
                .appendQueryParameter(PARAM_CHANNEL, String.valueOf(channelId)).build();
    }

    /**
     * Builds a URI that points to all programs on a given channel.
     *
     * @param channelUri The URI of the channel to return programs for.
     */
    public static Uri buildProgramsUriForChannel(Uri channelUri) {
        if (!isChannelUriForTunerInput(channelUri)) {
            throw new IllegalArgumentException("Not a channel: " + channelUri);
        }
        return buildProgramsUriForChannel(ContentUris.parseId(channelUri));
    }

    /**
     * Builds a URI that points to programs on a specific channel whose schedules overlap with the
     * given time frame.
     *
     * @param channelId The ID of the channel to return programs for.
     * @param startTime The start time used to filter programs. The returned programs will have a
     *            {@link Programs#COLUMN_END_TIME_UTC_MILLIS} that is greater than or equal to
                  {@code startTime}.
     * @param endTime The end time used to filter programs. The returned programs will have
     *            {@link Programs#COLUMN_START_TIME_UTC_MILLIS} that is less than or equal to
     *            {@code endTime}.
     */
    public static Uri buildProgramsUriForChannel(long channelId, long startTime,
            long endTime) {
        Uri uri = buildProgramsUriForChannel(channelId);
        return uri.buildUpon().appendQueryParameter(PARAM_START_TIME, String.valueOf(startTime))
                .appendQueryParameter(PARAM_END_TIME, String.valueOf(endTime)).build();
    }

    /**
     * Builds a URI that points to programs on a specific channel whose schedules overlap with the
     * given time frame.
     *
     * @param channelUri The URI of the channel to return programs for.
     * @param startTime The start time used to filter programs. The returned programs should have
     *            {@link Programs#COLUMN_END_TIME_UTC_MILLIS} that is greater than this time.
     * @param endTime The end time used to filter programs. The returned programs should have
     *            {@link Programs#COLUMN_START_TIME_UTC_MILLIS} that is less than this time.
     */
    public static Uri buildProgramsUriForChannel(Uri channelUri, long startTime,
            long endTime) {
        if (!isChannelUriForTunerInput(channelUri)) {
            throw new IllegalArgumentException("Not a channel: " + channelUri);
        }
        return buildProgramsUriForChannel(ContentUris.parseId(channelUri), startTime, endTime);
    }

    /**
     * Builds a URI that points to a specific recorded program.
     *
     * @param recordedProgramId The ID of the recorded program to point to.
     */
    public static Uri buildRecordedProgramUri(long recordedProgramId) {
        return ContentUris.withAppendedId(RecordedPrograms.CONTENT_URI, recordedProgramId);
    }

    /**
     * Builds a URI that points to a specific preview program.
     *
     * @param previewProgramId The ID of the preview program to point to.
     */
    public static Uri buildPreviewProgramUri(long previewProgramId) {
        return ContentUris.withAppendedId(PreviewPrograms.CONTENT_URI, previewProgramId);
    }

    /**
     * Builds a URI that points to all preview programs on a given channel.
     *
     * @param channelId The ID of the channel to return preview programs for.
     */
    public static Uri buildPreviewProgramsUriForChannel(long channelId) {
        return PreviewPrograms.CONTENT_URI.buildUpon()
                .appendQueryParameter(PARAM_CHANNEL, String.valueOf(channelId)).build();
    }

    /**
     * Builds a URI that points to all preview programs on a given channel.
     *
     * @param channelUri The URI of the channel to return preview programs for.
     */
    public static Uri buildPreviewProgramsUriForChannel(Uri channelUri) {
        if (!isChannelUriForTunerInput(channelUri)) {
            throw new IllegalArgumentException("Not a channel: " + channelUri);
        }
        return buildPreviewProgramsUriForChannel(ContentUris.parseId(channelUri));
    }

    /**
     * Builds a URI that points to a specific watch next program.
     *
     * @param watchNextProgramId The ID of the watch next program to point to.
     */
    public static Uri buildWatchNextProgramUri(long watchNextProgramId) {
        return ContentUris.withAppendedId(WatchNextPrograms.CONTENT_URI, watchNextProgramId);
    }

    /**
     * Builds a URI that points to a specific program the user watched.
     *
     * @param watchedProgramId The ID of the watched program to point to.
     * @hide
     */
    public static Uri buildWatchedProgramUri(long watchedProgramId) {
        return ContentUris.withAppendedId(WatchedPrograms.CONTENT_URI, watchedProgramId);
    }

    private static boolean isTvUri(Uri uri) {
        return uri != null && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                && AUTHORITY.equals(uri.getAuthority());
    }

    private static boolean isTwoSegmentUriStartingWith(Uri uri, String pathSegment) {
        List<String> pathSegments = uri.getPathSegments();
        return pathSegments.size() == 2 && pathSegment.equals(pathSegments.get(0));
    }

    /**
     * @return {@code true} if {@code uri} is a channel URI.
     */
    public static boolean isChannelUri(@NonNull Uri uri) {
        return isChannelUriForTunerInput(uri) || isChannelUriForPassthroughInput(uri);
    }

    /**
     * @return {@code true} if {@code uri} is a channel URI for a tuner input.
     */
    public static boolean isChannelUriForTunerInput(@NonNull Uri uri) {
        return isTvUri(uri) && isTwoSegmentUriStartingWith(uri, PATH_CHANNEL);
    }

    /**
     * @return {@code true} if {@code uri} is a channel URI for a pass-through input.
     */
    public static boolean isChannelUriForPassthroughInput(@NonNull Uri uri) {
        return isTvUri(uri) && isTwoSegmentUriStartingWith(uri, PATH_PASSTHROUGH);
    }

    /**
     * @return {@code true} if {@code uri} is a program URI.
     */
    public static boolean isProgramUri(@NonNull Uri uri) {
        return isTvUri(uri) && isTwoSegmentUriStartingWith(uri, PATH_PROGRAM);
    }

    /**
     * @return {@code true} if {@code uri} is a recorded program URI.
     */
    public static boolean isRecordedProgramUri(@NonNull Uri uri) {
        return isTvUri(uri) && isTwoSegmentUriStartingWith(uri, PATH_RECORDED_PROGRAM);
    }

    /**
     * Requests to make a channel browsable.
     *
     * <p>Once called, the system will review the request and make the channel browsable based on
     * its policy. The first request from a package is guaranteed to be approved. This is only
     * relevant to channels with {@link Channels#TYPE_PREVIEW} type.
     *
     * @param context The context for accessing content provider.
     * @param channelId The channel ID to be browsable.
     * @see Channels#COLUMN_BROWSABLE
     */
    public static void requestChannelBrowsable(Context context, long channelId) {
        TvInputManager manager = (TvInputManager) context.getSystemService(
            Context.TV_INPUT_SERVICE);
        if (manager != null) {
            manager.requestChannelBrowsable(buildChannelUri(channelId));
        }
    }

    private TvContract() {}

    /**
     * Common base for the tables of TV channels/programs.
     */
    public interface BaseTvColumns extends BaseColumns {
        /**
         * The name of the package that owns the current row.
         *
         * <p>The TV provider fills in this column with the name of the package that provides the
         * initial data of the row. If the package is later uninstalled, the rows it owns are
         * automatically removed from the tables.
         *
         * <p>Type: TEXT
         */
        String COLUMN_PACKAGE_NAME = "package_name";
    }

    /**
     * Common columns for the tables of TV programs.
     * @hide
     */
    interface ProgramColumns {
        /** @hide */
        @IntDef({
                REVIEW_RATING_STYLE_STARS,
                REVIEW_RATING_STYLE_THUMBS_UP_DOWN,
                REVIEW_RATING_STYLE_PERCENTAGE,
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface ReviewRatingStyle {}

        /**
         * The review rating style for five star rating.
         *
         * @see #COLUMN_REVIEW_RATING_STYLE
         */
        int REVIEW_RATING_STYLE_STARS = 0;

        /**
         * The review rating style for thumbs-up and thumbs-down rating.
         *
         * @see #COLUMN_REVIEW_RATING_STYLE
         */
        int REVIEW_RATING_STYLE_THUMBS_UP_DOWN = 1;

        /**
         * The review rating style for 0 to 100 point system.
         *
         * @see #COLUMN_REVIEW_RATING_STYLE
         */
        int REVIEW_RATING_STYLE_PERCENTAGE = 2;

        /**
         * The title of this TV program.
         *
         * <p>If this program is an episodic TV show, it is recommended that the title is the series
         * title and its related fields ({@link #COLUMN_SEASON_TITLE} and/or
         * {@link #COLUMN_SEASON_DISPLAY_NUMBER}, {@link #COLUMN_SEASON_DISPLAY_NUMBER},
         * {@link #COLUMN_EPISODE_DISPLAY_NUMBER}, and {@link #COLUMN_EPISODE_TITLE}) are filled in.
         *
         * <p>Type: TEXT
         */
        String COLUMN_TITLE = "title";

        /**
         * The season display number of this TV program for episodic TV shows.
         *
         * <p>This is used to indicate the season number. (e.g. 1, 2 or 3) Note that the value
         * does not necessarily be numeric. (e.g. 12B)
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        String COLUMN_SEASON_DISPLAY_NUMBER = "season_display_number";

        /**
         * The title of the season for this TV program for episodic TV shows.
         *
         * <p>This is an optional field supplied only when the season has a special title
         * (e.g. The Final Season). If provided, the applications should display it instead of
         * {@link #COLUMN_SEASON_DISPLAY_NUMBER}, and should display it without alterations.
         * (e.g. for "The Final Season", displayed string should be "The Final Season", not
         * "Season The Final Season"). When displaying multiple programs, the order should be based
         * on {@link #COLUMN_SEASON_DISPLAY_NUMBER}, even when {@link #COLUMN_SEASON_TITLE} exists.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        String COLUMN_SEASON_TITLE = "season_title";

        /**
         * The episode display number of this TV program for episodic TV shows.
         *
         * <p>This is used to indicate the episode number. (e.g. 1, 2 or 3) Note that the value
         * does not necessarily be numeric. (e.g. 12B)
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        String COLUMN_EPISODE_DISPLAY_NUMBER = "episode_display_number";

        /**
         * The episode title of this TV program for episodic TV shows.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        String COLUMN_EPISODE_TITLE = "episode_title";

        /**
         * The comma-separated canonical genre string of this TV program.
         *
         * <p>Canonical genres are defined in {@link Genres}. Use {@link Genres#encode} to create a
         * text that can be stored in this column. Use {@link Genres#decode} to get the canonical
         * genre strings from the text stored in the column.
         *
         * <p>Type: TEXT
         * @see Genres
         * @see Genres#encode
         * @see Genres#decode
         */
        String COLUMN_CANONICAL_GENRE = "canonical_genre";

        /**
         * The short description of this TV program that is displayed to the user by default.
         *
         * <p>It is recommended to limit the length of the descriptions to 256 characters.
         *
         * <p>Type: TEXT
         */
        String COLUMN_SHORT_DESCRIPTION = "short_description";

        /**
         * The detailed, lengthy description of this TV program that is displayed only when the user
         * wants to see more information.
         *
         * <p>TV input services should leave this field empty if they have no additional details
         * beyond {@link #COLUMN_SHORT_DESCRIPTION}.
         *
         * <p>Type: TEXT
         */
        String COLUMN_LONG_DESCRIPTION = "long_description";

        /**
         * The width of the video for this TV program, in the unit of pixels.
         *
         * <p>Together with {@link #COLUMN_VIDEO_HEIGHT} this is used to determine the video
         * resolution of the current TV program. Can be empty if it is not known initially or the
         * program does not convey any video such as the programs from type
         * {@link Channels#SERVICE_TYPE_AUDIO} channels.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_VIDEO_WIDTH = "video_width";

        /**
         * The height of the video for this TV program, in the unit of pixels.
         *
         * <p>Together with {@link #COLUMN_VIDEO_WIDTH} this is used to determine the video
         * resolution of the current TV program. Can be empty if it is not known initially or the
         * program does not convey any video such as the programs from type
         * {@link Channels#SERVICE_TYPE_AUDIO} channels.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_VIDEO_HEIGHT = "video_height";

        /**
         * The comma-separated audio languages of this TV program.
         *
         * <p>This is used to describe available audio languages included in the program. Use either
         * ISO 639-1 or 639-2/T codes.
         *
         * <p>Type: TEXT
         */
        String COLUMN_AUDIO_LANGUAGE = "audio_language";

        /**
         * The comma-separated content ratings of this TV program.
         *
         * <p>This is used to describe the content rating(s) of this program. Each comma-separated
         * content rating sub-string should be generated by calling
         * {@link TvContentRating#flattenToString}. Note that in most cases the program content is
         * rated by a single rating system, thus resulting in a corresponding single sub-string that
         * does not require comma separation and multiple sub-strings appear only when the program
         * content is rated by two or more content rating systems. If any of those ratings is
         * specified as "blocked rating" in the user's parental control settings, the TV input
         * service should block the current content and wait for the signal that it is okay to
         * unblock.
         *
         * <p>Type: TEXT
         */
        String COLUMN_CONTENT_RATING = "content_rating";

        /**
         * The URI for the poster art of this TV program.
         *
         * <p>The data in the column must be a URL, or a URI in one of the following formats:
         *
         * <ul>
         * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
         * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})
         * </li>
         * <li>file ({@link android.content.ContentResolver#SCHEME_FILE})</li>
         * </ul>
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        String COLUMN_POSTER_ART_URI = "poster_art_uri";

        /**
         * The URI for the thumbnail of this TV program.
         *
         * <p>The system can generate a thumbnail from the poster art if this column is not
         * specified. Thus it is not necessary for TV input services to include a thumbnail if it is
         * just a scaled image of the poster art.
         *
         * <p>The data in the column must be a URL, or a URI in one of the following formats:
         *
         * <ul>
         * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
         * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})
         * </li>
         * <li>file ({@link android.content.ContentResolver#SCHEME_FILE})</li>
         * </ul>
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        String COLUMN_THUMBNAIL_URI = "thumbnail_uri";

        /**
         * The flag indicating whether this TV program is searchable or not.
         *
         * <p>The columns of searchable programs can be read by other applications that have proper
         * permission. Care must be taken not to open sensitive data.
         *
         * <p>A value of 1 indicates that the program is searchable and its columns can be read by
         * other applications, a value of 0 indicates that the program is hidden and its columns can
         * be read only by the package that owns the program and the system. If not specified, this
         * value is set to 1 (searchable) by default.
         *
         * <p>Type: INTEGER (boolean)
         */
        String COLUMN_SEARCHABLE = "searchable";

        /**
         * Internal data used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: BLOB
         */
        String COLUMN_INTERNAL_PROVIDER_DATA = "internal_provider_data";

        /**
         * Internal integer flag used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_INTERNAL_PROVIDER_FLAG1 = "internal_provider_flag1";

        /**
         * Internal integer flag used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_INTERNAL_PROVIDER_FLAG2 = "internal_provider_flag2";

        /**
         * Internal integer flag used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_INTERNAL_PROVIDER_FLAG3 = "internal_provider_flag3";

        /**
         * Internal integer flag used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_INTERNAL_PROVIDER_FLAG4 = "internal_provider_flag4";

        /**
         * The version number of this row entry used by TV input services.
         *
         * <p>This is best used by sync adapters to identify the rows to update. The number can be
         * defined by individual TV input services. One may assign the same value as
         * {@code version_number} in ETSI EN 300 468 or ATSC A/65, if the data are coming from a TV
         * broadcast.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_VERSION_NUMBER = "version_number";

        /**
         * The review rating score style used for {@link #COLUMN_REVIEW_RATING}.
         *
         * <p> The value should match one of the followings: {@link #REVIEW_RATING_STYLE_STARS},
         * {@link #REVIEW_RATING_STYLE_THUMBS_UP_DOWN}, and {@link #REVIEW_RATING_STYLE_PERCENTAGE}.
         *
         * <p>Type: INTEGER
         * @see #COLUMN_REVIEW_RATING
         */
        String COLUMN_REVIEW_RATING_STYLE = "review_rating_style";

        /**
         * The review rating score for this program.
         *
         * <p>The format of the value is dependent on {@link #COLUMN_REVIEW_RATING_STYLE}. If the
         * style is {@link #REVIEW_RATING_STYLE_STARS}, the value should be a real number between
         * 0.0 and 5.0. (e.g. "4.5") If the style is {@link #REVIEW_RATING_STYLE_THUMBS_UP_DOWN},
         * the value should be two integers, one for thumbs-up count and the other for thumbs-down
         * count, with a comma between them. (e.g. "200,40") If the style is
         * {@link #REVIEW_RATING_STYLE_PERCENTAGE}, the value shoule be a real number between 0 and
         * 100. (e.g. "99.9")
         *
         * <p>Type: TEXT
         * @see #COLUMN_REVIEW_RATING_STYLE
         */
        String COLUMN_REVIEW_RATING = "review_rating";

        /**
         * The series ID of this TV program for episodic TV shows.
         *
         * <p>This is used to indicate the series ID. Programs in the same series share a series ID.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        String COLUMN_SERIES_ID = "series_id";

        /**
         * The split ID of this TV program for multi-part content, as a URI.
         *
         * <p>A content may consist of multiple programs within the same channel or over several
         * channels. For example, a film might be divided into two parts interrupted by a news in
         * the middle or a longer sport event might be split into several parts over several
         * channels. The split ID is used to identify all the programs in the same multi-part
         * content. Suitable URIs include
         * <ul>
         * <li>{@code crid://<CRIDauthority>/<data>#<IMI>} from ETSI TS 102 323
         * </ul>
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        String COLUMN_SPLIT_ID = "split_id";
    }

    /**
     * Common columns for the tables of preview programs.
     * @hide
     */
    interface PreviewProgramColumns {

        /** @hide */
        @IntDef({
                TYPE_MOVIE,
                TYPE_TV_SERIES,
                TYPE_TV_SEASON,
                TYPE_TV_EPISODE,
                TYPE_CLIP,
                TYPE_EVENT,
                TYPE_CHANNEL,
                TYPE_TRACK,
                TYPE_ALBUM,
                TYPE_ARTIST,
                TYPE_PLAYLIST,
                TYPE_STATION,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Type {}

        /**
         * The program type for movie.
         *
         * @see #COLUMN_TYPE
         */
        int TYPE_MOVIE = 0;

        /**
         * The program type for TV series.
         *
         * @see #COLUMN_TYPE
         */
        int TYPE_TV_SERIES = 1;

        /**
         * The program type for TV season.
         *
         * @see #COLUMN_TYPE
         */
        int TYPE_TV_SEASON = 2;

        /**
         * The program type for TV episode.
         *
         * @see #COLUMN_TYPE
         */
        int TYPE_TV_EPISODE = 3;

        /**
         * The program type for clip.
         *
         * @see #COLUMN_TYPE
         */
        int TYPE_CLIP = 4;

        /**
         * The program type for event.
         *
         * @see #COLUMN_TYPE
         */
        int TYPE_EVENT = 5;

        /**
         * The program type for channel.
         *
         * @see #COLUMN_TYPE
         */
        int TYPE_CHANNEL = 6;

        /**
         * The program type for track.
         *
         * @see #COLUMN_TYPE
         */
        int TYPE_TRACK = 7;

        /**
         * The program type for album.
         *
         * @see #COLUMN_TYPE
         */
        int TYPE_ALBUM = 8;

        /**
         * The program type for artist.
         *
         * @see #COLUMN_TYPE
         */
        int TYPE_ARTIST = 9;

        /**
         * The program type for playlist.
         *
         * @see #COLUMN_TYPE
         */
        int TYPE_PLAYLIST = 10;

        /**
         * The program type for station.
         *
         * @see #COLUMN_TYPE
         */
        int TYPE_STATION = 11;

        /** @hide */
        @IntDef({
                ASPECT_RATIO_16_9,
                ASPECT_RATIO_3_2,
                ASPECT_RATIO_1_1,
                ASPECT_RATIO_2_3,
                ASPECT_RATIO_4_3,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface AspectRatio {}

        /**
         * The aspect ratio for 16:9.
         *
         * @see #COLUMN_POSTER_ART_ASPECT_RATIO
         * @see #COLUMN_THUMBNAIL_ASPECT_RATIO
         */
        int ASPECT_RATIO_16_9 = 0;

        /**
         * The aspect ratio for 3:2.
         *
         * @see #COLUMN_POSTER_ART_ASPECT_RATIO
         * @see #COLUMN_THUMBNAIL_ASPECT_RATIO
         */
        int ASPECT_RATIO_3_2 = 1;

        /**
         * The aspect ratio for 4:3.
         *
         * @see #COLUMN_POSTER_ART_ASPECT_RATIO
         * @see #COLUMN_THUMBNAIL_ASPECT_RATIO
         */
        int ASPECT_RATIO_4_3 = 2;

        /**
         * The aspect ratio for 1:1.
         *
         * @see #COLUMN_POSTER_ART_ASPECT_RATIO
         * @see #COLUMN_THUMBNAIL_ASPECT_RATIO
         */
        int ASPECT_RATIO_1_1 = 3;

        /**
         * The aspect ratio for 2:3.
         *
         * @see #COLUMN_POSTER_ART_ASPECT_RATIO
         * @see #COLUMN_THUMBNAIL_ASPECT_RATIO
         */
        int ASPECT_RATIO_2_3 = 4;

        /** @hide */
        @IntDef({
                AVAILABILITY_AVAILABLE,
                AVAILABILITY_FREE_WITH_SUBSCRIPTION,
                AVAILABILITY_PAID_CONTENT,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Availability {}

        /**
         * The availability for "available to this user".
         *
         * @see #COLUMN_AVAILABILITY
         */
        int AVAILABILITY_AVAILABLE = 0;

        /**
         * The availability for "free with subscription".
         *
         * @see #COLUMN_AVAILABILITY
         */
        int AVAILABILITY_FREE_WITH_SUBSCRIPTION = 1;

        /**
         * The availability for "paid content, either to-own or rental
         * (user has not purchased/rented).
         *
         * @see #COLUMN_AVAILABILITY
         */
        int AVAILABILITY_PAID_CONTENT = 2;

        /** @hide */
        @IntDef({
                INTERACTION_TYPE_VIEWS,
                INTERACTION_TYPE_LISTENS,
                INTERACTION_TYPE_FOLLOWERS,
                INTERACTION_TYPE_FANS,
                INTERACTION_TYPE_LIKES,
                INTERACTION_TYPE_THUMBS,
                INTERACTION_TYPE_VIEWERS,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface InteractionType {}

        /**
         * The interaction type for "views".
         *
         * @see #COLUMN_INTERACTION_TYPE
         */
        int INTERACTION_TYPE_VIEWS = 0;

        /**
         * The interaction type for "listens".
         *
         * @see #COLUMN_INTERACTION_TYPE
         */
        int INTERACTION_TYPE_LISTENS = 1;

        /**
         * The interaction type for "followers".
         *
         * @see #COLUMN_INTERACTION_TYPE
         */
        int INTERACTION_TYPE_FOLLOWERS = 2;

        /**
         * The interaction type for "fans".
         *
         * @see #COLUMN_INTERACTION_TYPE
         */
        int INTERACTION_TYPE_FANS = 3;

        /**
         * The interaction type for "likes".
         *
         * @see #COLUMN_INTERACTION_TYPE
         */
        int INTERACTION_TYPE_LIKES = 4;

        /**
         * The interaction type for "thumbs".
         *
         * @see #COLUMN_INTERACTION_TYPE
         */
        int INTERACTION_TYPE_THUMBS = 5;

        /**
         * The interaction type for "viewers".
         *
         * @see #COLUMN_INTERACTION_TYPE
         */
        int INTERACTION_TYPE_VIEWERS = 6;

        /**
         * The type of this program content.
         *
         * <p>The value should match one of the followings:
         * {@link #TYPE_MOVIE},
         * {@link #TYPE_TV_SERIES},
         * {@link #TYPE_TV_SEASON},
         * {@link #TYPE_TV_EPISODE},
         * {@link #TYPE_CLIP},
         * {@link #TYPE_EVENT},
         * {@link #TYPE_CHANNEL},
         * {@link #TYPE_TRACK},
         * {@link #TYPE_ALBUM},
         * {@link #TYPE_ARTIST},
         * {@link #TYPE_PLAYLIST}, and
         * {@link #TYPE_STATION}.
         *
         * <p>This is a required field if the program is from a {@link Channels#TYPE_PREVIEW}
         * channel.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_TYPE = "type";

        /**
         * The aspect ratio of the poster art for this TV program.
         *
         * <p>The value should match one of the followings:
         * {@link #ASPECT_RATIO_16_9},
         * {@link #ASPECT_RATIO_3_2},
         * {@link #ASPECT_RATIO_4_3},
         * {@link #ASPECT_RATIO_1_1}, and
         * {@link #ASPECT_RATIO_2_3}.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_POSTER_ART_ASPECT_RATIO = "poster_art_aspect_ratio";

        /**
         * The aspect ratio of the thumbnail for this TV program.
         *
         * <p>The value should match one of the followings:
         * {@link #ASPECT_RATIO_16_9},
         * {@link #ASPECT_RATIO_3_2},
         * {@link #ASPECT_RATIO_4_3},
         * {@link #ASPECT_RATIO_1_1}, and
         * {@link #ASPECT_RATIO_2_3}.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_THUMBNAIL_ASPECT_RATIO = "poster_thumbnail_aspect_ratio";

        /**
         * The URI for the logo of this TV program.
         *
         * <p>This is a small badge shown on top of the poster art or thumbnail representing the
         * source of the content.
         *
         * <p>The data in the column must be a URL, or a URI in one of the following formats:
         *
         * <ul>
         * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
         * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})
         * </li>
         * <li>file ({@link android.content.ContentResolver#SCHEME_FILE})</li>
         * </ul>
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        String COLUMN_LOGO_URI = "logo_uri";

        /**
         * The availability of this TV program.
         *
         * <p>The value should match one of the followings:
         * {@link #AVAILABILITY_AVAILABLE},
         * {@link #AVAILABILITY_FREE_WITH_SUBSCRIPTION}, and
         * {@link #AVAILABILITY_PAID_CONTENT}.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_AVAILABILITY = "availability";

        /**
         * The starting price of this TV program.
         *
         * <p>This indicates the lowest regular acquisition cost of the content. It is only used
         * if the availability of the program is {@link #AVAILABILITY_PAID_CONTENT}.
         *
         * <p>Type: TEXT
         * @see #COLUMN_OFFER_PRICE
         */
        String COLUMN_STARTING_PRICE = "starting_price";

        /**
         * The offer price of this TV program.
         *
         * <p>This is the promotional cost of the content. It is only used if the availability of
         * the program is {@link #AVAILABILITY_PAID_CONTENT}.
         *
         * <p>Type: TEXT
         * @see #COLUMN_STARTING_PRICE
         */
        String COLUMN_OFFER_PRICE = "offer_price";

        /**
         * The release date of this TV program.
         *
         * <p>The value should be in one of the following formats:
         * "yyyy", "yyyy-MM-dd", and "yyyy-MM-ddTHH:mm:ssZ" (UTC in ISO 8601).
         *
         * <p>Type: TEXT
         */
        String COLUMN_RELEASE_DATE = "release_date";

        /**
         * The count of the items included in this TV program.
         *
         * <p>This is only relevant if the program represents a collection of items such as series,
         * episodes, or music tracks.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_ITEM_COUNT = "item_count";

        /**
         * The flag indicating whether this TV program is live or not.
         *
         * <p>A value of 1 indicates that the content is airing and should be consumed now, a value
         * of 0 indicates that the content is off the air and does not need to be consumed at the
         * present time. If not specified, the value is set to 0 (not live) by default.
         *
         * <p>Type: INTEGER (boolean)
         */
        String COLUMN_LIVE = "live";

        /**
         * The internal ID used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        String COLUMN_INTERNAL_PROVIDER_ID = "internal_provider_id";

        /**
         * The URI for the preview video.
         *
         * <p>The data in the column must be a URL, or a URI in one of the following formats:
         *
         * <ul>
         * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
         * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})
         * </li>
         * <li>file ({@link android.content.ContentResolver#SCHEME_FILE})</li>
         * </ul>
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        String COLUMN_PREVIEW_VIDEO_URI = "preview_video_uri";

        /**
         * The last playback position (in milliseconds) of the original content of this preview
         * program.
         *
         * <p>Can be empty.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_LAST_PLAYBACK_POSITION_MILLIS =
                "last_playback_position_millis";

        /**
         * The duration (in milliseconds) of the original content of this preview program.
         *
         * <p>Can be empty.
         *
         * <p>Type: INTEGER
         */
        String COLUMN_DURATION_MILLIS = "duration_millis";

        /**
         * The intent URI which is launched when the preview program is selected.
         *
         * <p>The URI is created using {@link Intent#toUri} with {@link Intent#URI_INTENT_SCHEME}
         * and converted back to the original intent with {@link Intent#parseUri}. The intent is
         * launched when the user selects the preview program item.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        String COLUMN_INTENT_URI = "intent_uri";

        /**
         * The flag indicating whether this program is transient or not.
         *
         * <p>A value of 1 indicates that the channel will be automatically removed by the system on
         * reboot, and a value of 0 indicates that the channel is persistent across reboot. If not
         * specified, this value is set to 0 (not transient) by default.
         *
         * <p>Type: INTEGER (boolean)
         * @see Channels#COLUMN_TRANSIENT
         */
        String COLUMN_TRANSIENT = "transient";

        /**
         * The type of interaction for this TV program.
         *
         * <p> The value should match one of the followings:
         * {@link #INTERACTION_TYPE_VIEWS},
         * {@link #INTERACTION_TYPE_LISTENS},
         * {@link #INTERACTION_TYPE_FOLLOWERS},
         * {@link #INTERACTION_TYPE_FANS},
         * {@link #INTERACTION_TYPE_LIKES},
         * {@link #INTERACTION_TYPE_THUMBS}, and
         * {@link #INTERACTION_TYPE_VIEWERS}.
         *
         * <p>Type: INTEGER
         * @see #COLUMN_INTERACTION_COUNT
         */
        String COLUMN_INTERACTION_TYPE = "interaction_type";

        /**
         * The interaction count for this program.
         *
         * <p>This indicates the number of times interaction has happened.
         *
         * <p>Type: INTEGER (long)
         * @see #COLUMN_INTERACTION_TYPE
         */
        String COLUMN_INTERACTION_COUNT = "interaction_count";

        /**
         * The author or artist of this content.
         *
         * <p>Type: TEXT
         */
        String COLUMN_AUTHOR = "author";

        /**
         * The flag indicating whether this TV program is browsable or not.
         *
         * <p>This column can only be set by applications having proper system permission. For
         * other applications, this is a read-only column.
         *
         * <p>A value of 1 indicates that the program is browsable and can be shown to users in
         * the UI. A value of 0 indicates that the program should be hidden from users and the
         * application who changes this value to 0 should send
         * {@link #ACTION_WATCH_NEXT_PROGRAM_BROWSABLE_DISABLED} to the owner of the program
         * to notify this change.
         *
         * <p>This value is set to 1 (browsable) by default.
         *
         * <p>Type: INTEGER (boolean)
         */
        String COLUMN_BROWSABLE = "browsable";

        /**
         * The content ID of this TV program.
         *
         * <p>A public ID of the content which allows the application to apply the same operation to
         * all the program copies in different channels.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        String COLUMN_CONTENT_ID = "content_id";

        /**
         * The start time of this TV program, in milliseconds since the epoch.
         *
         * <p>Should be empty if this program is not live.
         *
         * <p>Type: INTEGER (long)
         * @see #COLUMN_LIVE
         */
        String COLUMN_START_TIME_UTC_MILLIS = "start_time_utc_millis";

        /**
         * The end time of this TV program, in milliseconds since the epoch.
         *
         * <p>Should be empty if this program is not live.
         *
         * <p>Type: INTEGER (long)
         * @see #COLUMN_LIVE
         */
        String COLUMN_END_TIME_UTC_MILLIS = "end_time_utc_millis";
    }

    /** Column definitions for the TV channels table. */
    public static final class Channels implements BaseTvColumns {

        /**
         * The content:// style URI for this table.
         *
         * <p>SQL selection is not supported for {@link ContentResolver#query},
         * {@link ContentResolver#update} and {@link ContentResolver#delete} operations.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/"
                + PATH_CHANNEL);

        /** The MIME type of a directory of TV channels. */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/channel";

        /** The MIME type of a single TV channel. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/channel";

        /** @hide */
        @StringDef(prefix = { "TYPE_" }, value = {
                TYPE_OTHER,
                TYPE_NTSC,
                TYPE_PAL,
                TYPE_SECAM,
                TYPE_DVB_T,
                TYPE_DVB_T2,
                TYPE_DVB_S,
                TYPE_DVB_S2,
                TYPE_DVB_C,
                TYPE_DVB_C2,
                TYPE_DVB_H,
                TYPE_DVB_SH,
                TYPE_ATSC_T,
                TYPE_ATSC_C,
                TYPE_ATSC_M_H,
                TYPE_ATSC3_T,
                TYPE_ISDB_T,
                TYPE_ISDB_TB,
                TYPE_ISDB_S,
                TYPE_ISDB_S3,
                TYPE_ISDB_C,
                TYPE_1SEG,
                TYPE_DTMB,
                TYPE_CMMB,
                TYPE_T_DMB,
                TYPE_S_DMB,
                TYPE_PREVIEW,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Type {}

        /**
         * A generic channel type.
         *
         * Use this if the current channel is streaming-based or its broadcast system type does not
         * fit under any other types. This is the default channel type.
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_OTHER = "TYPE_OTHER";

        /**
         * The channel type for NTSC.
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_NTSC = "TYPE_NTSC";

        /**
         * The channel type for PAL.
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_PAL = "TYPE_PAL";

        /**
         * The channel type for SECAM.
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_SECAM = "TYPE_SECAM";

        /**
         * The channel type for DVB-T (terrestrial).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_DVB_T = "TYPE_DVB_T";

        /**
         * The channel type for DVB-T2 (terrestrial).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_DVB_T2 = "TYPE_DVB_T2";

        /**
         * The channel type for DVB-S (satellite).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_DVB_S = "TYPE_DVB_S";

        /**
         * The channel type for DVB-S2 (satellite).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_DVB_S2 = "TYPE_DVB_S2";

        /**
         * The channel type for DVB-C (cable).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_DVB_C = "TYPE_DVB_C";

        /**
         * The channel type for DVB-C2 (cable).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_DVB_C2 = "TYPE_DVB_C2";

        /**
         * The channel type for DVB-H (handheld).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_DVB_H = "TYPE_DVB_H";

        /**
         * The channel type for DVB-SH (satellite).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_DVB_SH = "TYPE_DVB_SH";

        /**
         * The channel type for ATSC (terrestrial).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_ATSC_T = "TYPE_ATSC_T";

        /**
         * The channel type for ATSC (cable).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_ATSC_C = "TYPE_ATSC_C";

        /**
         * The channel type for ATSC-M/H (mobile/handheld).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_ATSC_M_H = "TYPE_ATSC_M_H";

        /**
         * The channel type for ATSC3.0 (terrestrial).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_ATSC3_T = "TYPE_ATSC3_T";

        /**
         * The channel type for ISDB-T (terrestrial).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_ISDB_T = "TYPE_ISDB_T";

        /**
         * The channel type for ISDB-Tb (Brazil).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_ISDB_TB = "TYPE_ISDB_TB";

        /**
         * The channel type for ISDB-S (satellite).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_ISDB_S = "TYPE_ISDB_S";

        /**
         * The channel type for ISDB-S3 (satellite).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_ISDB_S3 = "TYPE_ISDB_S3";

        /**
         * The channel type for ISDB-C (cable).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_ISDB_C = "TYPE_ISDB_C";

        /**
         * The channel type for 1seg (handheld).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_1SEG = "TYPE_1SEG";

        /**
         * The channel type for DTMB (terrestrial).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_DTMB = "TYPE_DTMB";

        /**
         * The channel type for CMMB (handheld).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_CMMB = "TYPE_CMMB";

        /**
         * The channel type for T-DMB (terrestrial).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_T_DMB = "TYPE_T_DMB";

        /**
         * The channel type for S-DMB (satellite).
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_S_DMB = "TYPE_S_DMB";

        /**
         * The channel type for preview videos.
         *
         * <P>Unlike other broadcast TV channel types, the programs in the preview channel usually
         * are promotional videos. The UI may treat the preview channels differently from the other
         * broadcast channels.
         *
         * @see #COLUMN_TYPE
         */
        public static final String TYPE_PREVIEW = "TYPE_PREVIEW";

        /** @hide */
        @StringDef(prefix = { "SERVICE_TYPE_" }, value = {
                SERVICE_TYPE_OTHER,
                SERVICE_TYPE_AUDIO_VIDEO,
                SERVICE_TYPE_AUDIO,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface ServiceType {}

        /** A generic service type. */
        public static final String SERVICE_TYPE_OTHER = "SERVICE_TYPE_OTHER";

        /** The service type for regular TV channels that have both audio and video. */
        public static final String SERVICE_TYPE_AUDIO_VIDEO = "SERVICE_TYPE_AUDIO_VIDEO";

        /** The service type for radio channels that have audio only. */
        public static final String SERVICE_TYPE_AUDIO = "SERVICE_TYPE_AUDIO";

        /** @hide */
        @StringDef(prefix = { "VIDEO_FORMAT_" }, value = {
                VIDEO_FORMAT_240P,
                VIDEO_FORMAT_360P,
                VIDEO_FORMAT_480I,
                VIDEO_FORMAT_576I,
                VIDEO_FORMAT_576P,
                VIDEO_FORMAT_720P,
                VIDEO_FORMAT_1080I,
                VIDEO_FORMAT_1080P,
                VIDEO_FORMAT_2160P,
                VIDEO_FORMAT_4320P,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface VideoFormat {}

        /** The video format for 240p. */
        public static final String VIDEO_FORMAT_240P = "VIDEO_FORMAT_240P";

        /** The video format for 360p. */
        public static final String VIDEO_FORMAT_360P = "VIDEO_FORMAT_360P";

        /** The video format for 480i. */
        public static final String VIDEO_FORMAT_480I = "VIDEO_FORMAT_480I";

        /** The video format for 480p. */
        public static final String VIDEO_FORMAT_480P = "VIDEO_FORMAT_480P";

        /** The video format for 576i. */
        public static final String VIDEO_FORMAT_576I = "VIDEO_FORMAT_576I";

        /** The video format for 576p. */
        public static final String VIDEO_FORMAT_576P = "VIDEO_FORMAT_576P";

        /** The video format for 720p. */
        public static final String VIDEO_FORMAT_720P = "VIDEO_FORMAT_720P";

        /** The video format for 1080i. */
        public static final String VIDEO_FORMAT_1080I = "VIDEO_FORMAT_1080I";

        /** The video format for 1080p. */
        public static final String VIDEO_FORMAT_1080P = "VIDEO_FORMAT_1080P";

        /** The video format for 2160p. */
        public static final String VIDEO_FORMAT_2160P = "VIDEO_FORMAT_2160P";

        /** The video format for 4320p. */
        public static final String VIDEO_FORMAT_4320P = "VIDEO_FORMAT_4320P";

        /** @hide */
        @StringDef(prefix = { "VIDEO_RESOLUTION_" }, value = {
                VIDEO_RESOLUTION_SD,
                VIDEO_RESOLUTION_ED,
                VIDEO_RESOLUTION_HD,
                VIDEO_RESOLUTION_FHD,
                VIDEO_RESOLUTION_UHD,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface VideoResolution {}

        /** The video resolution for standard-definition. */
        public static final String VIDEO_RESOLUTION_SD = "VIDEO_RESOLUTION_SD";

        /** The video resolution for enhanced-definition. */
        public static final String VIDEO_RESOLUTION_ED = "VIDEO_RESOLUTION_ED";

        /** The video resolution for high-definition. */
        public static final String VIDEO_RESOLUTION_HD = "VIDEO_RESOLUTION_HD";

        /** The video resolution for full high-definition. */
        public static final String VIDEO_RESOLUTION_FHD = "VIDEO_RESOLUTION_FHD";

        /** The video resolution for ultra high-definition. */
        public static final String VIDEO_RESOLUTION_UHD = "VIDEO_RESOLUTION_UHD";

        private static final Map<String, String> VIDEO_FORMAT_TO_RESOLUTION_MAP = new HashMap<>();

        static {
            VIDEO_FORMAT_TO_RESOLUTION_MAP.put(VIDEO_FORMAT_480I, VIDEO_RESOLUTION_SD);
            VIDEO_FORMAT_TO_RESOLUTION_MAP.put(VIDEO_FORMAT_480P, VIDEO_RESOLUTION_ED);
            VIDEO_FORMAT_TO_RESOLUTION_MAP.put(VIDEO_FORMAT_576I, VIDEO_RESOLUTION_SD);
            VIDEO_FORMAT_TO_RESOLUTION_MAP.put(VIDEO_FORMAT_576P, VIDEO_RESOLUTION_ED);
            VIDEO_FORMAT_TO_RESOLUTION_MAP.put(VIDEO_FORMAT_720P, VIDEO_RESOLUTION_HD);
            VIDEO_FORMAT_TO_RESOLUTION_MAP.put(VIDEO_FORMAT_1080I, VIDEO_RESOLUTION_HD);
            VIDEO_FORMAT_TO_RESOLUTION_MAP.put(VIDEO_FORMAT_1080P, VIDEO_RESOLUTION_FHD);
            VIDEO_FORMAT_TO_RESOLUTION_MAP.put(VIDEO_FORMAT_2160P, VIDEO_RESOLUTION_UHD);
            VIDEO_FORMAT_TO_RESOLUTION_MAP.put(VIDEO_FORMAT_4320P, VIDEO_RESOLUTION_UHD);
        }

        /**
         * Returns the video resolution (definition) for a given video format.
         *
         * @param videoFormat The video format defined in {@link Channels}.
         * @return the corresponding video resolution string. {@code null} if the resolution string
         *         is not defined for the given video format.
         * @see #COLUMN_VIDEO_FORMAT
         */
        @Nullable
        public static final String getVideoResolution(@VideoFormat String videoFormat) {
            return VIDEO_FORMAT_TO_RESOLUTION_MAP.get(videoFormat);
        }

        /**
         * The ID of the TV input service that provides this TV channel.
         *
         * <p>Use {@link #buildInputId} to build the ID.
         *
         * <p>This is a required field.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_INPUT_ID = "input_id";

        /**
         * The broadcast system type of this TV channel.
         *
         * <p>This is used to indicate the broadcast standard (e.g. ATSC, DVB or ISDB) the current
         * channel conforms to. Use {@link #TYPE_OTHER} for streaming-based channels, which is the
         * default channel type. The value should match one of the followings:
         * {@link #TYPE_1SEG},
         * {@link #TYPE_ATSC_C},
         * {@link #TYPE_ATSC_M_H},
         * {@link #TYPE_ATSC_T},
         * {@link #TYPE_ATSC3_T},
         * {@link #TYPE_CMMB},
         * {@link #TYPE_DTMB},
         * {@link #TYPE_DVB_C},
         * {@link #TYPE_DVB_C2},
         * {@link #TYPE_DVB_H},
         * {@link #TYPE_DVB_S},
         * {@link #TYPE_DVB_S2},
         * {@link #TYPE_DVB_SH},
         * {@link #TYPE_DVB_T},
         * {@link #TYPE_DVB_T2},
         * {@link #TYPE_ISDB_C},
         * {@link #TYPE_ISDB_S},
         * {@link #TYPE_ISDB_S3},
         * {@link #TYPE_ISDB_T},
         * {@link #TYPE_ISDB_TB},
         * {@link #TYPE_NTSC},
         * {@link #TYPE_OTHER},
         * {@link #TYPE_PAL},
         * {@link #TYPE_SECAM},
         * {@link #TYPE_S_DMB},
         * {@link #TYPE_T_DMB}, and
         * {@link #TYPE_PREVIEW}.
         *
         * <p>This value cannot be changed once it's set. Trying to modify it will make the update
         * fail.
         *
         * <p>This is a required field.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_TYPE = "type";

        /**
         * The predefined service type of this TV channel.
         *
         * <p>This is primarily used to indicate whether the current channel is a regular TV channel
         * or a radio-like channel. Use the same coding for {@code service_type} in the underlying
         * broadcast standard if it is defined there (e.g. ATSC A/53, ETSI EN 300 468 and ARIB
         * STD-B10). Otherwise use one of the followings: {@link #SERVICE_TYPE_OTHER},
         * {@link #SERVICE_TYPE_AUDIO_VIDEO}, {@link #SERVICE_TYPE_AUDIO}
         *
         * <p>This is a required field.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_SERVICE_TYPE = "service_type";

        /**
         * The original network ID of this TV channel.
         *
         * <p>It is used to identify the originating delivery system, if applicable. Use the same
         * coding for {@code original_network_id} for ETSI EN 300 468/TR 101 211 and ARIB STD-B10.
         *
         * <p>This is a required field only if the underlying broadcast standard defines the same
         * name field. Otherwise, leave empty.
         *
         * <p>Type: INTEGER
         */
        public static final String COLUMN_ORIGINAL_NETWORK_ID = "original_network_id";

        /**
         * The transport stream ID of this channel.
         *
         * <p>It is used to identify the Transport Stream that contains the current channel from any
         * other multiplex within a network, if applicable. Use the same coding for
         * {@code transport_stream_id} defined in ISO/IEC 13818-1 if the channel is transmitted via
         * the MPEG Transport Stream.
         *
         * <p>This is a required field only if the current channel is transmitted via the MPEG
         * Transport Stream. Leave empty otherwise.
         *
         * <p>Type: INTEGER
         */
        public static final String COLUMN_TRANSPORT_STREAM_ID = "transport_stream_id";

        /**
         * The service ID of this channel.
         *
         * <p>It is used to identify the current service, or channel from any other services within
         * a given Transport Stream, if applicable. Use the same coding for {@code service_id} in
         * ETSI EN 300 468 and ARIB STD-B10 or {@code program_number} in ISO/IEC 13818-1.
         *
         * <p>This is a required field only if the underlying broadcast standard defines the same
         * name field, or the current channel is transmitted via the MPEG Transport Stream. Leave
         * empty otherwise.
         *
         * <p>Type: INTEGER
         */
        public static final String COLUMN_SERVICE_ID = "service_id";

        /**
         * The channel number that is displayed to the user.
         *
         * <p>The format can vary depending on broadcast standard and product specification.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_DISPLAY_NUMBER = "display_number";

        /**
         * The channel name that is displayed to the user.
         *
         * <p>A call sign is a good candidate to use for this purpose but any name that helps the
         * user recognize the current channel will be enough. Can also be empty depending on
         * broadcast standard.
         *
         * <p> Type: TEXT
         */
        public static final String COLUMN_DISPLAY_NAME = "display_name";

        /**
         * The network affiliation for this TV channel.
         *
         * <p>This is used to identify a channel that is commonly called by its network affiliation
         * instead of the display name. Examples include ABC for the channel KGO-HD, FOX for the
         * channel KTVU-HD and NBC for the channel KNTV-HD. Can be empty if not applicable.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_NETWORK_AFFILIATION = "network_affiliation";

        /**
         * The description of this TV channel.
         *
         * <p>Can be empty initially.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_DESCRIPTION = "description";

        /**
         * The typical video format for programs from this TV channel.
         *
         * <p>This is primarily used to filter out channels based on video format by applications.
         * The value should match one of the followings: {@link #VIDEO_FORMAT_240P},
         * {@link #VIDEO_FORMAT_360P}, {@link #VIDEO_FORMAT_480I}, {@link #VIDEO_FORMAT_480P},
         * {@link #VIDEO_FORMAT_576I}, {@link #VIDEO_FORMAT_576P}, {@link #VIDEO_FORMAT_720P},
         * {@link #VIDEO_FORMAT_1080I}, {@link #VIDEO_FORMAT_1080P}, {@link #VIDEO_FORMAT_2160P},
         * {@link #VIDEO_FORMAT_4320P}. Note that the actual video resolution of each program from a
         * given channel can vary thus one should use {@link Programs#COLUMN_VIDEO_WIDTH} and
         * {@link Programs#COLUMN_VIDEO_HEIGHT} to get more accurate video resolution.
         *
         * <p>Type: TEXT
         *
         * @see #getVideoResolution
         */
        public static final String COLUMN_VIDEO_FORMAT = "video_format";

        /**
         * The flag indicating whether this TV channel is browsable or not.
         *
         * <p>This column can only be set by applications having proper system permission. For
         * other applications, this is a read-only column.
         *
         * <p>A value of 1 indicates the channel is included in the channel list that applications
         * use to browse channels, a value of 0 indicates the channel is not included in the list.
         * If not specified, this value is set to 0 (not browsable) by default.
         *
         * <p>Type: INTEGER (boolean)
         */
        public static final String COLUMN_BROWSABLE = "browsable";

        /**
         * The flag indicating whether this TV channel is searchable or not.
         *
         * <p>The columns of searchable channels can be read by other applications that have proper
         * permission. Care must be taken not to open sensitive data.
         *
         * <p>A value of 1 indicates that the channel is searchable and its columns can be read by
         * other applications, a value of 0 indicates that the channel is hidden and its columns can
         * be read only by the package that owns the channel and the system. If not specified, this
         * value is set to 1 (searchable) by default.
         *
         * <p>Type: INTEGER (boolean)
         */
        public static final String COLUMN_SEARCHABLE = "searchable";

        /**
         * The flag indicating whether this TV channel is locked or not.
         *
         * <p>This is primarily used for alternative parental control to prevent unauthorized users
         * from watching the current channel regardless of the content rating. A value of 1
         * indicates the channel is locked and the user is required to enter passcode to unlock it
         * in order to watch the current program from the channel, a value of 0 indicates the
         * channel is not locked thus the user is not prompted to enter passcode If not specified,
         * this value is set to 0 (not locked) by default.
         *
         * <p>This column can only be set by applications having proper system permission to
         * modify parental control settings. For other applications, this is a read-only column.

         * <p>Type: INTEGER (boolean)
         */
        public static final String COLUMN_LOCKED = "locked";

        /**
         * The URI for the app badge icon of the app link template for this channel.
         *
         * <p>This small icon is overlaid at the bottom of the poster art specified by
         * {@link #COLUMN_APP_LINK_POSTER_ART_URI}. The data in the column must be a URI in one of
         * the following formats:
         *
         * <ul>
         * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
         * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})
         * </li>
         * <li>file ({@link android.content.ContentResolver#SCHEME_FILE})</li>
         * </ul>
         *
         * <p>The app-linking allows channel input sources to provide activity links from their live
         * channel programming to another activity. This enables content providers to increase user
         * engagement by offering the viewer other content or actions.
         *
         * <p>Type: TEXT
         * @see #COLUMN_APP_LINK_COLOR
         * @see #COLUMN_APP_LINK_INTENT_URI
         * @see #COLUMN_APP_LINK_POSTER_ART_URI
         * @see #COLUMN_APP_LINK_TEXT
         */
        public static final String COLUMN_APP_LINK_ICON_URI = "app_link_icon_uri";

        /**
         * The URI for the poster art used as the background of the app link template for this
         * channel.
         *
         * <p>The data in the column must be a URL, or a URI in one of the following formats:
         *
         * <ul>
         * <li>content ({@link android.content.ContentResolver#SCHEME_CONTENT})</li>
         * <li>android.resource ({@link android.content.ContentResolver#SCHEME_ANDROID_RESOURCE})
         * </li>
         * <li>file ({@link android.content.ContentResolver#SCHEME_FILE})</li>
         * </ul>
         *
         * <p>The app-linking allows channel input sources to provide activity links from their live
         * channel programming to another activity. This enables content providers to increase user
         * engagement by offering the viewer other content or actions.
         *
         * <p>Type: TEXT
         * @see #COLUMN_APP_LINK_COLOR
         * @see #COLUMN_APP_LINK_ICON_URI
         * @see #COLUMN_APP_LINK_INTENT_URI
         * @see #COLUMN_APP_LINK_TEXT
         */
        public static final String COLUMN_APP_LINK_POSTER_ART_URI = "app_link_poster_art_uri";

        /**
         * The link text of the app link template for this channel.
         *
         * <p>This provides a short description of the action that happens when the corresponding
         * app link is clicked.
         *
         * <p>The app-linking allows channel input sources to provide activity links from their live
         * channel programming to another activity. This enables content providers to increase user
         * engagement by offering the viewer other content or actions.
         *
         * <p>Type: TEXT
         * @see #COLUMN_APP_LINK_COLOR
         * @see #COLUMN_APP_LINK_ICON_URI
         * @see #COLUMN_APP_LINK_INTENT_URI
         * @see #COLUMN_APP_LINK_POSTER_ART_URI
         */
        public static final String COLUMN_APP_LINK_TEXT = "app_link_text";

        /**
         * The accent color of the app link template for this channel. This is primarily used for
         * the background color of the text box in the template.
         *
         * <p>The app-linking allows channel input sources to provide activity links from their live
         * channel programming to another activity. This enables content providers to increase user
         * engagement by offering the viewer other content or actions.
         *
         * <p>Type: INTEGER (color value)
         * @see #COLUMN_APP_LINK_ICON_URI
         * @see #COLUMN_APP_LINK_INTENT_URI
         * @see #COLUMN_APP_LINK_POSTER_ART_URI
         * @see #COLUMN_APP_LINK_TEXT
         */
        public static final String COLUMN_APP_LINK_COLOR = "app_link_color";

        /**
         * The intent URI of the app link for this channel.
         *
         * <p>The URI is created using {@link Intent#toUri} with {@link Intent#URI_INTENT_SCHEME}
         * and converted back to the original intent with {@link Intent#parseUri}. The intent is
         * launched when the user clicks the corresponding app link for the current channel.
         *
         * <p>The app-linking allows channel input sources to provide activity links from their live
         * channel programming to another activity. This enables content providers to increase user
         * engagement by offering the viewer other content or actions.
         *
         * <p>Type: TEXT
         * @see #COLUMN_APP_LINK_COLOR
         * @see #COLUMN_APP_LINK_ICON_URI
         * @see #COLUMN_APP_LINK_POSTER_ART_URI
         * @see #COLUMN_APP_LINK_TEXT
         */
        public static final String COLUMN_APP_LINK_INTENT_URI = "app_link_intent_uri";

        /**
         * The internal ID used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_INTERNAL_PROVIDER_ID = "internal_provider_id";

        /**
         * Internal data used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: BLOB
         */
        public static final String COLUMN_INTERNAL_PROVIDER_DATA = "internal_provider_data";

        /**
         * Internal integer flag used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: INTEGER
         */
        public static final String COLUMN_INTERNAL_PROVIDER_FLAG1 = "internal_provider_flag1";

        /**
         * Internal integer flag used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: INTEGER
         */
        public static final String COLUMN_INTERNAL_PROVIDER_FLAG2 = "internal_provider_flag2";

        /**
         * Internal integer flag used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: INTEGER
         */
        public static final String COLUMN_INTERNAL_PROVIDER_FLAG3 = "internal_provider_flag3";

        /**
         * Internal integer flag used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: INTEGER
         */
        public static final String COLUMN_INTERNAL_PROVIDER_FLAG4 = "internal_provider_flag4";

        /**
         * The version number of this row entry used by TV input services.
         *
         * <p>This is best used by sync adapters to identify the rows to update. The number can be
         * defined by individual TV input services. One may assign the same value as
         * {@code version_number} that appears in ETSI EN 300 468 or ATSC A/65, if the data are
         * coming from a TV broadcast.
         *
         * <p>Type: INTEGER
         */
        public static final String COLUMN_VERSION_NUMBER = "version_number";

        /**
         * The flag indicating whether this TV channel is transient or not.
         *
         * <p>A value of 1 indicates that the channel will be automatically removed by the system on
         * reboot, and a value of 0 indicates that the channel is persistent across reboot. If not
         * specified, this value is set to 0 (not transient) by default.
         *
         * <p>Type: INTEGER (boolean)
         * @see PreviewPrograms#COLUMN_TRANSIENT
         * @see WatchNextPrograms#COLUMN_TRANSIENT
         */
        public static final String COLUMN_TRANSIENT = "transient";

        /**
         * The global content ID of this TV channel, as a URI.
         *
         * <p>A globally unique URI that identifies this TV channel, if applicable. Suitable URIs
         * include
         * <ul>
         * <li>{@code globalServiceId} from ATSC A/331. ex {@code https://doi.org/10.5239/7E4E-B472}
         * <li>Other broadcast ID provider. ex {@code http://example.com/tv_channel/1234}
         * </ul>
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_GLOBAL_CONTENT_ID = "global_content_id";

        /**
         * The remote control key preset number that is assigned to this channel.
         *
         * <p> This can be used for one-touch-tuning, tuning to the channel with
         * pressing the preset button.
         *
         * <p> Type: INTEGER (remote control key preset number)
         */
        public static final String COLUMN_REMOTE_CONTROL_KEY_PRESET_NUMBER =
                "remote_control_key_preset_number";

        /**
         * The flag indicating whether this TV channel is scrambled or not.
         *
         * <p>Use the same coding for scrambled in the underlying broadcast standard
         * if {@code free_ca_mode} in SDT is defined there (e.g. ETSI EN 300 468).
         *
         * <p>Type: INTEGER (boolean)
         */
        public static final String COLUMN_SCRAMBLED = "scrambled";

        /**
         * The typical video resolution.
         *
         * <p>This is primarily used to filter out channels based on video resolution
         * by applications. The value is from SDT if defined there. (e.g. ETSI EN 300 468)
         * The value should match one of the followings: {@link #VIDEO_RESOLUTION_SD},
         * {@link #VIDEO_RESOLUTION_HD}, {@link #VIDEO_RESOLUTION_UHD}.
         *
         * <p>Type: TEXT
         *
         */
        public static final String COLUMN_VIDEO_RESOLUTION = "video_resolution";

        /**
         * The channel list ID of this TV channel.
         *
         * <p>It is used to identify the channel list constructed from broadcast SI based on the
         * underlying broadcast standard or country/operator profile, if applicable. Otherwise,
         * leave empty.
         *
         * <p>The ID can be defined by individual TV input services. For example, one may assign a
         * service operator name for the service operator channel list constructed from broadcast
         * SI or one may assign the {@code profile_name} of the operator_info() APDU defined in CI
         * Plus 1.3 for the dedicated CICAM operator profile channel list constructed
         * from CICAM NIT.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_CHANNEL_LIST_ID = "channel_list_id";

        /**
         * The comma-separated genre string of this TV channel.
         *
         * <p>Use the same language appeared in the underlying broadcast standard, if applicable.
         * Otherwise, leave empty. Use
         * {@link Genres#encode Genres.encode()} to create a text that can be stored in this column.
         * Use {@link Genres#decode Genres.decode()} to get the broadcast genre strings from the
         * text stored in the column.
         *
         * <p>Type: TEXT
         * @see Programs#COLUMN_BROADCAST_GENRE
         */
        public static final String COLUMN_BROADCAST_GENRE = Programs.COLUMN_BROADCAST_GENRE;

        /**
         * The broadcast visibility type of this TV channel.
         *
         * <p>This is used to indicate the broadcast visibility type defined in the underlying
         * broadcast standard or country/operator profile, if applicable. For example,
         * {@code visible_service_flag} and {@code numeric_selection_flag} of
         * {@code service_attribute_descriptor} in D-Book, the specification for UK-based TV
         * products, {@code visible_service_flag} and {@code selectable_service_flag} of
         * {@code ciplus_service_descriptor} in the CI Plus 1.3 specification.
         *
         * <p>The value should match one of the following:
         * {@link #BROADCAST_VISIBILITY_TYPE_VISIBLE},
         * {@link #BROADCAST_VISIBILITY_TYPE_NUMERIC_SELECTABLE_ONLY}, and
         * {@link #BROADCAST_VISIBILITY_TYPE_INVISIBLE}.
         *
         * <p>If not specified, this value is set to {@link #BROADCAST_VISIBILITY_TYPE_VISIBLE}
         * by default.
         *
         * <p>Type: INTEGER
         */
        @FlaggedApi(Flags.FLAG_BROADCAST_VISIBILITY_TYPES)
        public static final String COLUMN_BROADCAST_VISIBILITY_TYPE = "broadcast_visibility_type";

        /** @hide */
        @IntDef(prefix = { "BROADCAST_VISIBILITY_TYPE_" }, value = {
                BROADCAST_VISIBILITY_TYPE_VISIBLE,
                BROADCAST_VISIBILITY_TYPE_NUMERIC_SELECTABLE_ONLY,
                BROADCAST_VISIBILITY_TYPE_INVISIBLE,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface BroadcastVisibilityType {}

        /**
         * The broadcast visibility type for visible services. Use this type when the service is
         * visible from users and selectable by users via normal service navigation mechanisms.
         *
         * @see #COLUMN_BROADCAST_VISIBILITY_TYPE
         */
        @FlaggedApi(Flags.FLAG_BROADCAST_VISIBILITY_TYPES)
        public static final int BROADCAST_VISIBILITY_TYPE_VISIBLE = 0;

        /**
         * The broadcast visibility type for numeric selectable only services. Use this type when
         * the service is invisible from users but selectable by users only via direct entry of
         * the logical channel number.
         *
         * @see #COLUMN_BROADCAST_VISIBILITY_TYPE
         */
        @FlaggedApi(Flags.FLAG_BROADCAST_VISIBILITY_TYPES)
        public static final int BROADCAST_VISIBILITY_TYPE_NUMERIC_SELECTABLE_ONLY = 1;

        /**
         * The broadcast visibility type for invisible services. Use this type when the service
         * is invisible from users and not able to be selected by users via any of the normal
         * service navigation mechanisms.
         *
         * @see #COLUMN_BROADCAST_VISIBILITY_TYPE
         */
        @FlaggedApi(Flags.FLAG_BROADCAST_VISIBILITY_TYPES)
        public static final int BROADCAST_VISIBILITY_TYPE_INVISIBLE = 2;

        private Channels() {}

        /**
         * A sub-directory of a single TV channel that represents its primary logo.
         *
         * <p>To access this directory, append {@link Channels.Logo#CONTENT_DIRECTORY} to the raw
         * channel URI.  The resulting URI represents an image file, and should be interacted
         * using ContentResolver.openAssetFileDescriptor.
         *
         * <p>Note that this sub-directory also supports opening the logo as an asset file in write
         * mode.  Callers can create or replace the primary logo associated with this channel by
         * opening the asset file and writing the full-size photo contents into it. (Make sure there
         * is no padding around the logo image.) When the file is closed, the image will be parsed,
         * sized down if necessary, and stored.
         *
         * <p>Usage example:
         * <pre>
         * public void writeChannelLogo(long channelId, byte[] logo) {
         *     Uri channelLogoUri = TvContract.buildChannelLogoUri(channelId);
         *     try {
         *         AssetFileDescriptor fd =
         *             getContentResolver().openAssetFileDescriptor(channelLogoUri, "rw");
         *         OutputStream os = fd.createOutputStream();
         *         os.write(logo);
         *         os.close();
         *         fd.close();
         *     } catch (IOException e) {
         *         // Handle error cases.
         *     }
         * }
         * </pre>
         */
        public static final class Logo {

            /**
             * The directory twig for this sub-table.
             */
            public static final String CONTENT_DIRECTORY = "logo";

            private Logo() {}
        }
    }

    /**
     * Column definitions for the TV programs table.
     *
     * <p>By default, the query results will be sorted by
     * {@link Programs#COLUMN_START_TIME_UTC_MILLIS} in ascending order.
     */
    public static final class Programs implements BaseTvColumns, ProgramColumns {

        /**
         * The content:// style URI for this table.
         *
         * <p>SQL selection is not supported for {@link ContentResolver#query},
         * {@link ContentResolver#update} and {@link ContentResolver#delete} operations.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/"
                + PATH_PROGRAM);

        /** The MIME type of a directory of TV programs. */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/program";

        /** The MIME type of a single TV program. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/program";

        /**
         * The ID of the TV channel that provides this TV program.
         *
         * <p>This is a part of the channel URI and matches to {@link BaseColumns#_ID}.
         *
         * <p>This is a required field.
         *
         * <p>Type: INTEGER (long)
         */
        public static final String COLUMN_CHANNEL_ID = "channel_id";

        /**
         * The season number of this TV program for episodic TV shows.
         *
         * <p>Can be empty.
         *
         * <p>Type: INTEGER
         *
         * @deprecated Use {@link #COLUMN_SEASON_DISPLAY_NUMBER} instead.
         */
        @Deprecated
        public static final String COLUMN_SEASON_NUMBER = "season_number";

        /**
         * The episode number of this TV program for episodic TV shows.
         *
         * <p>Can be empty.
         *
         * <p>Type: INTEGER
         *
         * @deprecated Use {@link #COLUMN_EPISODE_DISPLAY_NUMBER} instead.
         */
        @Deprecated
        public static final String COLUMN_EPISODE_NUMBER = "episode_number";

        /**
         * The start time of this TV program, in milliseconds since the epoch.
         *
         * <p>The value should be equal to or larger than {@link #COLUMN_END_TIME_UTC_MILLIS} of the
         * previous program in the same channel. In practice, start time will usually be the end
         * time of the previous program.
         *
         * <p>Can be empty if this program belongs to a {@link Channels#TYPE_PREVIEW} channel.
         *
         * <p>Type: INTEGER (long)
         */
        public static final String COLUMN_START_TIME_UTC_MILLIS = "start_time_utc_millis";

        /**
         * The end time of this TV program, in milliseconds since the epoch.
         *
         * <p>The value should be equal to or less than {@link #COLUMN_START_TIME_UTC_MILLIS} of the
         * next program in the same channel. In practice, end time will usually be the start time of
         * the next program.
         *
         * <p>Can be empty if this program belongs to a {@link Channels#TYPE_PREVIEW} channel.
         *
         * <p>Type: INTEGER (long)
         */
        public static final String COLUMN_END_TIME_UTC_MILLIS = "end_time_utc_millis";

        /**
         * The comma-separated genre string of this TV program.
         *
         * <p>Use the same language appeared in the underlying broadcast standard, if applicable.
         * (For example, one can refer to the genre strings used in Genre Descriptor of ATSC A/65 or
         * Content Descriptor of ETSI EN 300 468, if appropriate.) Otherwise, leave empty. Use
         * {@link Genres#encode} to create a text that can be stored in this column. Use
         * {@link Genres#decode} to get the broadcast genre strings from the text stored in the
         * column.
         *
         * <p>Type: TEXT
         * @see Genres#encode
         * @see Genres#decode
         */
        public static final String COLUMN_BROADCAST_GENRE = "broadcast_genre";

        /**
         * The flag indicating whether recording of this program is prohibited.
         *
         * <p>A value of 1 indicates that recording of this program is prohibited and application
         * will not schedule any recording for this program. A value of 0 indicates that the
         * recording is not prohibited. If not specified, this value is set to 0 (not prohibited) by
         * default.
         *
         * <p>Type: INTEGER (boolean)
         */
        public static final String COLUMN_RECORDING_PROHIBITED = "recording_prohibited";

        /**
         * The event ID of this TV program.
         *
         * <p>It is used to identify the current TV program in the same channel, if applicable.
         * Use the same coding for {@code event_id} in the underlying broadcast standard if it
         * is defined there (e.g. ATSC A/65, ETSI EN 300 468 and ARIB STD-B10).
         *
         * <p>This is a required field only if the underlying broadcast standard defines the same
         * name field. Otherwise, leave empty.
         *
         * <p>Type: INTEGER
         */
        public static final String COLUMN_EVENT_ID = "event_id";

        /**
         * The global content ID of this TV program, as a URI.
         *
         * <p>A globally unique ID that identifies this TV program, if applicable. Suitable URIs
         * include
         * <ul>
         * <li>{@code crid://<CRIDauthority>/<data>} from ETSI TS 102 323
         * <li>{@code globalContentId} from ATSC A/332
         * <li>Other broadcast ID provider. ex {@code http://example.com/tv_program/1234}
         * </ul>
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_GLOBAL_CONTENT_ID = "global_content_id";

        /**
         * The flag indicating whether this TV program is scrambled or not.
         *
         * <p>Use the same coding for scrambled in the underlying broadcast standard
         * if {@code free_ca_mode} in EIT is defined there (e.g. ETSI EN 300 468).
         *
         * <p>Type: INTEGER (boolean)
         */
        public static final String COLUMN_SCRAMBLED = "scrambled";

        /**
         * The comma-separated series IDs of this TV program for episodic TV shows.
         *
         * <p>This is used to indicate the series IDs.
         * Programs in the same series share a series ID.
         * Use this instead of {@link #COLUMN_SERIES_ID} if more than one series IDs
         * are assigned to the TV program.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_MULTI_SERIES_ID = "multi_series_id";

        /**
         * The internal ID used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_INTERNAL_PROVIDER_ID = "internal_provider_id";

        private Programs() {}

        /** Canonical genres for TV programs. */
        public static final class Genres {
            /** @hide */
            @StringDef({
                    FAMILY_KIDS,
                    SPORTS,
                    SHOPPING,
                    MOVIES,
                    COMEDY,
                    TRAVEL,
                    DRAMA,
                    EDUCATION,
                    ANIMAL_WILDLIFE,
                    NEWS,
                    GAMING,
                    ARTS,
                    ENTERTAINMENT,
                    LIFE_STYLE,
                    MUSIC,
                    PREMIER,
                    TECH_SCIENCE,
            })
            @Retention(RetentionPolicy.SOURCE)
            public @interface Genre {}

            /** The genre for Family/Kids. */
            public static final String FAMILY_KIDS = "FAMILY_KIDS";

            /** The genre for Sports. */
            public static final String SPORTS = "SPORTS";

            /** The genre for Shopping. */
            public static final String SHOPPING = "SHOPPING";

            /** The genre for Movies. */
            public static final String MOVIES = "MOVIES";

            /** The genre for Comedy. */
            public static final String COMEDY = "COMEDY";

            /** The genre for Travel. */
            public static final String TRAVEL = "TRAVEL";

            /** The genre for Drama. */
            public static final String DRAMA = "DRAMA";

            /** The genre for Education. */
            public static final String EDUCATION = "EDUCATION";

            /** The genre for Animal/Wildlife. */
            public static final String ANIMAL_WILDLIFE = "ANIMAL_WILDLIFE";

            /** The genre for News. */
            public static final String NEWS = "NEWS";

            /** The genre for Gaming. */
            public static final String GAMING = "GAMING";

            /** The genre for Arts. */
            public static final String ARTS = "ARTS";

            /** The genre for Entertainment. */
            public static final String ENTERTAINMENT = "ENTERTAINMENT";

            /** The genre for Life Style. */
            public static final String LIFE_STYLE = "LIFE_STYLE";

            /** The genre for Music. */
            public static final String MUSIC = "MUSIC";

            /** The genre for Premier. */
            public static final String PREMIER = "PREMIER";

            /** The genre for Tech/Science. */
            public static final String TECH_SCIENCE = "TECH_SCIENCE";

            private static final ArraySet<String> CANONICAL_GENRES = new ArraySet<>();
            static {
                CANONICAL_GENRES.add(FAMILY_KIDS);
                CANONICAL_GENRES.add(SPORTS);
                CANONICAL_GENRES.add(SHOPPING);
                CANONICAL_GENRES.add(MOVIES);
                CANONICAL_GENRES.add(COMEDY);
                CANONICAL_GENRES.add(TRAVEL);
                CANONICAL_GENRES.add(DRAMA);
                CANONICAL_GENRES.add(EDUCATION);
                CANONICAL_GENRES.add(ANIMAL_WILDLIFE);
                CANONICAL_GENRES.add(NEWS);
                CANONICAL_GENRES.add(GAMING);
                CANONICAL_GENRES.add(ARTS);
                CANONICAL_GENRES.add(ENTERTAINMENT);
                CANONICAL_GENRES.add(LIFE_STYLE);
                CANONICAL_GENRES.add(MUSIC);
                CANONICAL_GENRES.add(PREMIER);
                CANONICAL_GENRES.add(TECH_SCIENCE);
            }

            private static final char DOUBLE_QUOTE = '"';
            private static final char COMMA = ',';
            private static final String DELIMITER = ",";

            private static final String[] EMPTY_STRING_ARRAY = new String[0];

            private Genres() {}

            /**
             * Encodes genre strings to a text that can be put into the database.
             *
             * @param genres Genre strings.
             * @return an encoded genre string that can be inserted into the
             *         {@link #COLUMN_BROADCAST_GENRE} or {@link #COLUMN_CANONICAL_GENRE} column.
             */
            public static String encode(@NonNull @Genre String... genres) {
                if (genres == null) {
                    // MNC and before will throw a NPE.
                    return null;
                }
                StringBuilder sb = new StringBuilder();
                String separator = "";
                for (String genre : genres) {
                    sb.append(separator).append(encodeToCsv(genre));
                    separator = DELIMITER;
                }
                return sb.toString();
            }

            private static String encodeToCsv(String genre) {
                StringBuilder sb = new StringBuilder();
                int length = genre.length();
                for (int i = 0; i < length; ++i) {
                    char c = genre.charAt(i);
                    switch (c) {
                        case DOUBLE_QUOTE:
                            sb.append(DOUBLE_QUOTE);
                            break;
                        case COMMA:
                            sb.append(DOUBLE_QUOTE);
                            break;
                    }
                    sb.append(c);
                }
                return sb.toString();
            }

            /**
             * Decodes the genre strings from the text stored in the database.
             *
             * @param genres The encoded genre string retrieved from the
             *            {@link #COLUMN_BROADCAST_GENRE} or {@link #COLUMN_CANONICAL_GENRE} column.
             * @return genre strings.
             */
            public static @Genre String[] decode(@NonNull String genres) {
                if (TextUtils.isEmpty(genres)) {
                    // MNC and before will throw a NPE for {@code null} genres.
                    return EMPTY_STRING_ARRAY;
                }
                if (genres.indexOf(COMMA) == -1 && genres.indexOf(DOUBLE_QUOTE) == -1) {
                    return new String[] {genres.trim()};
                }
                StringBuilder sb = new StringBuilder();
                List<String> results = new ArrayList<>();
                int length = genres.length();
                boolean escape = false;
                for (int i = 0; i < length; ++i) {
                    char c = genres.charAt(i);
                    switch (c) {
                        case DOUBLE_QUOTE:
                            if (!escape) {
                                escape = true;
                                continue;
                            }
                            break;
                        case COMMA:
                            if (!escape) {
                                String string = sb.toString().trim();
                                if (string.length() > 0) {
                                    results.add(string);
                                }
                                sb = new StringBuilder();
                                continue;
                            }
                            break;
                    }
                    sb.append(c);
                    escape = false;
                }
                String string = sb.toString().trim();
                if (string.length() > 0) {
                    results.add(string);
                }
                return results.toArray(new String[results.size()]);
            }

            /**
             * Returns whether a given text is a canonical genre defined in {@link Genres}.
             *
             * @param genre The name of genre to be checked.
             * @return {@code true} if the genre is canonical, otherwise {@code false}.
             */
            public static boolean isCanonical(String genre) {
                return CANONICAL_GENRES.contains(genre);
            }
        }
    }

    /**
     * Column definitions for the recorded TV programs table.
     *
     * <p>By default, the query results will be sorted by {@link #COLUMN_START_TIME_UTC_MILLIS} in
     * ascending order.
     */
    public static final class RecordedPrograms implements BaseTvColumns, ProgramColumns {

        /**
         * The content:// style URI for this table.
         *
         * <p>SQL selection is not supported for {@link ContentResolver#query},
         * {@link ContentResolver#update} and {@link ContentResolver#delete} operations.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/"
                + PATH_RECORDED_PROGRAM);

        /** The MIME type of a directory of recorded TV programs. */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/recorded_program";

        /** The MIME type of a single recorded TV program. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/recorded_program";

        /**
         * The ID of the TV channel that provides this recorded program.
         *
         * <p>This is a part of the channel URI and matches to {@link BaseColumns#_ID}.
         *
         * <p>Type: INTEGER (long)
         */
        public static final String COLUMN_CHANNEL_ID = "channel_id";

        /**
         * The ID of the TV input service that is associated with this recorded program.
         *
         * <p>Use {@link #buildInputId} to build the ID.
         *
         * <p>This is a required field.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_INPUT_ID = "input_id";

        /**
         * The start time of the original TV program, in milliseconds since the epoch.
         *
         * <p>Type: INTEGER (long)
         * @see Programs#COLUMN_START_TIME_UTC_MILLIS
         */
        public static final String COLUMN_START_TIME_UTC_MILLIS =
                Programs.COLUMN_START_TIME_UTC_MILLIS;

        /**
         * The end time of the original TV program, in milliseconds since the epoch.
         *
         * <p>Type: INTEGER (long)
         * @see Programs#COLUMN_END_TIME_UTC_MILLIS
         */
        public static final String COLUMN_END_TIME_UTC_MILLIS = Programs.COLUMN_END_TIME_UTC_MILLIS;

        /**
         * The comma-separated genre string of this recorded TV program.
         *
         * <p>Use the same language appeared in the underlying broadcast standard, if applicable.
         * (For example, one can refer to the genre strings used in Genre Descriptor of ATSC A/65 or
         * Content Descriptor of ETSI EN 300 468, if appropriate.) Otherwise, leave empty. Use
         * {@link Genres#encode Genres.encode()} to create a text that can be stored in this column.
         * Use {@link Genres#decode Genres.decode()} to get the broadcast genre strings from the
         * text stored in the column.
         *
         * <p>Type: TEXT
         * @see Programs#COLUMN_BROADCAST_GENRE
         */
        public static final String COLUMN_BROADCAST_GENRE = Programs.COLUMN_BROADCAST_GENRE;

        /**
         * The URI of the recording data for this recorded program.
         *
         * <p>Together with {@link #COLUMN_RECORDING_DATA_BYTES}, applications can use this
         * information to manage recording storage. The URI should indicate a file or directory with
         * the scheme {@link android.content.ContentResolver#SCHEME_FILE}.
         *
         * <p>Type: TEXT
         * @see #COLUMN_RECORDING_DATA_BYTES
         */
        public static final String COLUMN_RECORDING_DATA_URI = "recording_data_uri";

        /**
         * The data size (in bytes) for this recorded program.
         *
         * <p>Together with {@link #COLUMN_RECORDING_DATA_URI}, applications can use this
         * information to manage recording storage.
         *
         * <p>Type: INTEGER (long)
         * @see #COLUMN_RECORDING_DATA_URI
         */
        public static final String COLUMN_RECORDING_DATA_BYTES = "recording_data_bytes";

        /**
         * The duration (in milliseconds) of this recorded program.
         *
         * <p>The actual duration of the recorded program can differ from the one calculated by
         * {@link #COLUMN_END_TIME_UTC_MILLIS} - {@link #COLUMN_START_TIME_UTC_MILLIS} as program
         * recording can be interrupted in the middle for some reason, resulting in a partially
         * recorded program, which is still playable.
         *
         * <p>Type: INTEGER
         */
        public static final String COLUMN_RECORDING_DURATION_MILLIS = "recording_duration_millis";

        /**
         * The expiration time for this recorded program, in milliseconds since the epoch.
         *
         * <p>Recorded TV programs do not expire by default unless explicitly requested by the user
         * or the user allows applications to delete them in order to free up disk space for future
         * recording. However, some TV content can have expiration date set by the content provider
         * when recorded. This field is used to indicate such a restriction.
         *
         * <p>Can be empty.
         *
         * <p>Type: INTEGER (long)
         */
        public static final String COLUMN_RECORDING_EXPIRE_TIME_UTC_MILLIS =
                "recording_expire_time_utc_millis";

        /**
         * The comma-separated series IDs of this TV program for episodic TV shows.
         *
         * <p>This is used to indicate the series IDs.
         * Programs in the same series share a series ID.
         * Use this instead of {@link #COLUMN_SERIES_ID} if more than one series IDs
         * are assigned to the TV program.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_MULTI_SERIES_ID = "multi_series_id";

        /**
         * The internal ID used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_INTERNAL_PROVIDER_ID = "internal_provider_id";

        private RecordedPrograms() {}
    }

    /**
     * Column definitions for the preview TV programs table.
     */
    public static final class PreviewPrograms implements BaseTvColumns, ProgramColumns,
        PreviewProgramColumns {

        /**
         * The content:// style URI for this table.
         *
         * <p>SQL selection is not supported for {@link ContentResolver#query},
         * {@link ContentResolver#update} and {@link ContentResolver#delete} operations.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/"
                + PATH_PREVIEW_PROGRAM);

        /** The MIME type of a directory of preview TV programs. */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/preview_program";

        /** The MIME type of a single preview TV program. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/preview_program";

        /**
         * The ID of the TV channel that provides this TV program.
         *
         * <p>This value cannot be changed once it's set. Trying to modify it will make the update
         * fail.
         *
         * <p>This is a part of the channel URI and matches to {@link BaseColumns#_ID}.
         *
         * <p>This is a required field.
         *
         * <p>Type: INTEGER (long)
         */
        public static final String COLUMN_CHANNEL_ID = "channel_id";

        /**
         * The weight of the preview program within the channel.
         *
         * <p>The UI may choose to show this item in a different position in the channel row.
         * A larger weight value means the program is more important than other programs having
         * smaller weight values. The value is relevant for the preview programs in the same
         * channel. This is only relevant to {@link Channels#TYPE_PREVIEW}.
         *
         * <p>Can be empty.
         *
         * <p>Type: INTEGER
         */
        public static final String COLUMN_WEIGHT = "weight";

        private PreviewPrograms() {}
    }

    /**
     * Column definitions for the "watch next" TV programs table.
     */
    public static final class WatchNextPrograms implements BaseTvColumns, ProgramColumns,
        PreviewProgramColumns {

        /**
         * The content:// style URI for this table.
         *
         * <p>SQL selection is not supported for {@link ContentResolver#query},
         * {@link ContentResolver#update} and {@link ContentResolver#delete} operations.
         */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/"
                + PATH_WATCH_NEXT_PROGRAM);

        /** The MIME type of a directory of "watch next" TV programs. */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/watch_next_program";

        /** The MIME type of a single preview TV program. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/watch_next_program";

        /** @hide */
        @IntDef({
                WATCH_NEXT_TYPE_CONTINUE,
                WATCH_NEXT_TYPE_NEXT,
                WATCH_NEXT_TYPE_NEW,
                WATCH_NEXT_TYPE_WATCHLIST,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface WatchNextType {}

        /**
         * The watch next type for CONTINUE. Use this type when the user has already watched more
         * than 1 minute of this content.
         *
         * @see #COLUMN_WATCH_NEXT_TYPE
         */
        public static final int WATCH_NEXT_TYPE_CONTINUE = 0;

        /**
         * The watch next type for NEXT. Use this type when the user has watched one or more
         * complete episodes from some episodic content, but there remains more than one episode
         * remaining or there is one last episode remaining, but it is not “new” in that it was
         * released before the user started watching the show.
         *
         * @see #COLUMN_WATCH_NEXT_TYPE
         */
        public static final int WATCH_NEXT_TYPE_NEXT = 1;

        /**
         * The watch next type for NEW. Use this type when the user had watched all of the available
         * episodes from some episodic content, but a new episode became available since the user
         * started watching the first episode and now there is exactly one unwatched episode. This
         * could also work for recorded events in a series e.g. soccer matches or football games.
         *
         * @see #COLUMN_WATCH_NEXT_TYPE
         */
        public static final int WATCH_NEXT_TYPE_NEW = 2;

        /**
         * The watch next type for WATCHLIST. Use this type when the user has elected to explicitly
         * add a movie, event or series to a “watchlist” as a manual way of curating what they
         * want to watch next.
         *
         * @see #COLUMN_WATCH_NEXT_TYPE
         */
        public static final int WATCH_NEXT_TYPE_WATCHLIST = 3;

        /**
         * The "watch next" type of this program content.
         *
         * <p>The value should match one of the followings:
         * {@link #WATCH_NEXT_TYPE_CONTINUE},
         * {@link #WATCH_NEXT_TYPE_NEXT},
         * {@link #WATCH_NEXT_TYPE_NEW}, and
         * {@link #WATCH_NEXT_TYPE_WATCHLIST}.
         *
         * <p>This is a required field.
         *
         * <p>Type: INTEGER
         */
        public static final String COLUMN_WATCH_NEXT_TYPE = "watch_next_type";

        /**
         * The last UTC time that the user engaged in this TV program, in milliseconds since the
         * epoch. This is a hint for the application that is used for ordering of "watch next"
         * programs.
         *
         * <p>The meaning of the value varies depending on the {@link #COLUMN_WATCH_NEXT_TYPE}:
         * <ul>
         *     <li>{@link #WATCH_NEXT_TYPE_CONTINUE}: the date that the user was last watching the
         *     content.</li>
         *     <li>{@link #WATCH_NEXT_TYPE_NEXT}: the date of the last episode watched.</li>
         *     <li>{@link #WATCH_NEXT_TYPE_NEW}: the release date of the new episode.</li>
         *     <li>{@link #WATCH_NEXT_TYPE_WATCHLIST}: the date the item was added to the Watchlist.
         *     </li>
         * </ul>
         *
         * <p>This is a required field.
         *
         * <p>Type: INTEGER (long)
         */
        public static final String COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS =
                "last_engagement_time_utc_millis";

        private WatchNextPrograms() {}
    }

    /**
     * Column definitions for the TV programs that the user watched. Applications do not have access
     * to this table.
     *
     * <p>By default, the query results will be sorted by
     * {@link WatchedPrograms#COLUMN_WATCH_START_TIME_UTC_MILLIS} in descending order.
     * @hide
     */
    @SystemApi
    public static final class WatchedPrograms implements BaseTvColumns {

        /** The content:// style URI for this table. */
        public static final Uri CONTENT_URI =
                Uri.parse("content://" + AUTHORITY + "/watched_program");

        /** The MIME type of a directory of watched programs. */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/watched_program";

        /** The MIME type of a single item in this table. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/watched_program";

        /**
         * The UTC time that the user started watching this TV program, in milliseconds since the
         * epoch.
         *
         * <p>Type: INTEGER (long)
         */
        public static final String COLUMN_WATCH_START_TIME_UTC_MILLIS =
                "watch_start_time_utc_millis";

        /**
         * The UTC time that the user stopped watching this TV program, in milliseconds since the
         * epoch.
         *
         * <p>Type: INTEGER (long)
         */
        public static final String COLUMN_WATCH_END_TIME_UTC_MILLIS = "watch_end_time_utc_millis";

        /**
         * The ID of the TV channel that provides this TV program.
         *
         * <p>This is a required field.
         *
         * <p>Type: INTEGER (long)
         */
        public static final String COLUMN_CHANNEL_ID = "channel_id";

        /**
         * The title of this TV program.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_TITLE = "title";

        /**
         * The start time of this TV program, in milliseconds since the epoch.
         *
         * <p>Type: INTEGER (long)
         */
        public static final String COLUMN_START_TIME_UTC_MILLIS = "start_time_utc_millis";

        /**
         * The end time of this TV program, in milliseconds since the epoch.
         *
         * <p>Type: INTEGER (long)
         */
        public static final String COLUMN_END_TIME_UTC_MILLIS = "end_time_utc_millis";

        /**
         * The description of this TV program.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_DESCRIPTION = "description";

        /**
         * Extra parameters given to {@link TvInputService.Session#tune(Uri, android.os.Bundle)
         * TvInputService.Session.tune(Uri, android.os.Bundle)} when tuning to the channel that
         * provides this TV program. (Used internally.)
         *
         * <p>This column contains an encoded string that represents comma-separated key-value pairs of
         * the tune parameters. (Ex. "[key1]=[value1], [key2]=[value2]"). '%' is used as an escape
         * character for '%', '=', and ','.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_INTERNAL_TUNE_PARAMS = "tune_params";

        /**
         * The session token of this TV program. (Used internally.)
         *
         * <p>This contains a String representation of {@link IBinder} for
         * {@link TvInputService.Session} that provides the current TV program. It is used
         * internally to distinguish watched programs entries from different TV input sessions.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_INTERNAL_SESSION_TOKEN = "session_token";

        private WatchedPrograms() {}
    }
}
