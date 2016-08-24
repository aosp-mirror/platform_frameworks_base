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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.ArraySet;

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
    private static final String PATH_PASSTHROUGH = "passthrough";

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
     * A optional query, update or delete URI parameter that allows the caller to specify canonical
     * genre to filter programs.
     * @hide
     */
    public static final String PARAM_CANONICAL_GENRE = "canonical_genre";

    /**
     * Builds an ID that uniquely identifies a TV input service.
     *
     * @param name The {@link ComponentName} of the TV input service to build ID for.
     * @return the ID for the given TV input service.
     */
    public static final String buildInputId(ComponentName name) {
        return name.flattenToShortString();
    }

    /**
     * Builds a URI that points to a specific channel.
     *
     * @param channelId The ID of the channel to point to.
     */
    public static final Uri buildChannelUri(long channelId) {
        return ContentUris.withAppendedId(Channels.CONTENT_URI, channelId);
    }

    /**
     * Build a special channel URI intended to be used with pass-through inputs. (e.g. HDMI)
     *
     * @param inputId The ID of the pass-through input to build a channels URI for.
     * @see TvInputInfo#isPassthroughInput()
     */
    public static final Uri buildChannelUriForPassthroughInput(String inputId) {
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT).authority(AUTHORITY)
                .appendPath(PATH_PASSTHROUGH).appendPath(inputId).build();
    }

    /**
     * Builds a URI that points to a channel logo. See {@link Channels.Logo}.
     *
     * @param channelId The ID of the channel whose logo is pointed to.
     */
    public static final Uri buildChannelLogoUri(long channelId) {
        return buildChannelLogoUri(buildChannelUri(channelId));
    }

    /**
     * Builds a URI that points to a channel logo. See {@link Channels.Logo}.
     *
     * @param channelUri The URI of the channel whose logo is pointed to.
     */
    public static final Uri buildChannelLogoUri(Uri channelUri) {
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
    public static final Uri buildChannelsUriForInput(@Nullable String inputId) {
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
    public static final Uri buildChannelsUriForInput(@Nullable String inputId,
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
    public static final Uri buildChannelsUriForInput(@Nullable String inputId,
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
    public static final Uri buildProgramUri(long programId) {
        return ContentUris.withAppendedId(Programs.CONTENT_URI, programId);
    }

    /**
     * Builds a URI that points to all programs on a given channel.
     *
     * @param channelId The ID of the channel to return programs for.
     */
    public static final Uri buildProgramsUriForChannel(long channelId) {
        return Programs.CONTENT_URI.buildUpon()
                .appendQueryParameter(PARAM_CHANNEL, String.valueOf(channelId)).build();
    }

    /**
     * Builds a URI that points to all programs on a given channel.
     *
     * @param channelUri The URI of the channel to return programs for.
     */
    public static final Uri buildProgramsUriForChannel(Uri channelUri) {
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
     * @param startTime The start time used to filter programs. The returned programs should have
     *            {@link Programs#COLUMN_END_TIME_UTC_MILLIS} that is greater than this time.
     * @param endTime The end time used to filter programs. The returned programs should have
     *            {@link Programs#COLUMN_START_TIME_UTC_MILLIS} that is less than this time.
     */
    public static final Uri buildProgramsUriForChannel(long channelId, long startTime,
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
    public static final Uri buildProgramsUriForChannel(Uri channelUri, long startTime,
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
    public static final Uri buildRecordedProgramUri(long recordedProgramId) {
        return ContentUris.withAppendedId(RecordedPrograms.CONTENT_URI, recordedProgramId);
    }

    /**
     * Builds a URI that points to a specific program the user watched.
     *
     * @param watchedProgramId The ID of the watched program to point to.
     * @hide
     */
    public static final Uri buildWatchedProgramUri(long watchedProgramId) {
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
     * Returns {@code true}, if {@code uri} is a channel URI.
     */
    public static final boolean isChannelUri(Uri uri) {
        return isChannelUriForTunerInput(uri) || isChannelUriForPassthroughInput(uri);
    }

    /**
     * Returns {@code true}, if {@code uri} is a channel URI for a tuner input.
     */
    public static final boolean isChannelUriForTunerInput(Uri uri) {
        return isTvUri(uri) && isTwoSegmentUriStartingWith(uri, PATH_CHANNEL);
    }

    /**
     * Returns {@code true}, if {@code uri} is a channel URI for a pass-through input.
     */
    public static final boolean isChannelUriForPassthroughInput(Uri uri) {
        return isTvUri(uri) && isTwoSegmentUriStartingWith(uri, PATH_PASSTHROUGH);
    }

    /**
     * Returns {@code true}, if {@code uri} is a program URI.
     */
    public static final boolean isProgramUri(Uri uri) {
        return isTvUri(uri) && isTwoSegmentUriStartingWith(uri, PATH_PROGRAM);
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

    /** Column definitions for the TV channels table. */
    public static final class Channels implements BaseTvColumns {

        /** The content:// style URI for this table. */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/"
                + PATH_CHANNEL);

        /** The MIME type of a directory of TV channels. */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/channel";

        /** The MIME type of a single TV channel. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/channel";

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

        /** A generic service type. */
        public static final String SERVICE_TYPE_OTHER = "SERVICE_TYPE_OTHER";

        /** The service type for regular TV channels that have both audio and video. */
        public static final String SERVICE_TYPE_AUDIO_VIDEO = "SERVICE_TYPE_AUDIO_VIDEO";

        /** The service type for radio channels that have audio only. */
        public static final String SERVICE_TYPE_AUDIO = "SERVICE_TYPE_AUDIO";

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
        public static final String getVideoResolution(String videoFormat) {
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
         * default channel type. The value should match to one of the followings:
         * {@link #TYPE_1SEG},
         * {@link #TYPE_ATSC_C},
         * {@link #TYPE_ATSC_M_H},
         * {@link #TYPE_ATSC_T},
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
         * {@link #TYPE_ISDB_T},
         * {@link #TYPE_ISDB_TB},
         * {@link #TYPE_NTSC},
         * {@link #TYPE_OTHER},
         * {@link #TYPE_PAL},
         * {@link #TYPE_SECAM},
         * {@link #TYPE_S_DMB}, and
         * {@link #TYPE_T_DMB}.
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
         * <p>A value of 1 indicates the channel is included in the channel list that applications
         * use to browse channels, a value of 0 indicates the channel is not included in the list.
         * If not specified, this value is set to 0 (not browsable) by default.
         *
         * <p>Type: INTEGER (boolean)
         * @hide
         */
        @SystemApi
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
         * <p>Type: INTEGER (boolean)
         * @hide
         */
        @SystemApi
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
    public static final class Programs implements BaseTvColumns {

        /** The content:// style URI for this table. */
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
         * The title of this TV program.
         *
         * <p>If this program is an episodic TV show, it is recommended that the title is the series
         * title and its related fields ({@link #COLUMN_SEASON_TITLE} and/or
         * {@link #COLUMN_SEASON_DISPLAY_NUMBER}, {@link #COLUMN_SEASON_DISPLAY_NUMBER},
         * {@link #COLUMN_EPISODE_DISPLAY_NUMBER}, and {@link #COLUMN_EPISODE_TITLE}) are filled in.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_TITLE = "title";

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
         * The season display number of this TV program for episodic TV shows.
         *
         * <p>This is used to indicate the season number. (e.g. 1, 2 or 3) Note that the value
         * does not necessarily be numeric. (e.g. 12B)
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_SEASON_DISPLAY_NUMBER = "season_display_number";

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
        public static final String COLUMN_SEASON_TITLE = "season_title";

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
         * The episode display number of this TV program for episodic TV shows.
         *
         * <p>This is used to indicate the episode number. (e.g. 1, 2 or 3) Note that the value
         * does not necessarily be numeric. (e.g. 12B)
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_EPISODE_DISPLAY_NUMBER = "episode_display_number";

        /**
         * The episode title of this TV program for episodic TV shows.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_EPISODE_TITLE = "episode_title";

        /**
         * The start time of this TV program, in milliseconds since the epoch.
         *
         * <p>The value should be equal to or larger than {@link #COLUMN_END_TIME_UTC_MILLIS} of the
         * previous program in the same channel. In practice, start time will usually be the end
         * time of the previous program.
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
        public static final String COLUMN_CANONICAL_GENRE = "canonical_genre";

        /**
         * The short description of this TV program that is displayed to the user by default.
         *
         * <p>It is recommended to limit the length of the descriptions to 256 characters.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_SHORT_DESCRIPTION = "short_description";

        /**
         * The detailed, lengthy description of this TV program that is displayed only when the user
         * wants to see more information.
         *
         * <p>TV input services should leave this field empty if they have no additional details
         * beyond {@link #COLUMN_SHORT_DESCRIPTION}.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_LONG_DESCRIPTION = "long_description";

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
        public static final String COLUMN_VIDEO_WIDTH = "video_width";

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
        public static final String COLUMN_VIDEO_HEIGHT = "video_height";

        /**
         * The comma-separated audio languages of this TV program.
         *
         * <p>This is used to describe available audio languages included in the program. Use either
         * ISO 639-1 or 639-2/T codes.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_AUDIO_LANGUAGE = "audio_language";

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
        public static final String COLUMN_CONTENT_RATING = "content_rating";

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
        public static final String COLUMN_POSTER_ART_URI = "poster_art_uri";

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
        public static final String COLUMN_THUMBNAIL_URI = "thumbnail_uri";

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
        public static final String COLUMN_SEARCHABLE = "searchable";

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
         * {@code version_number} in ETSI EN 300 468 or ATSC A/65, if the data are coming from a TV
         * broadcast.
         *
         * <p>Type: INTEGER
         */
        public static final String COLUMN_VERSION_NUMBER = "version_number";

        private Programs() {}

        /** Canonical genres for TV programs. */
        public static final class Genres {
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
            public static String encode(@NonNull String... genres) {
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
            public static String[] decode(@NonNull String genres) {
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
    public static final class RecordedPrograms implements BaseTvColumns {

        /** The content:// style URI for this table. */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/"
                + PATH_RECORDED_PROGRAM);

        /** The MIME type of a directory of recorded TV programs. */
        public static final String CONTENT_TYPE = "vnd.android.cursor.dir/recorded_program";

        /** The MIME type of a single recorded TV program. */
        public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/recorded_program";

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
         * The ID of the TV channel that provided this recorded TV program.
         *
         * <p>This is a part of the channel URI and matches to {@link BaseColumns#_ID}.
         *
         * <p>This is a required field.
         *
         * <p>Type: INTEGER (long)
         * @see Programs#COLUMN_CHANNEL_ID
         */
        public static final String COLUMN_CHANNEL_ID = Programs.COLUMN_CHANNEL_ID;

        /**
         * The title of this recorded TV program.
         *
         * <p>If this recorded program is an episodic TV show, it is recommended that the title is
         * the series title and its related fields ({@link #COLUMN_SEASON_TITLE} and/or
         * {@link #COLUMN_SEASON_DISPLAY_NUMBER}, {@link #COLUMN_EPISODE_DISPLAY_NUMBER},
         * and {@link #COLUMN_EPISODE_TITLE}) are filled in.
         *
         * <p>Type: TEXT
         * @see Programs#COLUMN_TITLE
         */
        public static final String COLUMN_TITLE = Programs.COLUMN_TITLE;

        /**
         * The season display number of this recorded TV program for episodic TV shows.
         *
         * <p>This is used to indicate the season number. (e.g. 1, 2 or 3) Note that the value
         * does not necessarily be numeric. (e.g. 12B)
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_SEASON_DISPLAY_NUMBER =
                Programs.COLUMN_SEASON_DISPLAY_NUMBER;

        /**
         * The title of the season for this recorded TV program for episodic TV shows.
         *
         * <p>This is an optional field supplied only when the season has a special title
         * (e.g. The Final Season). If provided, the applications should display it instead of
         * {@link #COLUMN_SEASON_DISPLAY_NUMBER} without alterations.
         * (e.g. for "The Final Season", displayed string should be "The Final Season", not
         * "Season The Final Season"). When displaying multiple programs, the order should be based
         * on {@link #COLUMN_SEASON_DISPLAY_NUMBER}, even when {@link #COLUMN_SEASON_TITLE} exists.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_SEASON_TITLE = Programs.COLUMN_SEASON_TITLE;

        /**
         * The episode display number of this recorded TV program for episodic TV shows.
         *
         * <p>This is used to indicate the episode number. (e.g. 1, 2 or 3) Note that the value
         * does not necessarily be numeric. (e.g. 12B)
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         */
        public static final String COLUMN_EPISODE_DISPLAY_NUMBER =
                Programs.COLUMN_EPISODE_DISPLAY_NUMBER;

        /**
         * The episode title of this recorded TV program for episodic TV shows.
         *
         * <p>Can be empty.
         *
         * <p>Type: TEXT
         * @see Programs#COLUMN_EPISODE_TITLE
         */
        public static final String COLUMN_EPISODE_TITLE = Programs.COLUMN_EPISODE_TITLE;

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
         * The comma-separated canonical genre string of this recorded TV program.
         *
         * <p>Canonical genres are defined in {@link Programs.Genres}. Use
         * {@link Programs.Genres#encode Genres.encode()} to create a text that can be stored in
         * this column. Use {@link Programs.Genres#decode Genres.decode()} to get the canonical
         * genre strings from the text stored in the column.
         *
         * <p>Type: TEXT
         * @see Programs#COLUMN_CANONICAL_GENRE
         * @see Programs.Genres
         */
        public static final String COLUMN_CANONICAL_GENRE = Programs.COLUMN_CANONICAL_GENRE;

        /**
         * The short description of this recorded TV program that is displayed to the user by
         * default.
         *
         * <p>It is recommended to limit the length of the descriptions to 256 characters.
         *
         * <p>Type: TEXT
         * @see Programs#COLUMN_SHORT_DESCRIPTION
         */
        public static final String COLUMN_SHORT_DESCRIPTION = Programs.COLUMN_SHORT_DESCRIPTION;

        /**
         * The detailed, lengthy description of this recorded TV program that is displayed only when
         * the user wants to see more information.
         *
         * <p>TV input services should leave this field empty if they have no additional details
         * beyond {@link #COLUMN_SHORT_DESCRIPTION}.
         *
         * <p>Type: TEXT
         * @see Programs#COLUMN_LONG_DESCRIPTION
         */
        public static final String COLUMN_LONG_DESCRIPTION = Programs.COLUMN_LONG_DESCRIPTION;

        /**
         * The width of the video for this recorded TV program, in the unit of pixels.
         *
         * <p>Together with {@link #COLUMN_VIDEO_HEIGHT} this is used to determine the video
         * resolution of the current recorded TV program. Can be empty if it is not known or the
         * recorded program does not convey any video.
         *
         * <p>Type: INTEGER
         * @see Programs#COLUMN_VIDEO_WIDTH
         */
        public static final String COLUMN_VIDEO_WIDTH = Programs.COLUMN_VIDEO_WIDTH;

        /**
         * The height of the video for this recorded TV program, in the unit of pixels.
         *
         * <p>Together with {@link #COLUMN_VIDEO_WIDTH} this is used to determine the video
         * resolution of the current recorded TV program. Can be empty if it is not known or the
         * recorded program does not convey any video.
         *
         * <p>Type: INTEGER
         * @see Programs#COLUMN_VIDEO_HEIGHT
         */
        public static final String COLUMN_VIDEO_HEIGHT = Programs.COLUMN_VIDEO_HEIGHT;

        /**
         * The comma-separated audio languages of this recorded TV program.
         *
         * <p>This is used to describe available audio languages included in the recorded program.
         * Use either ISO 639-1 or 639-2/T codes.
         *
         * <p>Type: TEXT
         * @see Programs#COLUMN_AUDIO_LANGUAGE
         */
        public static final String COLUMN_AUDIO_LANGUAGE = Programs.COLUMN_AUDIO_LANGUAGE;

        /**
         * The comma-separated content ratings of this recorded TV program.
         *
         * <p>This is used to describe the content rating(s) of this recorded program. Each
         * comma-separated content rating sub-string should be generated by calling
         * {@link TvContentRating#flattenToString}. Note that in most cases the recorded program
         * content is rated by a single rating system, thus resulting in a corresponding single
         * sub-string that does not require comma separation and multiple sub-strings appear only
         * when the recorded program content is rated by two or more content rating systems. If any
         * of those ratings is specified as "blocked rating" in the user's parental control
         * settings, the TV input service should block the current content and wait for the signal
         * that it is okay to unblock.
         *
         * <p>Type: TEXT
         * @see Programs#COLUMN_CONTENT_RATING
         */
        public static final String COLUMN_CONTENT_RATING = Programs.COLUMN_CONTENT_RATING;

        /**
         * The URI for the poster art of this recorded TV program.
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
         * @see Programs#COLUMN_POSTER_ART_URI
         */
        public static final String COLUMN_POSTER_ART_URI = Programs.COLUMN_POSTER_ART_URI;

        /**
         * The URI for the thumbnail of this recorded TV program.
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
         * @see Programs#COLUMN_THUMBNAIL_URI
         */
        public static final String COLUMN_THUMBNAIL_URI = Programs.COLUMN_THUMBNAIL_URI;

        /**
         * The flag indicating whether this recorded TV program is searchable or not.
         *
         * <p>The columns of searchable recorded programs can be read by other applications that
         * have proper permission. Care must be taken not to open sensitive data.
         *
         * <p>A value of 1 indicates that the recorded program is searchable and its columns can be
         * read by other applications, a value of 0 indicates that the recorded program is hidden
         * and its columns can be read only by the package that owns the recorded program and the
         * system. If not specified, this value is set to 1 (searchable) by default.
         *
         * <p>Type: INTEGER (boolean)
         * @see Programs#COLUMN_SEARCHABLE
         */
        public static final String COLUMN_SEARCHABLE = Programs.COLUMN_SEARCHABLE;

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
         * Internal data used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: BLOB
         * @see Programs#COLUMN_INTERNAL_PROVIDER_DATA
         */
        public static final String COLUMN_INTERNAL_PROVIDER_DATA =
                Programs.COLUMN_INTERNAL_PROVIDER_DATA;

        /**
         * Internal integer flag used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: INTEGER
         * @see Programs#COLUMN_INTERNAL_PROVIDER_FLAG1
         */
        public static final String COLUMN_INTERNAL_PROVIDER_FLAG1 =
                Programs.COLUMN_INTERNAL_PROVIDER_FLAG1;

        /**
         * Internal integer flag used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: INTEGER
         * @see Programs#COLUMN_INTERNAL_PROVIDER_FLAG2
         */
        public static final String COLUMN_INTERNAL_PROVIDER_FLAG2 =
                Programs.COLUMN_INTERNAL_PROVIDER_FLAG2;

        /**
         * Internal integer flag used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: INTEGER
         * @see Programs#COLUMN_INTERNAL_PROVIDER_FLAG3
         */
        public static final String COLUMN_INTERNAL_PROVIDER_FLAG3 =
                Programs.COLUMN_INTERNAL_PROVIDER_FLAG3;

        /**
         * Internal integer flag used by individual TV input services.
         *
         * <p>This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         *
         * <p>Type: INTEGER
         * @see Programs#COLUMN_INTERNAL_PROVIDER_FLAG4
         */
        public static final String COLUMN_INTERNAL_PROVIDER_FLAG4 =
                Programs.COLUMN_INTERNAL_PROVIDER_FLAG4;

        /**
         * The version number of this row entry used by TV input services.
         *
         * <p>This is best used by sync adapters to identify the rows to update. The number can be
         * defined by individual TV input services. One may assign the same value as
         * {@code version_number} in ETSI EN 300 468 or ATSC A/65, if the data are coming from a TV
         * broadcast.
         *
         * <p>Type: INTEGER
         * @see Programs#COLUMN_VERSION_NUMBER
         */
        public static final String COLUMN_VERSION_NUMBER = Programs.COLUMN_VERSION_NUMBER;

        private RecordedPrograms() {}
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
