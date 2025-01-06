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

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Holds extras to be passed to Perfetto track events in {@link PerfettoTrace}.
 *
 * @hide
 */
public final class PerfettoTrackEventExtra {
    private static final int DEFAULT_EXTRA_CACHE_SIZE = 5;
    private static final ThreadLocal<PerfettoTrackEventExtra> sTrackEventExtra =
            new ThreadLocal<PerfettoTrackEventExtra>() {
                @Override
                protected PerfettoTrackEventExtra initialValue() {
                    return new PerfettoTrackEventExtra();
                }
            };
    private static final AtomicLong sNamedTrackId = new AtomicLong();

    private boolean mIsInUse;
    private CounterInt64 mCounterInt64;
    private CounterDouble mCounterDouble;
    private Proto mProto;

    /**
     * Represents a native pointer to a Perfetto C SDK struct. E.g. PerfettoTeHlExtra.
     */
    public interface PerfettoPointer {
        /**
         * Returns the perfetto struct native pointer.
         */
        long getPtr();
    }

    /**
     * Container for {@link Field} instances.
     */
    public interface FieldContainer {
        /**
         * Add {@link Field} to the container.
         */
        void addField(PerfettoPointer field);
    }

    /**
     * RingBuffer implemented on top of a SparseArray.
     *
     * Bounds a SparseArray with a FIFO algorithm.
     */
    private static final class RingBuffer<T> {
        private final int mCapacity;
        private final int[] mKeyArray;
        private final T[] mValueArray;
        private int mWriteEnd = 0;

        RingBuffer(int capacity) {
            mCapacity = capacity;
            mKeyArray = new int[capacity];
            mValueArray = (T[]) new Object[capacity];
        }

        public void put(int key, T value) {
            mKeyArray[mWriteEnd] = key;
            mValueArray[mWriteEnd] = value;
            mWriteEnd = (mWriteEnd + 1) % mCapacity;
        }

        public T get(int key) {
            for (int i = 0; i < mCapacity; i++) {
                if (mKeyArray[i] == key) {
                    return mValueArray[i];
                }
            }
            return null;
        }
    }

    private static final class Pool<T> {
        private final int mCapacity;
        private final T[] mValueArray;
        private int mIdx = 0;

        Pool(int capacity) {
            mCapacity = capacity;
            mValueArray = (T[]) new Object[capacity];
        }

        public void reset() {
            mIdx = 0;
        }

        public T get(Supplier<T> supplier) {
            if (mIdx >= mCapacity) {
                return supplier.get();
            }
            if (mValueArray[mIdx] == null) {
                mValueArray[mIdx] = supplier.get();
            }
            return mValueArray[mIdx++];
        }
    }

    /**
     * Builder for Perfetto track event extras.
     */
    public static final class Builder {
        // For performance reasons, we hold a reference to mExtra as a holder for
        // perfetto pointers being added. This way, we avoid an additional list to hold
        // the pointers in Java and we can pass them down directly to native code.
        private final PerfettoTrackEventExtra mExtra;
        private boolean mIsBuilt;
        private Builder mParent;
        private FieldContainer mCurrentContainer;

        private final CounterInt64 mCounterInt64;
        private final CounterDouble mCounterDouble;
        private final Proto mProto;

        private final RingBuffer<NamedTrack> mNamedTrackCache;
        private final RingBuffer<CounterTrack> mCounterTrackCache;
        private final RingBuffer<ArgInt64> mArgInt64Cache;
        private final RingBuffer<ArgBool> mArgBoolCache;
        private final RingBuffer<ArgDouble> mArgDoubleCache;
        private final RingBuffer<ArgString> mArgStringCache;

        private final Pool<FieldInt64> mFieldInt64Cache;
        private final Pool<FieldDouble> mFieldDoubleCache;
        private final Pool<FieldString> mFieldStringCache;
        private final Pool<FieldNested> mFieldNestedCache;
        private final Pool<Flow> mFlowCache;
        private final Pool<Builder> mBuilderCache;

        private Builder() {
            this(sTrackEventExtra.get(), null, null);
        }

