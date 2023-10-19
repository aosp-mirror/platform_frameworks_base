/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.flags;

import android.annotation.NonNull;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A class for querying constants from the system - primarily booleans.
 *
 * Clients using this class can define their flags and their default values in one place,
 * can override those values on running devices for debugging and testing purposes, and can control
 * what flags are available to be used on release builds.
 *
 * TODO(b/279054964): A lot. This is skeleton code right now.
 * @hide
 */
public class FeatureFlags {
    private static final String TAG = "FeatureFlags";
    private static FeatureFlags sInstance;
    private static final Object sInstanceLock = new Object();

    private final Set<Flag<?>> mKnownFlags = new ArraySet<>();
    private final Set<Flag<?>> mDirtyFlags = new ArraySet<>();

    private IFeatureFlags mIFeatureFlags;
    private final Map<String, Map<String, Boolean>> mBooleanOverrides = new HashMap<>();
    private final Set<ChangeListener> mListeners = new HashSet<>();

    /**
     * Obtain a per-process instance of FeatureFlags.
     * @return A singleton instance of {@link FeatureFlags}.
     */
    @NonNull
    public static FeatureFlags getInstance() {
        synchronized (sInstanceLock) {
            if (sInstance == null) {
                sInstance = new FeatureFlags();
            }
        }

        return sInstance;
    }

    /** See {@link FeatureFlagsFake}. */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public static void setInstance(FeatureFlags instance) {
        synchronized (sInstanceLock) {
            sInstance = instance;
        }
    }

    private final IFeatureFlagsCallback mIFeatureFlagsCallback = new IFeatureFlagsCallback.Stub() {
        @Override
        public void onFlagChange(SyncableFlag flag) {
            for (Flag<?> f : mKnownFlags) {
                if (flagEqualsSyncableFlag(f, flag)) {
                    if (f instanceof DynamicFlag<?>) {
                        if (f instanceof DynamicBooleanFlag) {
                            String value = flag.getValue();
                            if (value == null) {  // Null means any existing overrides were erased.
                                value = ((DynamicBooleanFlag) f).getDefault().toString();
                            }
                            addBooleanOverride(flag.getNamespace(), flag.getName(), value);
                        }
                        FeatureFlags.this.onFlagChange((DynamicFlag<?>) f);
                    }
                    break;
                }
            }
        }
    };

    private FeatureFlags() {
        this(null);
    }

    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public FeatureFlags(IFeatureFlags iFeatureFlags) {
        mIFeatureFlags = iFeatureFlags;

        if (mIFeatureFlags != null) {
            try {
                mIFeatureFlags.registerCallback(mIFeatureFlagsCallback);
            } catch (RemoteException e) {
                // Shouldn't happen with things passed into tests.
                Log.e(TAG, "Could not register callbacks!", e);
            }
        }
    }

    /**
     * Construct a new {@link BooleanFlag}.
     *
     * Use this instead of constructing a {@link BooleanFlag} directly, as it registers the flag
     * with the internals of the flagging system.
     */
    @NonNull
    public static BooleanFlag booleanFlag(
            @NonNull String namespace, @NonNull String name, boolean def) {
        return getInstance().addFlag(new BooleanFlag(namespace, name, def));
    }

    /**
     * Construct a new {@link FusedOffFlag}.
     *
     * Use this instead of constructing a {@link FusedOffFlag} directly, as it registers the
     * flag with the internals of the flagging system.
     */
    @NonNull
    public static FusedOffFlag fusedOffFlag(@NonNull String namespace, @NonNull String name) {
        return getInstance().addFlag(new FusedOffFlag(namespace, name));
    }

    /**
     * Construct a new {@link FusedOnFlag}.
     *
     * Use this instead of constructing a {@link FusedOnFlag} directly, as it registers the flag
     * with the internals of the flagging system.
     */
    @NonNull
    public static FusedOnFlag fusedOnFlag(@NonNull String namespace, @NonNull String name) {
        return getInstance().addFlag(new FusedOnFlag(namespace, name));
    }

    /**
     * Construct a new {@link DynamicBooleanFlag}.
     *
     * Use this instead of constructing a {@link DynamicBooleanFlag} directly, as it registers
     * the flag with the internals of the flagging system.
     */
    @NonNull
    public static DynamicBooleanFlag dynamicBooleanFlag(
            @NonNull String namespace, @NonNull String name, boolean def) {
        return getInstance().addFlag(new DynamicBooleanFlag(namespace, name, def));
    }

    /**
     * Add a listener to be alerted when a {@link DynamicFlag} changes.
     *
     * See also {@link #removeChangeListener(ChangeListener)}.
     *
     * @param listener The listener to add.
     */
    public void addChangeListener(@NonNull ChangeListener listener) {
        mListeners.add(listener);
    }

    /**
     * Remove a listener that was added earlier.
     *
     * See also {@link #addChangeListener(ChangeListener)}.
     *
     * @param listener The listener to remove.
     */
    public void removeChangeListener(@NonNull ChangeListener listener) {
        mListeners.remove(listener);
    }

    protected void onFlagChange(@NonNull DynamicFlag<?> flag) {
        for (ChangeListener l : mListeners) {
            l.onFlagChanged(flag);
        }
    }

    /**
     * Returns whether the supplied flag is true or not.
     *
     * {@link BooleanFlag} should only be used in debug builds. They do not get optimized out.
     *
     * The first time a flag is read, its value is cached for the lifetime of the process.
     */
    public boolean isEnabled(@NonNull BooleanFlag flag) {
        return getBooleanInternal(flag);
    }

