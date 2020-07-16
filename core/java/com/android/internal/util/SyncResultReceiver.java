/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.internal.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcelable;

import com.android.internal.os.IResultReceiver;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A {@code IResultReceiver} implementation that can be used to make "sync" Binder calls by blocking
 * until it receives a result
 *
 * @hide
 */
public final class SyncResultReceiver extends IResultReceiver.Stub {

    private static final String EXTRA = "EXTRA";

    private final CountDownLatch mLatch  = new CountDownLatch(1);
    private final int mTimeoutMs;
    private int mResult;
    private Bundle mBundle;

    /**
     * Default constructor.
     *
     * @param timeoutMs how long to block waiting for {@link IResultReceiver} callbacks.
     */
    public SyncResultReceiver(int timeoutMs) {
        mTimeoutMs = timeoutMs;
    }

    private void waitResult() throws TimeoutException {
        try {
            if (!mLatch.await(mTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new TimeoutException("Not called in " + mTimeoutMs + "ms");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TimeoutException("Interrupted");
        }
    }

    /**
     * Gets the result from an operation that returns an {@code int}.
     */
    public int getIntResult() throws TimeoutException {
        waitResult();
        return mResult;
    }

    /**
     * Gets the result from an operation that returns an {@code String}.
     */
    @Nullable
    public String getStringResult() throws TimeoutException {
        waitResult();
        return mBundle == null ? null : mBundle.getString(EXTRA);
    }

    /**
     * Gets the result from an operation that returns a {@code String[]}.
     */
    @Nullable
    public String[] getStringArrayResult() throws TimeoutException {
        waitResult();
        return mBundle == null ? null : mBundle.getStringArray(EXTRA);
    }

    /**
     * Gets the result from an operation that returns a {@code Parcelable}.
     */
    @Nullable
    public <P extends Parcelable> P getParcelableResult() throws TimeoutException {
        waitResult();
        return mBundle == null ? null : mBundle.getParcelable(EXTRA);
    }

    /**
     * Gets the result from an operation that returns a {@code Parcelable} list.
     */
    @Nullable
    public <P extends Parcelable> ArrayList<P> getParcelableListResult() throws TimeoutException {
        waitResult();
        return mBundle == null ? null : mBundle.getParcelableArrayList(EXTRA);
    }

    /**
     * Gets the optional result from an operation that returns an extra {@code int} (besides the
     * result code).
     *
     * @return value set in the bundle, or {@code defaultValue} when not set.
     */
    public int getOptionalExtraIntResult(int defaultValue) throws TimeoutException {
        waitResult();
        if (mBundle == null || !mBundle.containsKey(EXTRA)) return defaultValue;

        return mBundle.getInt(EXTRA);
    }

    @Override
    public void send(int resultCode, Bundle resultData) {
        mResult = resultCode;
        mBundle = resultData;
        mLatch.countDown();
    }

    /**
     * Creates a bundle for a {@code String} value so it can be retrieved by
     * {@link #getStringResult()}.
     */
    @NonNull
    public static Bundle bundleFor(@Nullable String value) {
        final Bundle bundle = new Bundle();
        bundle.putString(EXTRA, value);
        return bundle;
    }

    /**
     * Creates a bundle for a {@code String[]} value so it can be retrieved by
     * {@link #getStringArrayResult()}.
     */
    @NonNull
    public static Bundle bundleFor(@Nullable String[] value) {
        final Bundle bundle = new Bundle();
        bundle.putStringArray(EXTRA, value);
        return bundle;
    }

    /**
     * Creates a bundle for a {@code Parcelable} value so it can be retrieved by
     * {@link #getParcelableResult()}.
     */
    @NonNull
    public static Bundle bundleFor(@Nullable Parcelable value) {
        final Bundle bundle = new Bundle();
        bundle.putParcelable(EXTRA, value);
        return bundle;
    }

    /**
     * Creates a bundle for a {@code Parcelable} list so it can be retrieved by
     * {@link #getParcelableResult()}.
     */
    @NonNull
    public static Bundle bundleFor(@Nullable ArrayList<? extends Parcelable> value) {
        final Bundle bundle = new Bundle();
        bundle.putParcelableArrayList(EXTRA, value);
        return bundle;
    }

    /**
     * Creates a bundle for an {@code int} value so it can be retrieved by
     * {@link #getParcelableResult()} - typically used to return an extra {@code int} (as the 1st
     * is returned as the result code).
     */
    @NonNull
    public static Bundle bundleFor(int value) {
        final Bundle bundle = new Bundle();
        bundle.putInt(EXTRA, value);
        return bundle;
    }

    /** @hide */
    public static final class TimeoutException extends Exception {
        private TimeoutException(String msg) {
            super(msg);
        }
    }
}