        private Builder(PerfettoTrackEventExtra extra, Builder parent, FieldContainer container) {
            mExtra = extra;
            mParent = parent;
            mCurrentContainer = container;

            mNamedTrackCache = mExtra.mNamedTrackCache;
            mCounterTrackCache = mExtra.mCounterTrackCache;
            mArgInt64Cache = mExtra.mArgInt64Cache;
            mArgDoubleCache = mExtra.mArgDoubleCache;
            mArgBoolCache = mExtra.mArgBoolCache;
            mArgStringCache = mExtra.mArgStringCache;
            mFieldInt64Cache = mExtra.mFieldInt64Cache;
            mFieldDoubleCache = mExtra.mFieldDoubleCache;
            mFieldStringCache = mExtra.mFieldStringCache;
            mFieldNestedCache = mExtra.mFieldNestedCache;
            mFlowCache = mExtra.mFlowCache;
            mBuilderCache = mExtra.mBuilderCache;

            mCounterInt64 = mExtra.getCounterInt64();
            mCounterDouble = mExtra.getCounterDouble();
            mProto = mExtra.getProto();
        }

        /**
         * Builds the track event extra.
         */
        public PerfettoTrackEventExtra build() {
            checkParent();
            mIsBuilt = true;

            mFieldInt64Cache.reset();
            mFieldDoubleCache.reset();
            mFieldStringCache.reset();
            mFieldNestedCache.reset();
            mBuilderCache.reset();

            return mExtra;
        }

        /**
         * Adds a debug arg with key {@code name} and value {@code val}.
         */
        public Builder addArg(String name, long val) {
            checkParent();
            ArgInt64 arg = mArgInt64Cache.get(name.hashCode());
            if (arg == null || !arg.getName().equals(name)) {
                arg = new ArgInt64(name);
                mArgInt64Cache.put(name.hashCode(), arg);
            }
            arg.setValue(val);
            mExtra.addPerfettoPointer(arg);
            return this;
        }

        /**
         * Adds a debug arg with key {@code name} and value {@code val}.
         */
        public Builder addArg(String name, boolean val) {
            checkParent();
            ArgBool arg = mArgBoolCache.get(name.hashCode());
            if (arg == null || !arg.getName().equals(name)) {
                arg = new ArgBool(name);
                mArgBoolCache.put(name.hashCode(), arg);
            }
            arg.setValue(val);
            mExtra.addPerfettoPointer(arg);
            return this;
        }

        /**
         * Adds a debug arg with key {@code name} and value {@code val}.
         */
        public Builder addArg(String name, double val) {
            checkParent();
            ArgDouble arg = mArgDoubleCache.get(name.hashCode());
            if (arg == null || !arg.getName().equals(name)) {
                arg = new ArgDouble(name);
                mArgDoubleCache.put(name.hashCode(), arg);
            }
            arg.setValue(val);
            mExtra.addPerfettoPointer(arg);
            return this;
        }

        /**
         * Adds a debug arg with key {@code name} and value {@code val}.
         */
        public Builder addArg(String name, String val) {
            checkParent();
            ArgString arg = mArgStringCache.get(name.hashCode());
            if (arg == null || !arg.getName().equals(name)) {
                arg = new ArgString(name);
                mArgStringCache.put(name.hashCode(), arg);
            }
            arg.setValue(val);
            mExtra.addPerfettoPointer(arg);
            return this;
        }

        /**
         * Adds a flow with {@code id}.
         */
        public Builder addFlow(int id) {
            checkParent();
            Flow flow = mFlowCache.get(Flow::new);
            flow.setProcessFlow(id);
            mExtra.addPerfettoPointer(flow);
            return this;
        }

        /**
         * Adds a terminating flow with {@code id}.
         */
        public Builder addTerminatingFlow(int id) {
            checkParent();
            Flow flow = mFlowCache.get(Flow::new);
            flow.setProcessTerminatingFlow(id);
            mExtra.addPerfettoPointer(flow);
            return this;
        }

        /**
         * Adds the events to a named track instead of the thread track where the
         * event occurred.
         */
        public Builder usingNamedTrack(String name, long parentUuid) {
            checkParent();
            NamedTrack track = mNamedTrackCache.get(name.hashCode());
            if (track == null || !track.getName().equals(name)) {
                track = new NamedTrack(name, parentUuid);
                mNamedTrackCache.put(name.hashCode(), track);
            }
            mExtra.addPerfettoPointer(track);
            return this;
        }

