/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.documentsui;

import android.content.AsyncTaskLoader;
import android.content.Context;
import android.database.ContentObserver;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;

/**
 * Loader that derives its data from a Uri. Watches for {@link ContentObserver}
 * changes while started, manages {@link CancellationSignal}, and caches
 * returned results.
 */
public abstract class UriDerivativeLoader<P, R> extends AsyncTaskLoader<R> {
    final ForceLoadContentObserver mObserver;

    private final P mParam;

    private R mResult;
    private CancellationSignal mCancellationSignal;

    @Override
    public final R loadInBackground() {
        synchronized (this) {
            if (isLoadInBackgroundCanceled()) {
                throw new OperationCanceledException();
            }
            mCancellationSignal = new CancellationSignal();
        }
        try {
            return loadInBackground(mParam, mCancellationSignal);
        } finally {
            synchronized (this) {
                mCancellationSignal = null;
            }
        }
    }

    public abstract R loadInBackground(P param, CancellationSignal signal);

    @Override
    public void cancelLoadInBackground() {
        super.cancelLoadInBackground();

        synchronized (this) {
            if (mCancellationSignal != null) {
                mCancellationSignal.cancel();
            }
        }
    }

    @Override
    public void deliverResult(R result) {
        if (isReset()) {
            closeQuietly(result);
            return;
        }
        R oldResult = mResult;
        mResult = result;

        if (isStarted()) {
            super.deliverResult(result);
        }

        if (oldResult != null && oldResult != result) {
            closeQuietly(oldResult);
        }
    }

    public UriDerivativeLoader(Context context, P param) {
        super(context);
        mObserver = new ForceLoadContentObserver();
        mParam = param;
    }

    @Override
    protected void onStartLoading() {
        if (mResult != null) {
            deliverResult(mResult);
        }
        if (takeContentChanged() || mResult == null) {
            forceLoad();
        }
    }

    @Override
    protected void onStopLoading() {
        cancelLoad();
    }

    @Override
    public void onCanceled(R result) {
        closeQuietly(result);
    }

    @Override
    protected void onReset() {
        super.onReset();

        // Ensure the loader is stopped
        onStopLoading();

        closeQuietly(mResult);
        mResult = null;

        getContext().getContentResolver().unregisterContentObserver(mObserver);
    }

    private void closeQuietly(R result) {
        if (result instanceof AutoCloseable) {
            try {
                ((AutoCloseable) result).close();
            } catch (RuntimeException rethrown) {
                throw rethrown;
            } catch (Exception ignored) {
            }
        }
    }
}
