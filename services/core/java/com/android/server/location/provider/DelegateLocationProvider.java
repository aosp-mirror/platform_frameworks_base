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

import android.location.LocationResult;
import android.location.provider.ProviderRequest;
import android.os.Bundle;

import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.concurrent.Executor;

/**
 * Helper class for wrapping location providers. Subclasses MUST ensure that
 * {@link #initializeDelegate()} is invoked before the delegate is used.
 */
class DelegateLocationProvider extends AbstractLocationProvider
        implements AbstractLocationProvider.Listener {

    private final Object mInitializationLock = new Object();

    protected final AbstractLocationProvider mDelegate;

    private boolean mInitialized = false;

    DelegateLocationProvider(Executor executor, AbstractLocationProvider delegate) {
        super(executor, null, null, Collections.emptySet());

        mDelegate = delegate;
    }

    /**
     * This function initializes state from the delegate and allows all other callbacks to be
     * immediately invoked. If this method is invoked from a subclass constructor, it should be the
     * last statement in the constructor since it allows the subclass' reference to escape.
     */
    protected void initializeDelegate() {
        synchronized (mInitializationLock) {
            Preconditions.checkState(!mInitialized);
            setState(previousState -> mDelegate.getController().setListener(this));
            mInitialized = true;
        }
    }

    // must be invoked in every listener callback to ensure they don't run until initialized
    protected final void waitForInitialization() {
        // callbacks can start coming as soon as the setListener() call in initializeDelegate
        // completes - but we can't allow any to proceed until the setState call afterwards
        // completes. acquiring the initialization lock here blocks until initialization is
        // complete, and we verify this wasn't called before initializeDelegate for some reason.
        synchronized (mInitializationLock) {
            Preconditions.checkState(mInitialized);
        }
    }

    @Override
    public void onStateChanged(State oldState, State newState) {
        waitForInitialization();
        setState(previousState -> newState);
    }

    @Override
    public void onReportLocation(LocationResult locationResult) {
        waitForInitialization();
        reportLocation(locationResult);
    }

    @Override
    protected void onStart() {
        Preconditions.checkState(mInitialized);
        mDelegate.getController().start();
    }

    @Override
    protected void onStop() {
        Preconditions.checkState(mInitialized);
        mDelegate.getController().stop();
    }

    @Override
    protected void onSetRequest(ProviderRequest request) {
        Preconditions.checkState(mInitialized);
        mDelegate.getController().setRequest(request);
    }

    @Override
    protected void onFlush(Runnable callback) {
        Preconditions.checkState(mInitialized);
        mDelegate.getController().flush(callback);
    }

    @Override
    protected void onExtraCommand(int uid, int pid, String command, Bundle extras) {
        Preconditions.checkState(mInitialized);
        mDelegate.getController().sendExtraCommand(uid, pid, command, extras);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        Preconditions.checkState(mInitialized);
        mDelegate.dump(fd, pw, args);
    }
}
