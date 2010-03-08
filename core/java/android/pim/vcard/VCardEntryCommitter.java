/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.pim.vcard;

import android.content.ContentResolver;
import android.net.Uri;
import android.util.Log;

/**
 * <P>
 * {@link VCardEntryHandler} implementation which commits the entry to ContentResolver.
 * </P>
 * <P>
 * Note:<BR />
 * Each vCard may contain big photo images encoded by BASE64,
 * If we store all vCard entries in memory, OutOfMemoryError may be thrown.
 * Thus, this class push each VCard entry into ContentResolver immediately.
 * </P>
 */
public class VCardEntryCommitter implements VCardEntryHandler {
    public static String LOG_TAG = "VCardEntryComitter";

    private final ContentResolver mContentResolver;
    private long mTimeToCommit;
    private Uri mLastCreatedUri;

    public VCardEntryCommitter(ContentResolver resolver) {
        mContentResolver = resolver;
    }

    public void onStart() {
    }

    public void onEnd() {
        if (VCardConfig.showPerformanceLog()) {
            Log.d(LOG_TAG, String.format("time to commit entries: %d ms", mTimeToCommit));
        }
    }

    public void onEntryCreated(final VCardEntry contactStruct) {
        long start = System.currentTimeMillis();
        mLastCreatedUri = contactStruct.pushIntoContentResolver(mContentResolver);
        mTimeToCommit += System.currentTimeMillis() - start;
    }

    public Uri getLastCreatedUri() {
        return mLastCreatedUri;
    }
}