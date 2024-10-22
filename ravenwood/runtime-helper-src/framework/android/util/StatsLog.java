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

package android.util;

/*
 * [Ravenwood] This is copied from StatsD, with the following changes:
 * - The static {} is commented out.
 * - All references to IStatsD and StatsdStatsLog are commented out.
 * - The native method is no-oped.
 */

import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.PACKAGE_USAGE_STATS;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.os.Build;
//import android.os.IStatsd;
import android.os.Process;
import android.util.proto.ProtoOutputStream;

import androidx.annotation.RequiresApi;

//import com.android.internal.statsd.StatsdStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * StatsLog provides an API for developers to send events to statsd. The events can be used to
 * define custom metrics in side statsd.
 */
public final class StatsLog {

//    // Load JNI library
//    static {
//        System.loadLibrary("stats_jni");
//    }
    private static final String TAG = "StatsLog";
    private static final boolean DEBUG = false;
    private static final int EXPERIMENT_IDS_FIELD_ID = 1;

    /**
     * Annotation ID constant for logging UID field.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    public static final byte ANNOTATION_ID_IS_UID = 1;

    /**
     * Annotation ID constant to indicate logged atom event's timestamp should be truncated.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    public static final byte ANNOTATION_ID_TRUNCATE_TIMESTAMP = 2;

    /**
     * Annotation ID constant for a state atom's primary field.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    public static final byte ANNOTATION_ID_PRIMARY_FIELD = 3;

    /**
     * Annotation ID constant for state atom's state field.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    public static final byte ANNOTATION_ID_EXCLUSIVE_STATE = 4;

    /**
     * Annotation ID constant to indicate the first UID in the attribution chain
     * is a primary field.
     * Should only be used for attribution chain fields.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    public static final byte ANNOTATION_ID_PRIMARY_FIELD_FIRST_UID = 5;

    /**
     * Annotation ID constant to indicate which state is default for the state atom.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    public static final byte ANNOTATION_ID_DEFAULT_STATE = 6;

    /**
     * Annotation ID constant to signal all states should be reset to the default state.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    public static final byte ANNOTATION_ID_TRIGGER_STATE_RESET = 7;

    /**
     * Annotation ID constant to indicate state changes need to account for nesting.
     * This should only be used with binary state atoms.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    public static final byte ANNOTATION_ID_STATE_NESTED = 8;

    /**
     * Annotation ID constant to indicate the restriction category of an atom.
     * This annotation must only be attached to the atom id. This is an int annotation.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final byte ANNOTATION_ID_RESTRICTION_CATEGORY = 9;

    /**
     * Annotation ID to indicate that a field of an atom contains peripheral device info.
     * This is a bool annotation.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_PERIPHERAL_DEVICE_INFO = 10;

    /**
     * Annotation ID to indicate that a field of an atom contains app usage information.
     * This is a bool annotation.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_APP_USAGE = 11;

    /**
     * Annotation ID to indicate that a field of an atom contains app activity information.
     * This is a bool annotation.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_APP_ACTIVITY = 12;

    /**
     * Annotation ID to indicate that a field of an atom contains health connect information.
     * This is a bool annotation.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_HEALTH_CONNECT = 13;

    /**
     * Annotation ID to indicate that a field of an atom contains accessibility information.
     * This is a bool annotation.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_ACCESSIBILITY = 14;

    /**
     * Annotation ID to indicate that a field of an atom contains system search information.
     * This is a bool annotation.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_SYSTEM_SEARCH = 15;

    /**
     * Annotation ID to indicate that a field of an atom contains user engagement information.
     * This is a bool annotation.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_USER_ENGAGEMENT = 16;

    /**
     * Annotation ID to indicate that a field of an atom contains ambient sensing information.
     * This is a bool annotation.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_AMBIENT_SENSING = 17;

    /**
     * Annotation ID to indicate that a field of an atom contains demographic classification
     * information. This is a bool annotation.
     *
     * The ID is a byte since StatsEvent.addBooleanAnnotation() and StatsEvent.addIntAnnotation()
     * accept byte as the type for annotation ids to save space.
     *
     * @hide
     */
    @SuppressLint("NoByteOrShort")
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final byte ANNOTATION_ID_FIELD_RESTRICTION_DEMOGRAPHIC_CLASSIFICATION = 18;


    /** @hide */
    @IntDef(prefix = { "RESTRICTION_CATEGORY_" }, value = {
            RESTRICTION_CATEGORY_DIAGNOSTIC,
            RESTRICTION_CATEGORY_SYSTEM_INTELLIGENCE,
            RESTRICTION_CATEGORY_AUTHENTICATION,
            RESTRICTION_CATEGORY_FRAUD_AND_ABUSE})
    @Retention(RetentionPolicy.SOURCE)
    public @interface RestrictionCategory {}

    /**
     * Restriction category for atoms about diagnostics.
     *
     * @hide
     */
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final int RESTRICTION_CATEGORY_DIAGNOSTIC = 1;

    /**
     * Restriction category for atoms about system intelligence.
     *
     * @hide
     */
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final int RESTRICTION_CATEGORY_SYSTEM_INTELLIGENCE = 2;