    /**
     * Returns whether the supplied flag is true or not.
     *
     * Always returns false.
     */
    public boolean isEnabled(@NonNull FusedOffFlag flag) {
        return false;
    }

    /**
     * Returns whether the supplied flag is true or not.
     *
     * Always returns true;
     */
    public boolean isEnabled(@NonNull FusedOnFlag flag) {
        return true;
    }

    /**
     * Returns whether the supplied flag is true or not.
     *
     * Can return a different value for the flag each time it is called if an override comes in.
     */
    public boolean isCurrentlyEnabled(@NonNull DynamicBooleanFlag flag) {
        return getBooleanInternal(flag);
    }

    private boolean getBooleanInternal(Flag<Boolean> flag) {
        sync();
        Map<String, Boolean> ns = mBooleanOverrides.get(flag.getNamespace());
        Boolean value = null;
        if (ns != null) {
            value = ns.get(flag.getName());
        }
        if (value == null) {
            throw new IllegalStateException("Boolean flag being read but was not synced: " + flag);
        }

        return value;
    }

    private <T extends Flag<?>> T addFlag(T flag)  {
        synchronized (FeatureFlags.class) {
            mDirtyFlags.add(flag);
            mKnownFlags.add(flag);
        }
        return flag;
    }

    /**
     * Sync any known flags that have not yet been synced.
     *
     * This is called implicitly when any flag is read, and is not generally needed except in
     * exceptional circumstances.
     */
    public void sync() {
        synchronized (FeatureFlags.class) {
            if (mDirtyFlags.isEmpty()) {
                return;
            }
            syncInternal(mDirtyFlags);
            mDirtyFlags.clear();
        }
    }

    /**
     * Called when new flags have been declared. Gives the implementation a chance to act on them.
     *
     * Guaranteed to be called from a synchronized, thread-safe context.
     */
    protected void syncInternal(Set<Flag<?>> dirtyFlags) {
        IFeatureFlags iFeatureFlags = bind();
        List<SyncableFlag> syncableFlags = new ArrayList<>();
        for (Flag<?> f : dirtyFlags) {
            syncableFlags.add(flagToSyncableFlag(f));
        }

        List<SyncableFlag> serverFlags = List.of();  // Need to initialize the list with something.
        try {
            // New values come back from the service.
            serverFlags = iFeatureFlags.syncFlags(syncableFlags);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }

        for (Flag<?> f : dirtyFlags) {
            boolean found = false;
            for (SyncableFlag sf : serverFlags) {
                if (flagEqualsSyncableFlag(f, sf)) {
                    if (f instanceof BooleanFlag || f instanceof DynamicBooleanFlag) {
                        addBooleanOverride(sf.getNamespace(), sf.getName(), sf.getValue());
                    }
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (f instanceof BooleanFlag) {
                    addBooleanOverride(
                            f.getNamespace(),
                            f.getName(),
                            ((BooleanFlag) f).getDefault() ? "true" : "false");
                }
            }
        }
    }

    private void addBooleanOverride(String namespace, String name, String override) {
        Map<String, Boolean> nsOverrides = mBooleanOverrides.get(namespace);
        if (nsOverrides == null) {
            nsOverrides = new HashMap<>();
            mBooleanOverrides.put(namespace, nsOverrides);
        }
        nsOverrides.put(name, parseBoolean(override));
    }

    private SyncableFlag flagToSyncableFlag(Flag<?> f) {
        return new SyncableFlag(
                f.getNamespace(),
                f.getName(),
                f.getDefault().toString(),
                f instanceof DynamicFlag<?>);
    }

    private IFeatureFlags bind() {
        if (mIFeatureFlags == null) {
            mIFeatureFlags = IFeatureFlags.Stub.asInterface(
                    ServiceManager.getService(Context.FEATURE_FLAGS_SERVICE));
            try {
                mIFeatureFlags.registerCallback(mIFeatureFlagsCallback);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to listen for flag changes!");
            }
        }

        return mIFeatureFlags;
    }

    static boolean parseBoolean(String value) {
        // Check for a truish string.
        boolean result = value.equalsIgnoreCase("true")
                || value.equals("1")
                || value.equalsIgnoreCase("t")
                || value.equalsIgnoreCase("on");
        if (!result) {  // Expect a falsish string, else log an error.
            if (!(value.equalsIgnoreCase("false")
                    || value.equals("0")
                    || value.equalsIgnoreCase("f")
                    || value.equalsIgnoreCase("off"))) {
                Log.e(TAG,
                        "Tried parsing " + value + " as boolean but it doesn't look like one. "
                                + "Value expected to be one of true|false, 1|0, t|f, on|off.");
            }
        }
        return result;
    }

    private static boolean flagEqualsSyncableFlag(Flag<?> f, SyncableFlag sf) {
        return f.getName().equals(sf.getName()) && f.getNamespace().equals(sf.getNamespace());
    }


    /**
     * A simpler listener that is alerted when a {@link DynamicFlag} changes.
     *
     * See {@link #addChangeListener(ChangeListener)}
     */
    public interface ChangeListener {
        /**
         * Called when a {@link DynamicFlag} changes.
         *
         * @param flag The flag that has changed.
         */
        void onFlagChanged(DynamicFlag<?> flag);
    }
}
