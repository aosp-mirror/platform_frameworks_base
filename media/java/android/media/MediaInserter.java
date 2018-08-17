/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.media;

import android.annotation.UnsupportedAppUsage;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.net.Uri;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A MediaScanner helper class which enables us to do lazy insertion on the
 * given provider. This class manages buffers internally and flushes when they
 * are full. Note that you should call flushAll() after using this class.
 * {@hide}
 */
public class MediaInserter {
    private final HashMap<Uri, List<ContentValues>> mRowMap =
            new HashMap<Uri, List<ContentValues>>();
    private final HashMap<Uri, List<ContentValues>> mPriorityRowMap =
            new HashMap<Uri, List<ContentValues>>();

    private final ContentProviderClient mProvider;
    private final int mBufferSizePerUri;

    public MediaInserter(ContentProviderClient provider, int bufferSizePerUri) {
        mProvider = provider;
        mBufferSizePerUri = bufferSizePerUri;
    }

    public void insert(Uri tableUri, ContentValues values) throws RemoteException {
        insert(tableUri, values, false);
    }

    public void insertwithPriority(Uri tableUri, ContentValues values) throws RemoteException {
        insert(tableUri, values, true);
    }

    private void insert(Uri tableUri, ContentValues values, boolean priority) throws RemoteException {
        HashMap<Uri, List<ContentValues>> rowmap = priority ? mPriorityRowMap : mRowMap;
        List<ContentValues> list = rowmap.get(tableUri);
        if (list == null) {
            list = new ArrayList<ContentValues>();
            rowmap.put(tableUri, list);
        }
        list.add(new ContentValues(values));
        if (list.size() >= mBufferSizePerUri) {
            flushAllPriority();
            flush(tableUri, list);
        }
    }

    @UnsupportedAppUsage
    public void flushAll() throws RemoteException {
        flushAllPriority();
        for (Uri tableUri : mRowMap.keySet()){
            List<ContentValues> list = mRowMap.get(tableUri);
            flush(tableUri, list);
        }
        mRowMap.clear();
    }

    private void flushAllPriority() throws RemoteException {
        for (Uri tableUri : mPriorityRowMap.keySet()){
            List<ContentValues> list = mPriorityRowMap.get(tableUri);
            flush(tableUri, list);
        }
        mPriorityRowMap.clear();
    }

    private void flush(Uri tableUri, List<ContentValues> list) throws RemoteException {
        if (!list.isEmpty()) {
            ContentValues[] valuesArray = new ContentValues[list.size()];
            valuesArray = list.toArray(valuesArray);
            mProvider.bulkInsert(tableUri, valuesArray);
            list.clear();
        }
    }
}
