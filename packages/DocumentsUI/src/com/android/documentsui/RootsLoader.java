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

import com.android.documentsui.BaseActivity.State;
import com.android.documentsui.model.RootInfo;

import java.util.Collection;

public class RootsLoader extends AsyncTaskLoader<Collection<RootInfo>> {
    private final ForceLoadContentObserver mObserver = new ForceLoadContentObserver();

    private final RootsCache mRoots;
    private final State mState;

    private Collection<RootInfo> mResult;

    public RootsLoader(Context context, RootsCache roots, State state) {
        super(context);
        mRoots = roots;
        mState = state;

        getContext().getContentResolver()
                .registerContentObserver(RootsCache.sNotificationUri, false, mObserver);
    }

    @Override
    public final Collection<RootInfo> loadInBackground() {
        return mRoots.getMatchingRootsBlocking(mState);
    }

    @Override
    public void deliverResult(Collection<RootInfo> result) {
        if (isReset()) {
            return;
        }
        Collection<RootInfo> oldResult = mResult;
        mResult = result;

        if (isStarted()) {
            super.deliverResult(result);
        }
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
    protected void onReset() {
        super.onReset();

        // Ensure the loader is stopped
        onStopLoading();

        mResult = null;

        getContext().getContentResolver().unregisterContentObserver(mObserver);
    }
}
