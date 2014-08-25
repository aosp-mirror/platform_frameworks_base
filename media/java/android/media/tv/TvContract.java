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

import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.net.Uri;
import android.os.IBinder;
import android.provider.BaseColumns;
import android.util.ArraySet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * The contract between the TV provider and applications. Contains definitions for the supported
 * URIs and columns.
 * </p>
 * <h3>Overview</h3>
 * <p>
 * TvContract defines a basic database of TV content metadata such as channel and program
 * information. The information is stored in {@link Channels} and {@link Programs} tables.
 * </p>
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

    private static final String PATH_CHANNEL = "channel";
    private static final String PATH_PROGRAM = "program";
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
    public static final Uri buildChannelsUriForInput(String inputId) {
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
    public static final Uri buildChannelsUriForInput(String inputId, boolean browsableOnly) {
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
    public static final Uri buildChannelsUriForInput(String inputId, String genre,
            boolean browsableOnly) {
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
     * Builds a URI that points to a specific program the user watched.
     *
     * @param watchedProgramId The ID of the watched program to point to.
     * @hide
     */
    public static final Uri buildWatchedProgramUri(long watchedProgramId) {
        return ContentUris.withAppendedId(WatchedPrograms.CONTENT_URI, watchedProgramId);
    }

    private static final boolean isTvUri(Uri uri) {
        return uri != null && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                && AUTHORITY.equals(uri.getAuthority());
    }

    private static final boolean isTwoSegmentUriStartingWith(Uri uri, String pathSegment) {
        List<String> pathSegments = uri.getPathSegments();
        return pathSegments.size() == 2 && pathSegment.equals(pathSegments.get(0));
    }

    /**
     * Returns true, if {@code uri} is a channel URI.
     * @hide
     */
    public static final boolean isChannelUri(Uri uri) {
        return isChannelUriForTunerInput(uri) || isChannelUriForPassthroughInput(uri);
    }

    /**
     * Returns true, if {@code uri} is a channel URI for a tuner input.
     * @hide
     */
    public static final boolean isChannelUriForTunerInput(Uri uri) {
        return isTvUri(uri) && isTwoSegmentUriStartingWith(uri, PATH_CHANNEL);
    }

    /**
     * Returns true, if {@code uri} is a channel URI for a passthrough input.
     * @hide
     */
    @SystemApi
    public static final boolean isChannelUriForPassthroughInput(Uri uri) {
        return isTvUri(uri) && isTwoSegmentUriStartingWith(uri, PATH_PASSTHROUGH);
    }

    /**
     * Returns true, if {@code uri} is a program URI.
     * @hide
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
         * The name of the package that owns a row in each table.
         * <p>
         * The TV provider fills it in with the name of the package that provides the initial data
         * of that row. If the package is later uninstalled, the rows it owns are automatically
         * removed from the tables.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_PACKAGE_NAME = "package_name";
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

        /** A generic channel type. */
        public static final String TYPE_OTHER = "TYPE_OTHER";

        /** The channel type for NTSC. */
        public static final String TYPE_NTSC = "TYPE_NTSC";

        /** The channel type for PAL. */
        public static final String TYPE_PAL = "TYPE_PAL";

        /** The channel type for SECAM. */
        public static final String TYPE_SECAM = "TYPE_SECAM";

        /** The channel type for DVB-T (terrestrial). */
        public static final String TYPE_DVB_T = "TYPE_DVB_T";

        /** The channel type for DVB-T2 (terrestrial). */
        public static final String TYPE_DVB_T2 = "TYPE_DVB_T2";

        /** The channel type for DVB-S (satellite). */
        public static final String TYPE_DVB_S = "TYPE_DVB_S";

        /** The channel type for DVB-S2 (satellite). */
        public static final String TYPE_DVB_S2 = "TYPE_DVB_S2";

        /** The channel type for DVB-C (cable). */
        public static final String TYPE_DVB_C = "TYPE_DVB_C";

        /** The channel type for DVB-C2 (cable). */
        public static final String TYPE_DVB_C2 = "TYPE_DVB_C2";

        /** The channel type for DVB-H (handheld). */
        public static final String TYPE_DVB_H = "TYPE_DVB_H";

        /** The channel type for DVB-SH (satellite). */
        public static final String TYPE_DVB_SH = "TYPE_DVB_SH";

        /** The channel type for ATSC (terrestrial). */
        public static final String TYPE_ATSC_T = "TYPE_ATSC_T";

        /** The channel type for ATSC (cable). */
        public static final String TYPE_ATSC_C = "TYPE_ATSC_C";

        /** The channel type for ATSC-M/H (mobile/handheld). */
        public static final String TYPE_ATSC_M_H = "TYPE_ATSC_M_H";

        /** The channel type for ISDB-T (terrestrial). */
        public static final String TYPE_ISDB_T = "TYPE_ISDB_T";

        /** The channel type for ISDB-Tb (Brazil). */
        public static final String TYPE_ISDB_TB = "TYPE_ISDB_TB";

        /** The channel type for ISDB-S (satellite). */
        public static final String TYPE_ISDB_S = "TYPE_ISDB_S";

        /** The channel type for ISDB-C (cable). */
        public static final String TYPE_ISDB_C = "TYPE_ISDB_C";

        /** The channel type for 1seg (handheld). */
        public static final String TYPE_1SEG = "TYPE_1SEG";

        /** The channel type for DTMB (terrestrial). */
        public static final String TYPE_DTMB = "TYPE_DTMB";

        /** The channel type for CMMB (handheld). */
        public static final String TYPE_CMMB = "TYPE_CMMB";

        /** The channel type for T-DMB (terrestrial). */
        public static final String TYPE_T_DMB = "TYPE_T_DMB";

        /** The channel type for S-DMB (satellite). */
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

        private static final Map<String, String> VIDEO_FORMAT_TO_RESOLUTION_MAP =
                new HashMap<String, String>();

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
        public static final String getVideoResolution(String videoFormat) {
            return VIDEO_FORMAT_TO_RESOLUTION_MAP.get(videoFormat);
        }

        /**
         * The ID of the TV input service that provides this TV channel.
         * <p>
         * Use {@link #buildInputId} to build the ID.
         * </p><p>
         * This is a required field.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_INPUT_ID = "input_id";

        /**
         * The predefined type of this TV channel.
         * <p>
         * This is primarily used to indicate which broadcast standard (e.g. ATSC, DVB or ISDB) the
         * current channel conforms to. The value should match to one of the followings:
         * {@link #TYPE_OTHER}, {@link #TYPE_DVB_T}, {@link #TYPE_DVB_T2}, {@link #TYPE_DVB_S},
         * {@link #TYPE_DVB_S2}, {@link #TYPE_DVB_C}, {@link #TYPE_DVB_C2}, {@link #TYPE_DVB_H},
         * {@link #TYPE_DVB_SH}, {@link #TYPE_ATSC_T}, {@link #TYPE_ATSC_C},
         * {@link #TYPE_ATSC_M_H}, {@link #TYPE_ISDB_T}, {@link #TYPE_ISDB_TB},
         * {@link #TYPE_ISDB_S}, {@link #TYPE_ISDB_C}, {@link #TYPE_1SEG}, {@link #TYPE_DTMB},
         * {@link #TYPE_CMMB}, {@link #TYPE_T_DMB}, {@link #TYPE_S_DMB}
         * </p><p>
         * This is a required field.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_TYPE = "type";

        /**
         * The predefined service type of this TV channel.
         * <p>
         * This is primarily used to indicate whether the current channel is a regular TV channel or
         * a radio-like channel. Use the same coding for {@code service_type} in the underlying
         * broadcast standard if it is defined there (e.g. ATSC A/53, ETSI EN 300 468 and ARIB
         * STD-B10). Otherwise use one of the followings: {@link #SERVICE_TYPE_OTHER},
         * {@link #SERVICE_TYPE_AUDIO_VIDEO}, {@link #SERVICE_TYPE_AUDIO}
         * </p><p>
         * This is a required field.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_SERVICE_TYPE = "service_type";

        /**
         * The original network ID of this TV channel.
         * <p>
         * This is used to identify the originating delivery system, if applicable. Use the same
         * coding for {@code original_network_id} in the underlying broadcast standard if it is
         * defined there (e.g. ETSI EN 300 468/TR 101 211 and ARIB STD-B10). If channels cannot be
         * globally identified by 2-tuple {{@link #COLUMN_TRANSPORT_STREAM_ID},
         * {@link #COLUMN_SERVICE_ID}}, one must carefully assign a value to this field to form a
         * unique 3-tuple identification {{@link #COLUMN_ORIGINAL_NETWORK_ID},
         * {@link #COLUMN_TRANSPORT_STREAM_ID}, {@link #COLUMN_SERVICE_ID}} for its channels.
         * </p><p>
         * This is a required field if the channel cannot be uniquely identified by a 2-tuple
         * {{@link #COLUMN_TRANSPORT_STREAM_ID}, {@link #COLUMN_SERVICE_ID}}.
         * </p><p>
         * Type: INTEGER
         * </p>
         */
        public static final String COLUMN_ORIGINAL_NETWORK_ID = "original_network_id";

        /**
         * The transport stream ID of this channel.
         * <p>
         * This is used to identify the Transport Stream that contains the current channel from any
         * other multiplex within a network, if applicable. Use the same coding for
         * {@code transport_stream_id} defined in ISO/IEC 13818-1 if the channel is transmitted via
         * the MPEG Transport Stream as is the case for many digital broadcast standards.
         * </p><p>
         * This is a required field if the current channel is transmitted via the MPEG Transport
         * Stream.
         * </p><p>
         * Type: INTEGER
         * </p>
         */
        public static final String COLUMN_TRANSPORT_STREAM_ID = "transport_stream_id";

        /**
         * The service ID of this channel.
         * <p>
         * This is used to identify the current service (roughly equivalent to channel) from any
         * other service within the Transport Stream, if applicable. Use the same coding for
         * {@code service_id} in the underlying broadcast standard if it is defined there (e.g. ETSI
         * EN 300 468 and ARIB STD-B10) or {@code program_number} (which usually has the same value
         * as {@code service_id}) in ISO/IEC 13818-1 if the channel is transmitted via the MPEG
         * Transport Stream.
         * </p><p>
         * This is a required field if the current channel is transmitted via the MPEG Transport
         * Stream.
         * </p><p>
         * Type: INTEGER
         * </p>
         */
        public static final String COLUMN_SERVICE_ID = "service_id";

        /**
         * The channel number that is displayed to the user.
         * <p>
         * The format can vary depending on broadcast standard and product specification.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_DISPLAY_NUMBER = "display_number";

        /**
         * The channel name that is displayed to the user.
         * <p>
         * A call sign is a good candidate to use for this purpose but any name that helps the user
         * recognize the current channel will be enough. Can also be empty depending on broadcast
         * standard.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_DISPLAY_NAME = "display_name";

        /**
         * The network affiliation for this TV channel.
         * <p>
         * This is used to identify a channel that is commonly called by its network affiliation
         * instead of the display name. Examples include ABC for the channel KGO-HD, FOX for the
         * channel KTVU-HD and NBC for the channel KNTV-HD. Can be empty if not applicable.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_NETWORK_AFFILIATION = "network_affiliation";

        /**
         * The description of this TV channel.
         * <p>
         * Can be empty initially.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_DESCRIPTION = "description";

        /**
         * The typical video format for programs from this TV channel.
         * <p>
         * This is primarily used to filter out channels based on video format by applications. The
         * value should match one of the followings: {@link #VIDEO_FORMAT_240P},
         * {@link #VIDEO_FORMAT_360P}, {@link #VIDEO_FORMAT_480I}, {@link #VIDEO_FORMAT_480P},
         * {@link #VIDEO_FORMAT_576I}, {@link #VIDEO_FORMAT_576P}, {@link #VIDEO_FORMAT_720P},
         * {@link #VIDEO_FORMAT_1080I}, {@link #VIDEO_FORMAT_1080P}, {@link #VIDEO_FORMAT_2160P},
         * {@link #VIDEO_FORMAT_4320P}. Note that the actual video resolution of each program from a
         * given channel can vary thus one should use {@link Programs#COLUMN_VIDEO_WIDTH} and
         * {@link Programs#COLUMN_VIDEO_HEIGHT} to get more accurate video resolution.
         * </p><p>
         * Type: TEXT
         * </p>
         * @see #getVideoResolution
         */
        public static final String COLUMN_VIDEO_FORMAT = "video_format";

        /**
         * The flag indicating whether this TV channel is browsable or not.
         * <p>
         * A value of 1 indicates the channel is included in the channel list that applications use
         * to browse channels, a value of 0 indicates the channel is not included in the list. If
         * not specified, this value is set to 1 (browsable) by default.
         * </p><p>
         * Type: INTEGER (boolean)
         * </p>
         * @hide
         */
        @SystemApi
        public static final String COLUMN_BROWSABLE = "browsable";

        /**
         * The flag indicating whether this TV channel is searchable or not.
         * <p>
         * In some regions, it is not allowed to surface search results for a given channel without
         * broadcaster's consent. This is used to impose such restriction. Channels marked with
         * "not searchable" cannot be used by other services except for the system service that
         * shows the TV content. A value of 1 indicates the channel is searchable and can be
         * included in search results, a value of 0 indicates the channel and its TV programs are
         * hidden from search. If not specified, this value is set to 1 (searchable) by default.
         * </p><p>
         * Type: INTEGER (boolean)
         * </p>
         */
        public static final String COLUMN_SEARCHABLE = "searchable";

        /**
         * The flag indicating whether this TV channel is locked or not.
         * <p>
         * This is primarily used for alternative parental control to prevent unauthorized users
         * from watching the current channel regardless of the content rating. A value of 1
         * indicates the channel is locked and the user is required to enter passcode to unlock it
         * in order to watch the current program from the channel, a value of 0 indicates the
         * channel is not locked thus the user is not prompted to enter passcode If not specified,
         * this value is set to 0 (not locked) by default.
         * </p><p>
         * Type: INTEGER (boolean)
         * </p>
         * @hide
         */
        @SystemApi
        public static final String COLUMN_LOCKED = "locked";

        /**
         * Internal data used by individual TV input services.
         * <p>
         * This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         * </p><p>
         * Type: BLOB
         * </p>
         */
        public static final String COLUMN_INTERNAL_PROVIDER_DATA = "internal_provider_data";

        /**
         * The version number of this row entry used by TV input services.
         * <p>
         * This is best used by sync adapters to identify the rows to update. The number can be
         * defined by individual TV input services. One may assign the same value as
         * {@code version_number} that appears in ETSI EN 300 468 or ATSC A/65, if the data are
         * coming from a TV broadcast.
         * </p><p>
         * Type: INTEGER
         * </p>
         */
        public static final String COLUMN_VERSION_NUMBER = "version_number";

        private Channels() {}

        /**
         * A sub-directory of a single TV channel that represents its primary logo.
         * <p>
         * To access this directory, append {@link Channels.Logo#CONTENT_DIRECTORY} to the raw
         * channel URI.  The resulting URI represents an image file, and should be interacted
         * using ContentResolver.openAssetFileDescriptor.
         * </p><p>
         * Note that this sub-directory also supports opening the logo as an asset file in write
         * mode.  Callers can create or replace the primary logo associated with this channel by
         * opening the asset file and writing the full-size photo contents into it.  When the file
         * is closed, the image will be parsed, sized down if necessary, and stored.
         * </p><p>
         * Usage example:
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
         * </p>
         */
        public static final class Logo {

            /**
             * The directory twig for this sub-table.
             */
            public static final String CONTENT_DIRECTORY = "logo";

            private Logo() {}
        }
    }

    /** Column definitions for the TV programs table. */
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
         * <p>
         * This is a part of the channel URI and matches to {@link BaseColumns#_ID}.
         * </p><p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String COLUMN_CHANNEL_ID = "channel_id";

        /**
         * The title of this TV program.
         * <p>
         * If this program is an episodic TV show, it is recommended that the title is the series
         * title and its related fields ({@link #COLUMN_SEASON_NUMBER},
         * {@link #COLUMN_EPISODE_NUMBER}, and {@link #COLUMN_EPISODE_TITLE}) are filled in.
         * </p><p>
         * Type: TEXT
         * </p>
         **/
        public static final String COLUMN_TITLE = "title";

        /**
         * The season number of this TV program for episodic TV shows.
         * <p>
         * Can be empty.
         * </p><p>
         * Type: INTEGER
         * </p>
         **/
        public static final String COLUMN_SEASON_NUMBER = "season_number";

        /**
         * The episode number of this TV program for episodic TV shows.
         * <p>
         * Can be empty.
         * </p><p>
         * Type: INTEGER
         * </p>
         **/
        public static final String COLUMN_EPISODE_NUMBER = "episode_number";

        /**
         * The episode title of this TV program for episodic TV shows.
         * <p>
         * Can be empty.
         * </p><p>
         * Type: TEXT
         * </p>
         **/
        public static final String COLUMN_EPISODE_TITLE = "episode_title";

        /**
         * The start time of this TV program, in milliseconds since the epoch.
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String COLUMN_START_TIME_UTC_MILLIS = "start_time_utc_millis";

        /**
         * The end time of this TV program, in milliseconds since the epoch.
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String COLUMN_END_TIME_UTC_MILLIS = "end_time_utc_millis";

        /**
         * The comma-separated genre string of this TV program.
         * <p>
         * Use the same language appeared in the underlying broadcast standard, if applicable. (For
         * example, one can refer to the genre strings used in Genre Descriptor of ATSC A/65 or
         * Content Descriptor of ETSI EN 300 468, if appropriate.) Otherwise, leave empty.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_BROADCAST_GENRE = "broadcast_genre";

        /**
         * The comma-separated canonical genre string of this TV program.
         * <p>
         * Canonical genres are defined in {@link Genres}. Use {@link Genres#encode Genres.encode()}
         * to create a text that can be stored in this column. Use {@link Genres#decode
         * Genres.decode()} to get the canonical genre strings from the text stored in this column.
         * </p><p>
         * Type: TEXT
         * </p>
         * @see Genres
         */
        public static final String COLUMN_CANONICAL_GENRE = "canonical_genre";

        /**
         * The short description of this TV program that is displayed to the user by default.
         * <p>
         * It is recommended to limit the length of the descriptions to 256 characters.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_SHORT_DESCRIPTION = "short_description";

        /**
         * The detailed, lengthy description of this TV program that is displayed only when the user
         * wants to see more information.
         * <p>
         * TV input services should leave this field empty if they have no additional details beyond
         * {@link #COLUMN_SHORT_DESCRIPTION}.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_LONG_DESCRIPTION = "long_description";

        /**
         * The width of the video for this TV program, in the unit of pixels.
         * <p>
         * Together with {@link #COLUMN_VIDEO_HEIGHT} this is used to determine the video resolution
         * of the current TV program. Can be empty if it is not known initially or the program does
         * not convey any video such as the programs from type {@link Channels#SERVICE_TYPE_AUDIO}
         * channels.
         * </p><p>
         * Type: INTEGER
         * </p>
         */
        public static final String COLUMN_VIDEO_WIDTH = "video_width";

        /**
         * The height of the video for this TV program, in the unit of pixels.
         * <p>
         * Together with {@link #COLUMN_VIDEO_WIDTH} this is used to determine the video resolution
         * of the current TV program. Can be empty if it is not known initially or the program does
         * not convey any video such as the programs from type {@link Channels#SERVICE_TYPE_AUDIO}
         * channels.
         * </p><p>
         * Type: INTEGER
         * </p>
         */
        public static final String COLUMN_VIDEO_HEIGHT = "video_height";

        /**
         * The comma-separated audio languages of this TV program.
         * <p>
         * This is used to describe available audio languages included in the program. Use
         * 3-character language code as specified by ISO 639-2.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_AUDIO_LANGUAGE = "audio_language";

        /**
         * The comma-separated content ratings of this TV program.
         * <p>
         * This is used to describe the content rating(s) of this program. Each comma-separated
         * content rating sub-string should be generated by calling
         * {@link TvContentRating#flattenToString}. Note that in most cases the program content is
         * rated by a single rating system, thus resulting in a corresponding single sub-string that
         * does not require comma separation and multiple sub-strings appear only when the program
         * content is rated by two or more content rating systems. If any of those ratings is
         * specified as "blocked rating" in the user's parental control settings, the TV input
         * service should block the current content and wait for the signal that it is okay to
         * unblock.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_CONTENT_RATING = "content_rating";

        /**
         * The URI for the poster art of this TV program.
         * <p>
         * Can be empty.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_POSTER_ART_URI = "poster_art_uri";

        /**
         * The URI for the thumbnail of this TV program.
         * <p>
         * Can be empty.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_THUMBNAIL_URI = "thumbnail_uri";

        /**
         * Internal data used by individual TV input services.
         * <p>
         * This is internal to the provider that inserted it, and should not be decoded by other
         * apps.
         * </p><p>
         * Type: BLOB
         * </p>
         */
        public static final String COLUMN_INTERNAL_PROVIDER_DATA = "internal_provider_data";

        /**
         * The version number of this row entry used by TV input services.
         * <p>
         * This is best used by sync adapters to identify the rows to update. The number can be
         * defined by individual TV input services. One may assign the same value as
         * {@code version_number} in ETSI EN 300 468 or ATSC A/65, if the data are coming from a TV
         * broadcast.
         * </p><p>
         * Type: INTEGER
         * </p>
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

            private static final ArraySet<String> CANONICAL_GENRES = new ArraySet<String>();
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
            }

            private Genres() {}

            /**
             * Encodes canonical genre strings to a text that can be put into the database.
             *
             * @param genres Canonical genre strings. Use the strings defined in this class.
             * @return an encoded genre string that can be inserted into the
             *         {@link #COLUMN_CANONICAL_GENRE} column.
             */
            public static String encode(String... genres) {
                StringBuilder sb = new StringBuilder();
                String separator = "";
                for (String genre : genres) {
                    sb.append(separator).append(genre);
                    separator = ",";
                }
                return sb.toString();
            }

            /**
             * Decodes the canonical genre strings from the text stored in the database.
             *
             * @param genres The encoded genre string retrieved from the
             *            {@link #COLUMN_CANONICAL_GENRE} column.
             * @return canonical genre strings.
             */
            public static String[] decode(String genres) {
                return genres.split("\\s*,\\s*");
            }

            /**
             * Check whether a given genre is canonical or not.
             *
             * @param genre The name of genre to be checked.
             * @return {@code true} if the genre is canonical, otherwise {@code false}.
             * @hide
             */
            @SystemApi
            public static boolean isCanonical(String genre) {
                return CANONICAL_GENRES.contains(genre);
            }
        }
    }

    /**
     * Column definitions for the TV programs that the user watched. Applications do not have access
     * to this table.
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
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String COLUMN_WATCH_START_TIME_UTC_MILLIS =
                "watch_start_time_utc_millis";

        /**
         * The UTC time that the user stopped watching this TV program, in milliseconds since the
         * epoch.
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String COLUMN_WATCH_END_TIME_UTC_MILLIS = "watch_end_time_utc_millis";

        /**
         * The ID of the TV channel that provides this TV program.
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String COLUMN_CHANNEL_ID = "channel_id";

        /**
         * The title of this TV program.
         * <p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_TITLE = "title";

        /**
         * The start time of this TV program, in milliseconds since the epoch.
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String COLUMN_START_TIME_UTC_MILLIS = "start_time_utc_millis";

        /**
         * The end time of this TV program, in milliseconds since the epoch.
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String COLUMN_END_TIME_UTC_MILLIS = "end_time_utc_millis";

        /**
         * The description of this TV program.
         * <p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_DESCRIPTION = "description";

        /**
         * Extra parameters given to {@link TvInputService.Session#tune(Uri, android.os.Bundle)
         * TvInputService.Session.tune(Uri, android.os.Bundle)} when tuning to the channel that
         * provides this TV program. (Used internally.)
         * <p>
         * This column contains an encoded string that represents comma-separated key-value pairs of
         * the tune parameters. (Ex. "[key1]=[value1], [key2]=[value2]"). '%' is used as an escape
         * character for '%', '=', and ','.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_INTERNAL_TUNE_PARAMS = "tune_params";

        /**
         * The session token of this TV program. (Used internally.)
         * <p>
         * This contains a String representation of {@link IBinder} for
         * {@link TvInputService.Session} that provides the current TV program. It is used
         * internally to distinguish watched programs entries from different TV input sessions.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String COLUMN_INTERNAL_SESSION_TOKEN = "session_token";

        private WatchedPrograms() {}
    }
}