        /**
         * Adds the events to a counter track instead. This is required for
         * setting counter values.
         *
         */
        public Builder usingCounterTrack(String name, long parentUuid) {
            checkParent();
            CounterTrack track = mCounterTrackCache.get(name.hashCode());
            if (track == null || !track.getName().equals(name)) {
                track = new CounterTrack(name, parentUuid);
                mCounterTrackCache.put(name.hashCode(), track);
            }
            mExtra.addPerfettoPointer(track);
            return this;
        }

        /**
         * Sets a long counter value on the event.
         *
         */
        public Builder setCounter(long val) {
            checkParent();
            mCounterInt64.setValue(val);
            mExtra.addPerfettoPointer(mCounterInt64);
            return this;
        }

        /**
         * Sets a double counter value on the event.
         *
         */
        public Builder setCounter(double val) {
            checkParent();
            mCounterDouble.setValue(val);
            mExtra.addPerfettoPointer(mCounterDouble);
            return this;
        }

        /**
         * Adds a proto field with field id {@code id} and value {@code val}.
         */
        public Builder addField(long id, long val) {
            checkContainer();
            FieldInt64 field = mFieldInt64Cache.get(FieldInt64::new);
            field.setValue(id, val);
            mCurrentContainer.addField(field);
            return this;
        }

        /**
         * Adds a proto field with field id {@code id} and value {@code val}.
         */
        public Builder addField(long id, double val) {
            checkContainer();
            FieldDouble field = mFieldDoubleCache.get(FieldDouble::new);
            field.setValue(id, val);
            mCurrentContainer.addField(field);
            return this;
        }

        /**
         * Adds a proto field with field id {@code id} and value {@code val}.
         */
        public Builder addField(long id, String val) {
            checkContainer();
            FieldString field = mFieldStringCache.get(FieldString::new);
            field.setValue(id, val);
            mCurrentContainer.addField(field);
            return this;
        }

        /**
         * Begins a proto field with field
         * Fields can be added from this point and there must be a corresponding
         * {@link endProto}.
         *
         * The proto field is a singleton and all proto fields get added inside the
         * one {@link beginProto} and {@link endProto} within the {@link Builder}.
         */
        public Builder beginProto() {
            checkParent();
            mProto.clearFields();
            mExtra.addPerfettoPointer(mProto);
            return mBuilderCache.get(Builder::new).init(this, mProto);
        }

        /**
         * Ends a proto field.
         */
        public Builder endProto() {
            if (mParent == null || mCurrentContainer == null) {
                throw new IllegalStateException("No proto to end");
            }
            return mParent;
        }

        /**
         * Begins a nested proto field with field id {@code id}.
         * Fields can be added from this point and there must be a corresponding
         * {@link endNested}.
         */
        public Builder beginNested(long id) {
            checkContainer();
            FieldNested field = mFieldNestedCache.get(FieldNested::new);
            field.setId(id);
            mCurrentContainer.addField(field);
            return mBuilderCache.get(Builder::new).init(this, field);
        }

        /**
         * Ends a nested proto field.
         */
        public Builder endNested() {
            if (mParent == null || mCurrentContainer == null) {
                throw new IllegalStateException("No nested field to end");
            }
            return mParent;
        }

        /**
         * Initializes a {@link Builder}.
         */
        public Builder init(Builder parent, FieldContainer container) {
            mParent = parent;
            mCurrentContainer = container;
            mIsBuilt = false;

            if (mParent == null) {
                if (mExtra.mIsInUse) {
                    throw new IllegalStateException("Cannot create a new builder when another"
                            + " extra is in use");
                }
                mExtra.mIsInUse = true;
            }
            return this;
        }

        private void checkState() {
            if (mIsBuilt) {
                throw new IllegalStateException(
                    "This builder has already been used. Create a new builder for another event.");
            }
        }

        private void checkParent() {
            checkState();
            if (mParent != null) {
                throw new IllegalStateException(
                    "This builder has already been used. Create a new builder for another event.");
            }
        }

        private void checkContainer() {
            checkState();
            if (mCurrentContainer == null) {
                throw new IllegalStateException(
                    "Field operations must be within beginProto/endProto block");
            }
        }
    }

    /**
     * Start a {@link Builder} to build a {@link PerfettoTrackEventExtra}.
     */
    public static Builder builder() {
        return sTrackEventExtra.get().mBuilderCache.get(Builder::new).init(null, null);
    }

