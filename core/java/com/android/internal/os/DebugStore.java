/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.os;

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.List;
import java.util.Objects;


/**
 * The DebugStore class provides methods for recording various debug events related to service
 * lifecycle, broadcast receivers and others.
 * The DebugStore class facilitates debugging ANR issues by recording time-stamped events
 * related to service lifecycles, broadcast receivers, and other framework operations. It logs
 * the start and end times of operations within the ANR timer scope called  by framework,
 * enabling pinpointing of methods and events contributing to ANRs.
 *
 * Usage currently includes recording service starts, binds, and asynchronous operations initiated
 * by broadcast receivers, providing a granular view of system behavior that facilitates
 * identifying performance bottlenecks and optimizing issue resolution.
 *
 * @hide
 */
public class DebugStore {
    private static final boolean DEBUG_EVENTS = false;
    private static final String TAG = "DebugStore";

    private static DebugStoreNative sDebugStoreNative = new DebugStoreNativeImpl();

    @UnsupportedAppUsage
    @VisibleForTesting
    public static void setDebugStoreNative(DebugStoreNative nativeImpl) {
        sDebugStoreNative = nativeImpl;
    }
    /**
     * Records the start of a service.
     *
     * @param startId The start ID of the service.
     * @param flags Additional flags for the service start.
     * @param intent The Intent associated with the service start.
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordServiceOnStart(int startId, int flags, @Nullable Intent intent) {
        return sDebugStoreNative.beginEvent(
                "SvcStart",
                List.of(
                        "stId",
                        String.valueOf(startId),
                        "flg",
                        Integer.toHexString(flags),
                        "act",
                        Objects.toString(intent != null ? intent.getAction() : null),
                        "comp",
                        Objects.toString(intent != null ? intent.getComponent() : null),
                        "pkg",
                        Objects.toString(intent != null ? intent.getPackage() : null)));
    }

    /**
     * Records the creation of a service.
     *
     * @param serviceInfo Information about the service being created.
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordServiceCreate(@Nullable ServiceInfo serviceInfo) {
        return sDebugStoreNative.beginEvent(
                "SvcCreate",
                List.of(
                        "name",
                        Objects.toString(serviceInfo != null ? serviceInfo.name : null),
                        "pkg",
                        Objects.toString(serviceInfo != null ? serviceInfo.packageName : null)));
    }

    /**
     * Records the binding of a service.
     *
     * @param isRebind Indicates whether the service is being rebound.
     * @param intent The Intent associated with the service binding.
     * @return A unique identifier for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordServiceBind(boolean isRebind, @Nullable Intent intent) {
        return sDebugStoreNative.beginEvent(
                "SvcBind",
                List.of(
                        "rebind",
                        String.valueOf(isRebind),
                        "act",
                        Objects.toString(intent != null ? intent.getAction() : null),
                        "cmp",
                        Objects.toString(intent != null ? intent.getComponent() : null),
                        "pkg",
                        Objects.toString(intent != null ? intent.getPackage() : null)));
    }

    /**
     * Records an asynchronous operation initiated by a broadcast receiver through calling GoAsync.
     *
     * @param receiverClassName The class name of the broadcast receiver.
     */
    @UnsupportedAppUsage
    public static void recordGoAsync(int pendingResultId) {
        sDebugStoreNative.recordEvent(
                "GoAsync",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "tid",
                        String.valueOf(Thread.currentThread().getId()),
                        "prid",
                        Integer.toHexString(pendingResultId)));
    }

    /**
     * Records the completion of a broadcast operation through calling Finish.
     *
     * @param receiverClassName The class of the broadcast receiver that completed the operation.
     */
    @UnsupportedAppUsage
    public static void recordFinish(int pendingResultId) {
        sDebugStoreNative.recordEvent(
                "Finish",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "tid",
                        String.valueOf(Thread.currentThread().getId()),
                        "prid",
                        Integer.toHexString(pendingResultId)));
    }

    /**
     * Records the completion of a long-running looper message.
     *
     * @param messageCode The code representing the type of the message.
     * @param targetClass The FQN of the class that handled the message.
     * @param elapsedTimeMs The time that was taken to process the message, in milliseconds.
     */
    @UnsupportedAppUsage
    public static void recordLongLooperMessage(int messageCode, String targetClass,
            long elapsedTimeMs) {
        sDebugStoreNative.recordEvent(
                "LooperMsg",
                List.of(
                        "code",
                        String.valueOf(messageCode),
                        "trgt",
                        Objects.toString(targetClass),
                        "elapsed",
                        String.valueOf(elapsedTimeMs)));
    }


    /**
     * Records the reception of a broadcast by a manifest-declared receiver.
     *
     * @param intent The Intent associated with the broadcast.
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordBroadcastReceive(@Nullable Intent intent, int pendingResultId) {
        return sDebugStoreNative.beginEvent(
                "BcRcv",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "tid",
                        String.valueOf(Thread.currentThread().getId()),
                        "act",
                        Objects.toString(intent != null ? intent.getAction() : null),
                        "cmp",
                        Objects.toString(intent != null ? intent.getComponent() : null),
                        "pkg",
                        Objects.toString(intent != null ? intent.getPackage() : null),
                        "prid",
                        Integer.toHexString(pendingResultId)));
    }

    /**
     * Records the reception of a broadcast by a context-registered receiver.
     *
     * @param intent The Intent associated with the broadcast.
     * @param pendingResultId The object ID of the PendingResult associated with the broadcast.
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordBroadcastReceiveReg(@Nullable Intent intent, int pendingResultId) {
        return sDebugStoreNative.beginEvent(
                "BcRcvReg",
                List.of(
                        "tname",
                        Thread.currentThread().getName(),
                        "tid",
                        String.valueOf(Thread.currentThread().getId()),
                        "act",
                        Objects.toString(intent != null ? intent.getAction() : null),
                        "cmp",
                        Objects.toString(intent != null ? intent.getComponent() : null),
                        "pkg",
                        Objects.toString(intent != null ? intent.getPackage() : null),
                        "prid",
                        Integer.toHexString(pendingResultId)));
    }

    /**
     * Records the binding of an application.
     *
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordHandleBindApplication() {
        return sDebugStoreNative.beginEvent("BindApp", List.of());
    }

    /**
     * Records the scheduling of a receiver.
     *
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordScheduleReceiver() {
        return sDebugStoreNative.beginEvent(
                "SchRcv",
                List.of(
                        "tname", Thread.currentThread().getName(),
                        "tid", String.valueOf(Thread.currentThread().getId())));
    }

    /**
     * Records the scheduling of a registered receiver.
     *
     * @return A unique ID for the recorded event.
     */
    @UnsupportedAppUsage
    public static long recordScheduleRegisteredReceiver() {
        return sDebugStoreNative.beginEvent(
                "SchRcvReg",
                List.of(
                        "tname", Thread.currentThread().getName(),
                        "tid", String.valueOf(Thread.currentThread().getId())));
    }

    /**
     * Ends a previously recorded event.
     *
     * @param id The unique ID of the event to be ended.
     */
    @UnsupportedAppUsage
    public static void recordEventEnd(long id) {
        sDebugStoreNative.endEvent(id, Collections.emptyList());
    }

    /**
     * An interface for a class that acts as a wrapper for the static native methods
     * of the Debug Store.
     *
     * It allows us to mock static native methods in our tests and should be removed
     * once mocking static methods becomes easier.
     */
    @VisibleForTesting
    public interface DebugStoreNative {
        /**
         * Begins an event with the given name and attributes.
         */
        long beginEvent(String eventName, List<String> attributes);
        /**
         * Ends an event with the given ID and attributes.
         */
        void endEvent(long id, List<String> attributes);
        /**
         * Records an event with the given name and attributes.
         */
        void recordEvent(String eventName, List<String> attributes);
    }

    private static class DebugStoreNativeImpl implements DebugStoreNative {
        @Override
        public long beginEvent(String eventName, List<String> attributes) {
            long id = DebugStore.beginEventNative(eventName, attributes);
            if (DEBUG_EVENTS) {
                Log.i(
                        TAG,
                        "beginEvent: " + id + " " + eventName + " " + attributeString(attributes));
            }
            return id;
        }

        @Override
        public void endEvent(long id, List<String> attributes) {
            if (DEBUG_EVENTS) {
                Log.i(TAG, "endEvent: " + id + " " + attributeString(attributes));
            }
            DebugStore.endEventNative(id, attributes);
        }

        @Override
        public void recordEvent(String eventName, List<String> attributes) {
            if (DEBUG_EVENTS) {
                Log.i(TAG, "recordEvent: " + eventName + " " + attributeString(attributes));
            }
            DebugStore.recordEventNative(eventName, attributes);
        }

        /**
         * Returns a string like "[key1=foo, key2=bar]"
         */
        private String attributeString(List<String> attributes) {
            StringBuilder sb = new StringBuilder().append("[");

            for (int i = 0; i < attributes.size(); i++) {
                sb.append(attributes.get(i));

                if (i % 2 == 0) {
                    sb.append("=");
                } else if (i < attributes.size() - 1) {
                    sb.append(", ");
                }
            }
            return sb.append("]").toString();
        }
    }

    private static native long beginEventNative(String eventName, List<String> attributes);

    private static native void endEventNative(long id, List<String> attributes);

    private static native void recordEventNative(String eventName, List<String> attributes);
}
