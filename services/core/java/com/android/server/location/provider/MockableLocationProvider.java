/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.provider;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.annotation.Nullable;
import android.location.Location;
import android.location.LocationResult;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Bundle;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.Set;

/**
 * Represents a location provider that may switch between a mock implementation and a real
 * implementation. Requires owners to provide a lock object that will be used internally and held
 * for the duration of all listener callbacks. Owners are reponsible for ensuring this cannot lead
 * to deadlock.
 *
 * In order to ensure deadlock does not occur, the owner must validate that the ONLY lock which can
 * be held BOTH when calling into this class AND when receiving a callback from this class is the
 * lock given to this class via the constructor. Holding any other lock is ok as long as there is no
 * possibility that it can be obtained within both codepaths.
 *
 * Holding the given lock guarantees atomicity of any operations on this class for the duration.
 *
 * @hide
 */
public class MockableLocationProvider extends AbstractLocationProvider {

    final Object mOwnerLock;

    @GuardedBy("mOwnerLock")
    private @Nullable AbstractLocationProvider mProvider;
    @GuardedBy("mOwnerLock")
    private @Nullable AbstractLocationProvider mRealProvider;
    @GuardedBy("mOwnerLock")
    private @Nullable MockLocationProvider mMockProvider;

    @GuardedBy("mOwnerLock")
    private boolean mStarted;
    @GuardedBy("mOwnerLock")
    private ProviderRequest mRequest;

    /**
     * The given lock object will be held any time the listener is invoked, and may also be acquired
     * and released during the course of invoking any public methods. Holding the given lock ensures
     * that provider state cannot change except as result of an explicit call by the owner of the
     * lock into this class. The client is reponsible for ensuring this cannot cause deadlock.
     *
     * The client should expect that it may being to receive callbacks as soon as this constructor
     * is invoked.
     */
    public MockableLocationProvider(Object ownerLock) {
        // using a direct executor is acceptable because all inbound calls are delegated to the
        // actual provider implementations which will use their own executors
        super(DIRECT_EXECUTOR, null, null, Collections.emptySet());
        mOwnerLock = ownerLock;
        mRequest = ProviderRequest.EMPTY_REQUEST;
    }

    /**
     * Returns the current provider implementation. May be null if there is no current
     * implementation.
     */
    @Nullable
    public AbstractLocationProvider getProvider() {
        synchronized (mOwnerLock) {
            return mProvider;
        }
    }

    /**
     * Sets the real provider implementation, replacing any previous real provider implementation.
     * May cause an inline invocation of {@link Listener#onStateChanged(State, State)} if this
     * results in a state change.
     */
    public void setRealProvider(@Nullable AbstractLocationProvider provider) {
        synchronized (mOwnerLock) {
            if (mRealProvider == provider) {
                return;
            }

            mRealProvider = provider;
            if (!isMock()) {
                setProviderLocked(mRealProvider);
            }
        }
    }

    /**
     * Sets the mock provider implementation, replacing any previous mock provider implementation.
     * Mock implementations are always used instead of real implementations if set. May cause an
     * inline invocation of {@link Listener#onStateChanged(State, State)} if this results in a
     * state change.
     */
    public void setMockProvider(@Nullable MockLocationProvider provider) {
        synchronized (mOwnerLock) {
            if (mMockProvider == provider) {
                return;
            }

            mMockProvider = provider;
            if (mMockProvider != null) {
                setProviderLocked(mMockProvider);
            } else {
                setProviderLocked(mRealProvider);
            }
        }
    }

    @GuardedBy("mOwnerLock")
    private void setProviderLocked(@Nullable AbstractLocationProvider provider) {
        if (mProvider == provider) {
            return;
        }

        AbstractLocationProvider oldProvider = mProvider;
        mProvider = provider;

        if (oldProvider != null) {
            // do this after switching the provider - so even if the old provider is using a direct
            // executor, if it re-enters this class within setRequest(), it will be ignored
            oldProvider.getController().setListener(null);
            if (oldProvider.getController().isStarted()) {
                oldProvider.getController().setRequest(ProviderRequest.EMPTY_REQUEST);
                oldProvider.getController().stop();
            }
        }

        State newState;
        if (mProvider != null) {
            newState = mProvider.getController().setListener(new ListenerWrapper(mProvider));
            if (mStarted) {
                if (!mProvider.getController().isStarted()) {
                    mProvider.getController().start();
                }
                mProvider.getController().setRequest(mRequest);
            } else {
                if (mProvider.getController().isStarted()) {
                    mProvider.getController().setRequest(ProviderRequest.EMPTY_REQUEST);
                    mProvider.getController().stop();
                }
            }
        } else {
            newState = State.EMPTY_STATE;
        }

        setState(prevState -> newState);
    }

