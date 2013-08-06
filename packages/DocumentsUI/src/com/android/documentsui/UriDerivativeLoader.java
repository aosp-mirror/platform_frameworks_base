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
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;

/**
 * Loader that derives its data from a Uri. Watches for {@link ContentObserver}
 * changes while started, manages {@link CancellationSignal}, and caches
 * returned results.
 */
public abstract class UriDerivativeLoader<T> extends AsyncTaskLoader<T> {
    private final ForceLoadContentObserver mObserver;
    private boolean mObserving;

    private final Uri mUri;

    private T mResult;
    private CancellationSignal mCancellationSignal;

    @Override
    public final T loadInBackground() {
        synchronized (this) {
            if (isLoadInBackgroundCanceled()) {
                throw new OperationCanceledException();
            }
            mCancellationSignal = new CancellationSignal();
        }
        try {
            return loadInBackground(mUri, mCancellationSignal);
        } finally {
            synchronized (this) {
                mCancellationSignal = null;
            }
        }
    }

    public abstract T loadInBackground(Uri uri, CancellationSignal signal);

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
    public void deliverResult(T result) {
        if (isReset()) {
            closeQuietly(result);
            return;
        }
        T oldResult = mResult;
        mResult = result;

        if (isStarted()) {
            super.deliverResult(result);
        }

        if (oldResult != null && oldResult != result) {
            closeQuietly(oldResult);
        }
    }

    public UriDerivativeLoader(Context context, Uri uri) {
        super(context);
        mObserver = new ForceLoadContentObserver();
        mUri = uri;
    }

    @Override
    protected void onStartLoading() {
        if (!mObserving) {
            getContext().getContentResolver().registerContentObserver(mUri, false, mObserver);
            mObserving = true;
        }
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
    public void onCanceled(T result) {
        closeQuietly(result);
    }

    @Override
    protected void onReset() {
        super.onReset();

        // Ensure the loader is stopped
        onStopLoading();

        closeQuietly(mResult);
        mResult = null;

        if (mObserving) {
            getContext().getContentResolver().unregisterContentObserver(mObserver);
            mObserving = false;
        }
    }

    private void closeQuietly(T result) {
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