    private final RingBuffer<NamedTrack> mNamedTrackCache =
            new RingBuffer(DEFAULT_EXTRA_CACHE_SIZE);
    private final RingBuffer<CounterTrack> mCounterTrackCache =
            new RingBuffer(DEFAULT_EXTRA_CACHE_SIZE);

    private final RingBuffer<ArgInt64> mArgInt64Cache = new RingBuffer(DEFAULT_EXTRA_CACHE_SIZE);
    private final RingBuffer<ArgBool> mArgBoolCache = new RingBuffer(DEFAULT_EXTRA_CACHE_SIZE);
    private final RingBuffer<ArgDouble> mArgDoubleCache = new RingBuffer(DEFAULT_EXTRA_CACHE_SIZE);
    private final RingBuffer<ArgString> mArgStringCache = new RingBuffer(DEFAULT_EXTRA_CACHE_SIZE);

    private final Pool<FieldInt64> mFieldInt64Cache = new Pool(DEFAULT_EXTRA_CACHE_SIZE);
    private final Pool<FieldDouble> mFieldDoubleCache = new Pool(DEFAULT_EXTRA_CACHE_SIZE);
    private final Pool<FieldString> mFieldStringCache = new Pool(DEFAULT_EXTRA_CACHE_SIZE);
    private final Pool<FieldNested> mFieldNestedCache = new Pool(DEFAULT_EXTRA_CACHE_SIZE);
    private final Pool<Flow> mFlowCache = new Pool(DEFAULT_EXTRA_CACHE_SIZE);
    private final Pool<Builder> mBuilderCache = new Pool(DEFAULT_EXTRA_CACHE_SIZE);

    private static final NativeAllocationRegistry sRegistry =
            NativeAllocationRegistry.createMalloced(
                    PerfettoTrackEventExtra.class.getClassLoader(), native_delete());

    private final long mPtr;
    private static final String TAG = "PerfettoTrackEventExtra";

    private PerfettoTrackEventExtra() {
        mPtr = native_init();
        sRegistry.registerNativeAllocation(this, mPtr);
    }

    /**
     * Returns the native pointer.
     */
    public long getPtr() {
        return mPtr;
    }

    /**
     * Adds a pointer representing a track event parameter.
     */
    public void addPerfettoPointer(PerfettoPointer extra) {
        native_add_arg(mPtr, extra.getPtr());
    }

    /**
     * Resets the track event extra.
     */
    public void reset() {
        native_clear_args(mPtr);
        mIsInUse = false;
    }

    private CounterInt64 getCounterInt64() {
        if (mCounterInt64 == null) {
            mCounterInt64 = new CounterInt64();
        }
        return mCounterInt64;
    }

    private CounterDouble getCounterDouble() {
        if (mCounterDouble == null) {
            mCounterDouble = new CounterDouble();
        }
        return mCounterDouble;
    }

    private Proto getProto() {
        if (mProto == null) {
            mProto = new Proto();
        }
        return mProto;
    }

