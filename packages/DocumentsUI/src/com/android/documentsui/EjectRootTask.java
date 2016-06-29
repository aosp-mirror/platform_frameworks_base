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

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import java.util.function.Consumer;

final class EjectRootTask
        extends CheckedTask<Void, Boolean> {
    private final String mAuthority;
    private final String mRootId;
    private final Consumer<Boolean> mListener;
    private Context mContext;

    public EjectRootTask(Check check,
            String authority,
            String rootId,
            Context context,
            Consumer<Boolean> listener) {
        super(check);
        mAuthority = authority;
        mRootId = rootId;
        mContext = context;
        mListener = listener;
    }

    @Override
    protected Boolean run(Void... params) {
        final ContentResolver resolver = mContext.getContentResolver();

        Uri rootUri = DocumentsContract.buildRootUri(mAuthority, mRootId);
        ContentProviderClient client = null;
        try {
            client = DocumentsApplication.acquireUnstableProviderOrThrow(
                    resolver, mAuthority);
            return DocumentsContract.ejectRoot(client, rootUri);
        } catch (Exception e) {
            Log.w(Shared.TAG, "Failed to eject root", e);
        } finally {
            ContentProviderClient.releaseQuietly(client);
        }

        return false;
    }

    @Override
    protected void finish(Boolean ejected) {
        mListener.accept(ejected);
    }
}