    /**
     * Restriction category for atoms about authentication.
     *
     * @hide
     */
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final int RESTRICTION_CATEGORY_AUTHENTICATION = 3;

    /**
     * Restriction category for atoms about fraud and abuse.
     *
     * @hide
     */
    @SystemApi
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public static final int RESTRICTION_CATEGORY_FRAUD_AND_ABUSE = 4;

    private StatsLog() {
    }

    /**
     * Logs a start event.
     *
     * @param label developer-chosen label.
     * @return True if the log request was sent to statsd.
     */
    public static boolean logStart(int label) {
        int callingUid = Process.myUid();
//        StatsdStatsLog.write(
//                StatsdStatsLog.APP_BREADCRUMB_REPORTED,
//                callingUid,
//                label,
//                StatsdStatsLog.APP_BREADCRUMB_REPORTED__STATE__START);
        return true;
    }

    /**
     * Logs a stop event.
     *
     * @param label developer-chosen label.
     * @return True if the log request was sent to statsd.
     */
    public static boolean logStop(int label) {
        int callingUid = Process.myUid();
//        StatsdStatsLog.write(
//                StatsdStatsLog.APP_BREADCRUMB_REPORTED,
//                callingUid,
//                label,
//                StatsdStatsLog.APP_BREADCRUMB_REPORTED__STATE__STOP);
        return true;
    }

    /**
     * Logs an event that does not represent a start or stop boundary.
     *
     * @param label developer-chosen label.
     * @return True if the log request was sent to statsd.
     */
    public static boolean logEvent(int label) {
        int callingUid = Process.myUid();
//        StatsdStatsLog.write(
//                StatsdStatsLog.APP_BREADCRUMB_REPORTED,
//                callingUid,
//                label,
//                StatsdStatsLog.APP_BREADCRUMB_REPORTED__STATE__UNSPECIFIED);
        return true;
    }

    /**
     * Logs an event for binary push for module updates.
     *
     * @param trainName        name of install train.
     * @param trainVersionCode version code of the train.
     * @param options          optional flags about this install.
     *                         The last 3 bits indicate options:
     *                             0x01: FLAG_REQUIRE_STAGING
     *                             0x02: FLAG_ROLLBACK_ENABLED
     *                             0x04: FLAG_REQUIRE_LOW_LATENCY_MONITOR
     * @param state            current install state. Defined as State enums in
     *                         BinaryPushStateChanged atom in
     *                         frameworks/proto_logging/stats/atoms.proto
     * @param experimentIds    experiment ids.
     * @return True if the log request was sent to statsd.
     */
    @RequiresPermission(allOf = {DUMP, PACKAGE_USAGE_STATS})
    public static boolean logBinaryPushStateChanged(@NonNull String trainName,
            long trainVersionCode, int options, int state,
            @NonNull long[] experimentIds) {
        ProtoOutputStream proto = new ProtoOutputStream();
        for (long id : experimentIds) {
            proto.write(
                    ProtoOutputStream.FIELD_TYPE_INT64
                    | ProtoOutputStream.FIELD_COUNT_REPEATED
                    | EXPERIMENT_IDS_FIELD_ID,
                    id);
        }
//        StatsdStatsLog.write(StatsdStatsLog.BINARY_PUSH_STATE_CHANGED,
//                trainName,
//                trainVersionCode,
//                (options & IStatsd.FLAG_REQUIRE_STAGING) > 0,
//                (options & IStatsd.FLAG_ROLLBACK_ENABLED) > 0,
//                (options & IStatsd.FLAG_REQUIRE_LOW_LATENCY_MONITOR) > 0,
//                state,
//                proto.getBytes(),
//                0,
//                0,
//                false);
        return true;
    }

    /**
     * Write an event to stats log using the raw format.
     *
     * @param buffer    The encoded buffer of data to write.
     * @param size      The number of bytes from the buffer to write.
     * @hide
     * @deprecated Use {@link write(final StatsEvent statsEvent)} instead.
     *
     */
    @Deprecated
    @SystemApi
    public static void writeRaw(@NonNull byte[] buffer, int size) {
        writeImpl(buffer, size, 0);
    }

    /**
     * Write an event to stats log using the raw format.
     *
     * @param buffer    The encoded buffer of data to write.
     * @param size      The number of bytes from the buffer to write.
     * @param atomId    The id of the atom to which the event belongs.
     */
//    private static native void writeImpl(@NonNull byte[] buffer, int size, int atomId);
    private static void writeImpl(@NonNull byte[] buffer, int size, int atomId) {
        // no-op for now
    }

    /**
     * Write an event to stats log using the raw format encapsulated in StatsEvent.
     * After writing to stats log, release() is called on the StatsEvent object.
     * No further action should be taken on the StatsEvent object following this call.
     *
     * @param statsEvent    The StatsEvent object containing the encoded buffer of data to write.
     * @hide
     */
    @SystemApi
    public static void write(@NonNull final StatsEvent statsEvent) {
        writeImpl(statsEvent.getBytes(), statsEvent.getNumBytes(), statsEvent.getAtomId());
        statsEvent.release();
    }
}
