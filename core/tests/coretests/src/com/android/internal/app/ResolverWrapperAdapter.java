/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

import androidx.test.espresso.idling.CountingIdlingResource;

import com.android.internal.app.chooser.DisplayResolveInfo;

import java.util.List;

public class ResolverWrapperAdapter extends ResolverListAdapter {

    private CountingIdlingResource mLabelIdlingResource =
            new CountingIdlingResource("LoadLabelTask");

    public ResolverWrapperAdapter(Context context,
            List<Intent> payloadIntents,
            Intent[] initialIntents,
            List<ResolveInfo> rList, boolean filterLastUsed,
            ResolverListController resolverListController,
            ResolverListCommunicator resolverListCommunicator) {
        super(context, payloadIntents, initialIntents, rList, filterLastUsed,
                resolverListController, resolverListCommunicator, false);
    }

    public CountingIdlingResource getLabelIdlingResource() {
        return mLabelIdlingResource;
    }

    @Override
    protected LoadLabelTask createLoadLabelTask(DisplayResolveInfo info) {
        return new LoadLabelWrapperTask(info);
    }

    class LoadLabelWrapperTask extends LoadLabelTask {

        protected LoadLabelWrapperTask(DisplayResolveInfo dri) {
            super(dri);
        }

        @Override
        protected void onPreExecute() {
            mLabelIdlingResource.increment();
        }

        @Override
        protected void onPostExecute(CharSequence[] result) {
            super.onPostExecute(result);
            mLabelIdlingResource.decrement();
        }
    }
}
