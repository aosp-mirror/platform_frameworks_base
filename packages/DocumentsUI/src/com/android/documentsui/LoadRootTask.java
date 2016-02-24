/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import com.android.documentsui.model.RootInfo;

final class LoadRootTask extends PairedTask<BaseActivity, Void, RootInfo> {
    private static final String TAG = "RestoreRootTask";

    private final Uri mRootUri;

    public LoadRootTask(BaseActivity activity, Uri rootUri) {
        super(activity);
        mRootUri = rootUri;
    }

    @Override
    protected RootInfo run(Void... params) {
        String rootId = DocumentsContract.getRootId(mRootUri);
        return mOwner.mRoots.getRootOneshot(mRootUri.getAuthority(), rootId);
    }

    @Override
    protected void finish(RootInfo root) {
        mOwner.mState.restored = true;

        if (root != null) {
            mOwner.onRootPicked(root);
        } else {
            Log.w(TAG, "Failed to find root: " + mRootUri);
            mOwner.finish();
        }
    }
}
