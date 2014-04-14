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

package android.provider;

import android.content.ContentUris;
import android.net.Uri;

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
    public static final String AUTHORITY = "com.android.tv";

    /**
     * Builds a URI that points to a specific channel.
     *
     * @param channelId The ID of the channel to point to.
     */
    public static final Uri buildChannelUri(long channelId) {
        return ContentUris.withAppendedId(Channels.CONTENT_URI, channelId);
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
     * Builds a URI that points to a specific program the user watched.
     *
     * @param watchedProgramId The ID of the watched program to point to.
     * @hide
     */
    public static final Uri buildWatchedProgramUri(long watchedProgramId) {
        return ContentUris.withAppendedId(WatchedPrograms.CONTENT_URI, watchedProgramId);
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
        public static final String PACKAGE_NAME = "package_name";
    }

    /** Column definitions for the TV channels table. */
    public static final class Channels implements BaseTvColumns {

        /** The content:// style URI for this table. */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/channel");

        /** The MIME type of a directory of TV channels. */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/vnd.com.android.tv.channels";

        /** The MIME type of a single TV channel. */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/vnd.com.android.tv.channels";

        /** A generic channel type. */
        public static final int TYPE_OTHER = 0x0;

        /** The special channel type used for pass-through inputs such as HDMI. */
        public static final int TYPE_PASSTHROUGH = 0x00010000;

        /** The channel type for DVB-T (terrestrial). */
        public static final int TYPE_DVB_T = 0x00020000;

        /** The channel type for DVB-T2 (terrestrial). */
        public static final int TYPE_DVB_T2 = 0x00020001;

        /** The channel type for DVB-S (satellite). */
        public static final int TYPE_DVB_S = 0x00020100;

        /** The channel type for DVB-S2 (satellite). */
        public static final int TYPE_DVB_S2 = 0x00020101;

        /** The channel type for DVB-C (cable). */
        public static final int TYPE_DVB_C = 0x00020200;

        /** The channel type for DVB-C2 (cable). */
        public static final int TYPE_DVB_C2 = 0x00020201;

        /** The channel type for DVB-H (handheld). */
        public static final int TYPE_DVB_H = 0x00020300;

        /** The channel type for DVB-SH (satellite). */
        public static final int TYPE_DVB_SH = 0x00020400;

        /** The channel type for ATSC (terrestrial/cable). */
        public static final int TYPE_ATSC = 0x00030000;

        /** The channel type for ATSC 2.0. */
        public static final int TYPE_ATSC_2_0 = 0x00030001;

        /** The channel type for ATSC-M/H (mobile/handheld). */
        public static final int TYPE_ATSC_M_H = 0x00030100;

        /** The channel type for ISDB-T (terrestrial). */
        public static final int TYPE_ISDB_T = 0x00040000;

        /** The channel type for ISDB-Tb (Brazil). */
        public static final int TYPE_ISDB_TB = 0x00040100;

        /** The channel type for ISDB-S (satellite). */
        public static final int TYPE_ISDB_S = 0x00040200;

        /** The channel type for ISDB-C (cable). */
        public static final int TYPE_ISDB_C = 0x00040300;

        /** The channel type for 1seg (handheld). */
        public static final int TYPE_1SEG = 0x00040400;

        /** The channel type for DTMB (terrestrial). */
        public static final int TYPE_DTMB = 0x00050000;

        /** The channel type for CMMB (handheld). */
        public static final int TYPE_CMMB = 0x00050100;

        /** The channel type for T-DMB (terrestrial). */
        public static final int TYPE_T_DMB = 0x00060000;

        /** The channel type for S-DMB (satellite). */
        public static final int TYPE_S_DMB = 0x00060100;

        /**
         * The name of the TV input service that provides this TV channel.
         * <p>
         * This is a required field.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String SERVICE_NAME = "service_name";

        /**
         * The predefined type of this TV channel.
         * <p>
         * This is used to indicate which broadcast standard (e.g. ATSC, DVB or ISDB) the current
         * channel conforms to.
         * </p><p>
         * This is a required field.
         * </p><p>
         * Type: INTEGER
         * </p>
         */
        public static final String TYPE = "type";

        /**
         * The transport stream ID as appeared in various broadcast standards.
         * <p>
         * This is not a required field but if provided, can significantly increase the accuracy of
         * channel identification.
         * </p><p>
         * Type: INTEGER
         * </p>
         */
        public static final String TRANSPORT_STREAM_ID = "transport_stream_id";

        /**
         * The channel number that is displayed to the user.
         * <p>
         * The format can vary depending on broadcast standard and product specification.
         * </p><p>
         * Type: INTEGER
         * </p>
         */
        public static final String DISPLAY_NUMBER = "display_number";

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
        public static final String DISPLAY_NAME = "display_name";

        /**
         * The description of this TV channel.
         * <p>
         * Can be empty initially.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String DESCRIPTION = "description";

        /**
         * The flag indicating whether this TV channel is browsable or not.
         * <p>
         * A value of 1 indicates the channel is included in the channel list that applications use
         * to browse channels, a value of 0 indicates the channel is not included in the list. If
         * not specified, this value is set to 1 by default.
         * </p><p>
         * Type: INTEGER (boolean)
         * </p>
         */
        public static final String BROWSABLE = "browsable";

        /**
         * Generic data used by individual TV input services.
         * <p>
         * Type: BLOB
         * </p>
         */
        public static final String DATA = "data";


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
        public static final String VERSION_NUMBER = "version_number";

        private Channels() {}
    }

    /** Column definitions for the TV programs table. */
    public static final class Programs implements BaseTvColumns {

        /** The content:// style URI for this table. */
        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/program");

        /** The MIME type of a directory of TV programs. */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/vnd.com.android.tv.programs";

        /** The MIME type of a single TV program. */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/vnd.com.android.tv.programs";

        /**
         * The ID of the TV channel that contains this TV program.
         * <p>
         * This is a part of the channel URI and matches to {@link BaseColumns#_ID}.
         * </p><p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String CHANNEL_ID = "channel_id";

        /**
         * The title of this TV program.
         * <p>
         * Type: TEXT
         * </p>
         **/
        public static final String TITLE = "title";

        /**
         * The start time of this TV program, in milliseconds since the epoch.
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String START_TIME_UTC_MILLIS = "start_time_utc_millis";

        /**
         * The end time of this TV program, in milliseconds since the epoch.
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String END_TIME_UTC_MILLIS = "end_time_utc_millis";

        /**
         * The description of this TV program that is displayed to the user by default.
         * <p>
         * The maximum length of this field is 256 characters.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String DESCRIPTION = "description";

        /**
         * The detailed, lengthy description of this TV program that is displayed only when the user
         * wants to see more information.
         * <p>
         * TV input services should leave this field empty if they have no additional
         * details beyond {@link #DESCRIPTION}.
         * </p><p>
         * Type: TEXT
         * </p>
         */
        public static final String LONG_DESCRIPTION = "long_description";

        /**
         * Generic data used by TV input services.
         * <p>
         * Type: BLOB
         * </p>
         */
        public static final String DATA = "data";

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
        public static final String VERSION_NUMBER = "version_number";

        private Programs() {}
    }

    /**
     * Column definitions for the TV programs that the user watched. Applications do not have access
     * to this table.
     *
     * @hide
     */
    public static final class WatchedPrograms implements BaseColumns {

        /** The content:// style URI for this table. */
        public static final Uri CONTENT_URI =
                Uri.parse("content://" + AUTHORITY + "/watched_program");

        /** The MIME type of a directory of watched programs. */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/vnd.com.android.tv.watched_programs";

        /** The MIME type of a single item in this table. */
        public static final String CONTENT_ITEM_TYPE =
                "vnd.android.cursor.item/vnd.com.android.tv.watched_programs";

        /**
         * The UTC time that the user started watching this TV program, in milliseconds since the
         * epoch.
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String WATCH_START_TIME_UTC_MILLIS = "watch_start_time_utc_millis";

        /**
         * The UTC time that the user stopped watching this TV program, in milliseconds since the
         * epoch.
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String WATCH_END_TIME_UTC_MILLIS = "watch_end_time_utc_millis";

        /**
         * The channel ID that contains this TV program.
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String CHANNEL_ID = "channel_id";

        /**
         * The title of this TV program.
         * <p>
         * Type: TEXT
         * </p>
         */
        public static final String TITLE = "title";

        /**
         * The start time of this TV program, in milliseconds since the epoch.
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String START_TIME_UTC_MILLIS = "start_time_utc_millis";

        /**
         * The end time of this TV program, in milliseconds since the epoch.
         * <p>
         * Type: INTEGER (long)
         * </p>
         */
        public static final String END_TIME_UTC_MILLIS = "end_time_utc_millis";

        /**
         * The description of this TV program.
         * <p>
         * Type: TEXT
         * </p>
         */
        public static final String DESCRIPTION = "description";

        private WatchedPrograms() {}
    }
}
