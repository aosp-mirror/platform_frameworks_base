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

package android.os;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;

import libcore.util.NativeAllocationRegistry;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Writes trace events to the perfetto trace buffer. These trace events can be
 * collected and visualized using the Perfetto UI.
 *
 * <p>This tracing mechanism is independent of the method tracing mechanism
 * offered by {@link Debug#startMethodTracing} or {@link Trace}.
 *
 * @hide
 */
public final class PerfettoTrace {
    private static final String TAG = "PerfettoTrace";

    // Keep in sync with C++
    private static final int PERFETTO_TE_TYPE_SLICE_BEGIN = 1;
    private static final int PERFETTO_TE_TYPE_SLICE_END = 2;
    private static final int PERFETTO_TE_TYPE_INSTANT = 3;
    private static final int PERFETTO_TE_TYPE_COUNTER = 4;

    private static final boolean IS_FLAG_ENABLED = android.os.Flags.perfettoSdkTracingV2();

    /**
     * For fetching the next flow event id in a process.
     */
    private static final AtomicInteger sFlowEventId = new AtomicInteger();

    /**
     * Perfetto category a trace event belongs to.
     * Registering a category is not sufficient to capture events within the category, it must
     * also be enabled in the trace config.
     */
    public static final class Category implements PerfettoTrackEventExtra.PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        Category.class.getClassLoader(), native_delete());

        private final long mPtr;
        private final long mExtraPtr;
        private final String mName;
        private final String mTag;
        private final String mSeverity;
        private boolean mIsRegistered;

        /**
         * Category ctor.
         *
         * @param name The category name.
         */
        public Category(String name) {
            this(name, null, null);
        }

        /**
         * Category ctor.
         *
         * @param name The category name.
         * @param tag An atrace tag name that this category maps to.
         */
        public Category(String name, String tag) {
            this(name, tag, null);
        }

        /**
         * Category ctor.
         *
         * @param name The category name.
         * @param tag An atrace tag name that this category maps to.
         * @param severity A Log style severity string for the category.
         */
        public Category(String name, String tag, String severity) {
            mName = name;
            mTag = tag;
            mSeverity = severity;
            mPtr = native_init(name, tag, severity);
            mExtraPtr = native_get_extra_ptr(mPtr);
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @FastNative
        private static native long native_init(String name, String tag, String severity);
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native void native_register(long ptr);
        @CriticalNative
        private static native void native_unregister(long ptr);
        @CriticalNative
        private static native boolean native_is_enabled(long ptr);
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);

        /**
         * Register the category.
         */
        public Category register() {
            native_register(mPtr);
            mIsRegistered = true;
            return this;
        }

        /**
         * Unregister the category.
         */
        public Category unregister() {
            native_unregister(mPtr);
            mIsRegistered = false;
            return this;
        }

        /**
         * Whether the category is enabled or not.
         */
        public boolean isEnabled() {
            return IS_FLAG_ENABLED && native_is_enabled(mPtr);
        }

        /**
         * Whether the category is registered or not.
         */
        public boolean isRegistered() {
            return mIsRegistered;
        }

        /**
         * Returns the native pointer for the category.
         */
        @Override
        public long getPtr() {
            return mExtraPtr;
        }
    }

    @FastNative
    private static native void native_event(int type, long tag, String name, long ptr);

    @CriticalNative
    private static native long native_get_process_track_uuid();

    @CriticalNative
    private static native long native_get_thread_track_uuid(long tid);

    @FastNative
    private static native void native_activate_trigger(String name, int ttlMs);

    /**
     * Writes a trace message to indicate a given section of code was invoked.
     *
     * @param category The perfetto category pointer.
     * @param eventName The event name to appear in the trace.
     * @param extra The extra arguments.
     */
    public static void instant(Category category, String eventName, PerfettoTrackEventExtra extra) {
        if (!category.isEnabled()) {
            return;
        }

        native_event(PERFETTO_TE_TYPE_INSTANT, category.getPtr(), eventName, extra.getPtr());
        extra.reset();
    }

    /**
     * Writes a trace message to indicate a given section of code was invoked.
     *
     * @param category The perfetto category.
     * @param eventName The event name to appear in the trace.
     * @param extraConfig Consumer for the extra arguments.
     */
    public static void instant(Category category, String eventName,
            Consumer<PerfettoTrackEventExtra.Builder> extraConfig) {
        PerfettoTrackEventExtra.Builder extra = PerfettoTrackEventExtra.builder();
        extraConfig.accept(extra);
        instant(category, eventName, extra.build());
    }

    /**
     * Writes a trace message to indicate a given section of code was invoked.
     *
     * @param category The perfetto category.
     * @param eventName The event name to appear in the trace.
     */
    public static void instant(Category category, String eventName) {
        instant(category, eventName, PerfettoTrackEventExtra.builder().build());
    }

    /**
     * Writes a trace message to indicate the start of a given section of code.
     *
     * @param category The perfetto category pointer.
     * @param eventName The event name to appear in the trace.
     * @param extra The extra arguments.
     */
    public static void begin(Category category, String eventName, PerfettoTrackEventExtra extra) {
        if (!category.isEnabled()) {
            return;
        }

        native_event(PERFETTO_TE_TYPE_SLICE_BEGIN, category.getPtr(), eventName, extra.getPtr());
        extra.reset();
    }

    /**
     * Writes a trace message to indicate the start of a given section of code.
     *
     * @param category The perfetto category pointer.
     * @param eventName The event name to appear in the trace.
     * @param extraConfig Consumer for the extra arguments.
     */
    public static void begin(Category category, String eventName,
            Consumer<PerfettoTrackEventExtra.Builder> extraConfig) {
        PerfettoTrackEventExtra.Builder extra = PerfettoTrackEventExtra.builder();
        extraConfig.accept(extra);
        begin(category, eventName, extra.build());
    }

    /**
     * Writes a trace message to indicate the start of a given section of code.
     *
     * @param category The perfetto category pointer.
     * @param eventName The event name to appear in the trace.
     */
    public static void begin(Category category, String eventName) {
        begin(category, eventName, PerfettoTrackEventExtra.builder().build());
    }

    /**
     * Writes a trace message to indicate the end of a given section of code.
     *
     * @param category The perfetto category pointer.
     * @param extra The extra arguments.
     */
    public static void end(Category category, PerfettoTrackEventExtra extra) {
        if (!category.isEnabled()) {
            return;
        }

        native_event(PERFETTO_TE_TYPE_SLICE_END, category.getPtr(), "", extra.getPtr());
        extra.reset();
    }

    /**
     * Writes a trace message to indicate the end of a given section of code.
     *
     * @param category The perfetto category pointer.
     * @param extraConfig Consumer for the extra arguments.
     */
    public static void end(Category category,
            Consumer<PerfettoTrackEventExtra.Builder> extraConfig) {
        PerfettoTrackEventExtra.Builder extra = PerfettoTrackEventExtra.builder();
        extraConfig.accept(extra);
        end(category, extra.build());
    }

    /**
     * Writes a trace message to indicate the end of a given section of code.
     *
     * @param category The perfetto category pointer.
     */
    public static void end(Category category) {
        end(category, PerfettoTrackEventExtra.builder().build());
    }

    /**
     * Writes a trace message to indicate the value of a given section of code.
     *
     * @param category The perfetto category pointer.
     * @param extra The extra arguments.
     */
    public static void counter(Category category, PerfettoTrackEventExtra extra) {
        if (!category.isEnabled()) {
            return;
        }

        native_event(PERFETTO_TE_TYPE_COUNTER, category.getPtr(), "", extra.getPtr());
        extra.reset();
    }

    /**
     * Writes a trace message to indicate the value of a given section of code.
     *
     * @param category The perfetto category pointer.
     * @param extraConfig Consumer for the extra arguments.
     */
    public static void counter(Category category,
            Consumer<PerfettoTrackEventExtra.Builder> extraConfig) {
        PerfettoTrackEventExtra.Builder extra = PerfettoTrackEventExtra.builder();
        extraConfig.accept(extra);
        counter(category, extra.build());
    }

    /**
     * Writes a trace message to indicate the value of a given section of code.
     *
     * @param category The perfetto category pointer.
     * @param trackName The trackName for the event.
     * @param value The value of the counter.
     */
    public static void counter(Category category, String trackName, long value) {
        PerfettoTrackEventExtra extra = PerfettoTrackEventExtra.builder()
                .usingCounterTrack(trackName, PerfettoTrace.getProcessTrackUuid())
                .setCounter(value)
                .build();
        counter(category, extra);
    }

    /**
     * Writes a trace message to indicate the value of a given section of code.
     *
     * @param category The perfetto category pointer.
     * @param trackName The trackName for the event.
     * @param value The value of the counter.
     */
    public static void counter(Category category, String trackName, double value) {
        PerfettoTrackEventExtra extra = PerfettoTrackEventExtra.builder()
                .usingCounterTrack(trackName, PerfettoTrace.getProcessTrackUuid())
                .setCounter(value)
                .build();
        counter(category, extra);
    }

    /**
     * Returns the next flow id to be used.
     */
    public static int getFlowId() {
        return sFlowEventId.incrementAndGet();
    }

    /**
     * Returns the global track uuid that can be used as a parent track uuid.
     */
    public static long getGlobalTrackUuid() {
        return 0;
    }

    /**
     * Returns the process track uuid that can be used as a parent track uuid.
     */
    public static long getProcessTrackUuid() {
        if (IS_FLAG_ENABLED) {
            return 0;
        }
        return native_get_process_track_uuid();
    }

    /**
     * Given a thread tid, returns the thread track uuid that can be used as a parent track uuid.
     */
    public static long getThreadTrackUuid(long tid) {
        if (IS_FLAG_ENABLED) {
            return 0;
        }
        return native_get_thread_track_uuid(tid);
    }

    /**
     * Activates a trigger by name {@code triggerName} with expiry in {@code ttlMs}.
     */
    public static void activateTrigger(String triggerName, int ttlMs) {
        if (IS_FLAG_ENABLED) {
            return;
        }
        native_activate_trigger(triggerName, ttlMs);
    }

    /**
     * Registers the process with Perfetto.
     */
    public static void register() {
        Trace.registerWithPerfetto();
    }
}