    /**
     * Returns true if the current active provider implementation is the mock implementation, and
     * false otherwise.
     */
    public boolean isMock() {
        synchronized (mOwnerLock) {
            return mMockProvider != null && mProvider == mMockProvider;
        }
    }

    /**
     * Sets the mock provider implementation's allowed state. Will throw an exception if the mock
     * provider is not currently the active implementation.
     */
    public void setMockProviderAllowed(boolean allowed) {
        synchronized (mOwnerLock) {
            Preconditions.checkState(isMock());
            mMockProvider.setProviderAllowed(allowed);
        }
    }
    /**
     * Sets the mock provider implementation's location. Will throw an exception if the mock
     * provider is not currently the active implementation.
     */
    public void setMockProviderLocation(Location location) {
        synchronized (mOwnerLock) {
            Preconditions.checkState(isMock());
            mMockProvider.setProviderLocation(location);
        }
    }

    /**
     * Returns the current location request.
     */
    public ProviderRequest getCurrentRequest() {
        synchronized (mOwnerLock) {
            return mRequest;
        }
    }

    @Override
    protected void onStart() {
        synchronized (mOwnerLock) {
            Preconditions.checkState(!mStarted);

            mStarted = true;

            if (mProvider != null) {
                mProvider.getController().start();
                if (!mRequest.equals(ProviderRequest.EMPTY_REQUEST)) {
                    mProvider.getController().setRequest(mRequest);
                }
            }
        }
    }

    @Override
    protected void onStop() {
        synchronized (mOwnerLock) {
            Preconditions.checkState(mStarted);

            mStarted = false;

            if (mProvider != null) {
                if (!mRequest.equals(ProviderRequest.EMPTY_REQUEST)) {
                    mProvider.getController().setRequest(ProviderRequest.EMPTY_REQUEST);
                }
                mProvider.getController().stop();
            }

            mRequest = ProviderRequest.EMPTY_REQUEST;
        }
    }

    @Override
    protected void onSetRequest(ProviderRequest request) {
        synchronized (mOwnerLock) {
            if (request == mRequest) {
                return;
            }

            mRequest = request;

            if (mProvider != null) {
                mProvider.getController().setRequest(request);
            }
        }
    }

    @Override
    protected void onFlush(Runnable callback) {
        synchronized (mOwnerLock) {
            if (mProvider != null) {
                mProvider.getController().flush(callback);
            } else {
                callback.run();
            }
        }
    }

    @Override
    protected void onExtraCommand(int uid, int pid, String command, Bundle extras) {
        synchronized (mOwnerLock) {
            if (mProvider != null) {
                mProvider.getController().sendExtraCommand(uid, pid, command, extras);
            }
        }
    }

    /**
     * Dumps the current provider implementation.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        // holding the owner lock outside this could lead to deadlock since we don't run dump on the
        // executor specified by the provider, we run it directly
        Preconditions.checkState(!Thread.holdsLock(mOwnerLock));

        AbstractLocationProvider provider;
        synchronized (mOwnerLock) {
            provider = mProvider;
            pw.println("allowed=" + isAllowed());
            CallerIdentity identity = getIdentity();
            if (identity != null) {
                pw.println("identity=" + identity);
            }
            Set<String> extraAttributionTags = getExtraAttributionTags();
            if (!extraAttributionTags.isEmpty()) {
                pw.println("extra attribution tags=" + extraAttributionTags);
            }
            ProviderProperties properties = getProperties();
            if (properties != null) {
                pw.println("properties=" + properties);
            }
        }

        if (provider != null) {
            // dump outside the lock in case the provider wants to acquire its own locks, and since
            // the default provider dump behavior does not move things onto the provider thread...
            provider.dump(fd, pw, args);
        }
    }

    // ensures that callbacks from the incorrect provider are never visible to clients - this
    // requires holding the owner's lock for the duration of the callback
    private class ListenerWrapper implements Listener {

        private final AbstractLocationProvider mListenerProvider;

        ListenerWrapper(AbstractLocationProvider listenerProvider) {
            mListenerProvider = listenerProvider;
        }

        @Override
        public final void onStateChanged(State oldState, State newState) {
            synchronized (mOwnerLock) {
                if (mListenerProvider != mProvider) {
                    return;
                }

                setState(prevState -> newState);
            }
        }

        @Override
        public final void onReportLocation(LocationResult locationResult) {
            synchronized (mOwnerLock) {
                if (mListenerProvider != mProvider) {
                    return;
                }

                reportLocation(locationResult);
            }
        }
    }
}