    private static final class Flow implements PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        Flow.class.getClassLoader(), native_delete());

        private final long mPtr;
        private final long mExtraPtr;

        Flow() {
            mPtr = native_init();
            mExtraPtr = native_get_extra_ptr(mPtr);
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        public void setProcessFlow(long type) {
            native_set_process_flow(mPtr, type);
        }

        public void setProcessTerminatingFlow(long id) {
            native_set_process_terminating_flow(mPtr, id);
        }

        @Override
        public long getPtr() {
            return mExtraPtr;
        }

        @CriticalNative
        private static native long native_init();
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native void native_set_process_flow(long ptr, long type);
        @CriticalNative
        private static native void native_set_process_terminating_flow(long ptr, long id);
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
    }

    private static class NamedTrack implements PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        NamedTrack.class.getClassLoader(), native_delete());

        private final long mPtr;
        private final long mExtraPtr;
        private final String mName;

        NamedTrack(String name, long parentUuid) {
            mPtr = native_init(sNamedTrackId.incrementAndGet(), name, parentUuid);
            mExtraPtr = native_get_extra_ptr(mPtr);
            mName = name;
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @Override
        public long getPtr() {
            return mExtraPtr;
        }

        public String getName() {
            return mName;
        }

        @FastNative
        private static native long native_init(long id, String name, long parentUuid);
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
    }

    private static final class CounterTrack implements PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        CounterTrack.class.getClassLoader(), native_delete());

        private final long mPtr;
        private final long mExtraPtr;
        private final String mName;

        CounterTrack(String name, long parentUuid) {
            mPtr = native_init(name, parentUuid);
            mExtraPtr = native_get_extra_ptr(mPtr);
            mName = name;
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @Override
        public long getPtr() {
            return mExtraPtr;
        }

        public String getName() {
            return mName;
        }

        @FastNative
        private static native long native_init(String name, long parentUuid);
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
    }

    private static final class CounterInt64 implements PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        CounterInt64.class.getClassLoader(), native_delete());

        private final long mPtr;
        private final long mExtraPtr;

        CounterInt64() {
            mPtr = native_init();
            mExtraPtr = native_get_extra_ptr(mPtr);
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @Override
        public long getPtr() {
            return mExtraPtr;
        }

        public void setValue(long value) {
            native_set_value(mPtr, value);
        }

        @CriticalNative
        private static native long native_init();
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native void native_set_value(long ptr, long value);
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
    }

    private static final class CounterDouble implements PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        CounterDouble.class.getClassLoader(), native_delete());

        private final long mPtr;
        private final long mExtraPtr;

        CounterDouble() {
            mPtr = native_init();
            mExtraPtr = native_get_extra_ptr(mPtr);
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @Override
        public long getPtr() {
            return mExtraPtr;
        }

        public void setValue(double value) {
            native_set_value(mPtr, value);
        }

        @CriticalNative
        private static native long native_init();
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native void native_set_value(long ptr, double value);
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
    }

    private static final class ArgInt64 implements PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        ArgInt64.class.getClassLoader(), native_delete());

        // Private pointer holding Perfetto object with metadata
        private final long mPtr;

        // Public pointer to Perfetto object itself
        private final long mExtraPtr;

        private final String mName;

        ArgInt64(String name) {
            mPtr = native_init(name);
            mExtraPtr = native_get_extra_ptr(mPtr);
            mName = name;
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @Override
        public long getPtr() {
            return mExtraPtr;
        }

        public String getName() {
            return mName;
        }

        public void setValue(long val) {
            native_set_value(mPtr, val);
        }

        @FastNative
        private static native long native_init(String name);
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
        @CriticalNative
        private static native void native_set_value(long ptr, long val);
    }

    private static final class ArgBool implements PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        ArgBool.class.getClassLoader(), native_delete());

        // Private pointer holding Perfetto object with metadata
        private final long mPtr;

        // Public pointer to Perfetto object itself
        private final long mExtraPtr;

        private final String mName;

        ArgBool(String name) {
            mPtr = native_init(name);
            mExtraPtr = native_get_extra_ptr(mPtr);
            mName = name;
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @Override
        public long getPtr() {
            return mExtraPtr;
        }

        public String getName() {
            return mName;
        }

        public void setValue(boolean val) {
            native_set_value(mPtr, val);
        }

        @FastNative
        private static native long native_init(String name);
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
        @CriticalNative
        private static native void native_set_value(long ptr, boolean val);
    }

    private static final class ArgDouble implements PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        ArgDouble.class.getClassLoader(), native_delete());

        // Private pointer holding Perfetto object with metadata
        private final long mPtr;

        // Public pointer to Perfetto object itself
        private final long mExtraPtr;

        private final String mName;

        ArgDouble(String name) {
            mPtr = native_init(name);
            mExtraPtr = native_get_extra_ptr(mPtr);
            mName = name;
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @Override
        public long getPtr() {
            return mExtraPtr;
        }

        public String getName() {
            return mName;
        }

        public void setValue(double val) {
            native_set_value(mPtr, val);
        }

        @FastNative
        private static native long native_init(String name);
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
        @CriticalNative
        private static native void native_set_value(long ptr, double val);
    }

    private static final class ArgString implements PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        ArgString.class.getClassLoader(), native_delete());

        // Private pointer holding Perfetto object with metadata
        private final long mPtr;

        // Public pointer to Perfetto object itself
        private final long mExtraPtr;

        private final String mName;

        ArgString(String name) {
            mPtr = native_init(name);
            mExtraPtr = native_get_extra_ptr(mPtr);
            mName = name;
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @Override
        public long getPtr() {
            return mExtraPtr;
        }

        public String getName() {
            return mName;
        }

        public void setValue(String val) {
            native_set_value(mPtr, val);
        }

        @FastNative
        private static native long native_init(String name);
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
        @FastNative
        private static native void native_set_value(long ptr, String val);
    }

    private static final class Proto implements PerfettoPointer, FieldContainer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        Proto.class.getClassLoader(), native_delete());

        // Private pointer holding Perfetto object with metadata
        private final long mPtr;

        // Public pointer to Perfetto object itself
        private final long mExtraPtr;

        Proto() {
            mPtr = native_init();
            mExtraPtr = native_get_extra_ptr(mPtr);
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @Override
        public long getPtr() {
            return mExtraPtr;
        }

        @Override
        public void addField(PerfettoPointer field) {
            native_add_field(mPtr, field.getPtr());
        }

        public void clearFields() {
            native_clear_fields(mPtr);
        }

        @CriticalNative
        private static native long native_init();
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
        @CriticalNative
        private static native void native_add_field(long ptr, long extraPtr);
        @CriticalNative
        private static native void native_clear_fields(long ptr);
    }

    private static final class FieldInt64 implements PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        FieldInt64.class.getClassLoader(), native_delete());

        // Private pointer holding Perfetto object with metadata
        private final long mPtr;

        // Public pointer to Perfetto object itself
        private final long mFieldPtr;

        FieldInt64() {
            mPtr = native_init();
            mFieldPtr = native_get_extra_ptr(mPtr);
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @Override
        public long getPtr() {
            return mFieldPtr;
        }

        public void setValue(long id, long val) {
            native_set_value(mPtr, id, val);
        }

        @CriticalNative
        private static native long native_init();
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
        @CriticalNative
        private static native void native_set_value(long ptr, long id, long val);
    }

    private static final class FieldDouble implements PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        FieldDouble.class.getClassLoader(), native_delete());

        // Private pointer holding Perfetto object with metadata
        private final long mPtr;

        // Public pointer to Perfetto object itself
        private final long mFieldPtr;

        FieldDouble() {
            mPtr = native_init();
            mFieldPtr = native_get_extra_ptr(mPtr);
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @Override
        public long getPtr() {
            return mFieldPtr;
        }

        public void setValue(long id, double val) {
            native_set_value(mPtr, id, val);
        }

        @CriticalNative
        private static native long native_init();
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
        @CriticalNative
        private static native void native_set_value(long ptr, long id, double val);
    }

    private static final class FieldString implements PerfettoPointer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        FieldString.class.getClassLoader(), native_delete());

        // Private pointer holding Perfetto object with metadata
        private final long mPtr;

        // Public pointer to Perfetto object itself
        private final long mFieldPtr;

        FieldString() {
            mPtr = native_init();
            mFieldPtr = native_get_extra_ptr(mPtr);
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @Override
        public long getPtr() {
            return mFieldPtr;
        }

        public void setValue(long id, String val) {
            native_set_value(mPtr, id, val);
        }

        @CriticalNative
        private static native long native_init();
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
        @FastNative
        private static native void native_set_value(long ptr, long id, String val);
    }

    private static final class FieldNested implements PerfettoPointer, FieldContainer {
        private static final NativeAllocationRegistry sRegistry =
                NativeAllocationRegistry.createMalloced(
                        FieldNested.class.getClassLoader(), native_delete());

        // Private pointer holding Perfetto object with metadata
        private final long mPtr;

        // Public pointer to Perfetto object itself
        private final long mFieldPtr;

        FieldNested() {
            mPtr = native_init();
            mFieldPtr = native_get_extra_ptr(mPtr);
            sRegistry.registerNativeAllocation(this, mPtr);
        }

        @Override
        public long getPtr() {
            return mFieldPtr;
        }

        @Override
        public void addField(PerfettoPointer field) {
            native_add_field(mPtr, field.getPtr());
        }

        public void setId(long id) {
            native_set_id(mPtr, id);
        }

        @CriticalNative
        private static native long native_init();
        @CriticalNative
        private static native long native_delete();
        @CriticalNative
        private static native long native_get_extra_ptr(long ptr);
        @CriticalNative
        private static native void native_add_field(long ptr, long extraPtr);
        @CriticalNative
        private static native void native_set_id(long ptr, long id);
    }

    @CriticalNative
    private static native long native_init();
    @CriticalNative
    private static native long native_delete();
    @CriticalNative
    private static native void native_add_arg(long ptr, long extraPtr);
    @CriticalNative
    private static native void native_clear_args(long ptr);
}
