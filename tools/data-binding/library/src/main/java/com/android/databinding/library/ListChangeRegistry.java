/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.databinding.library;

import android.binding.CallbackRegistry;
import android.binding.ObservableList;
import android.binding.OnListChangedListener;
import android.support.v4.util.Pools;

public class ListChangeRegistry
        extends
        CallbackRegistry<OnListChangedListener, ObservableList, ListChangeRegistry.ListChanges> {
    private static final Pools.SynchronizedPool<ListChanges> sListChanges =
            new Pools.SynchronizedPool<>(10);

    private static final int ALL = 0;
    private static final int CHANGED = 1;
    private static final int INSERTED = 2;
    private static final int MOVED = 3;
    private static final int REMOVED = 4;

    private static final CallbackRegistry.NotifierCallback<OnListChangedListener, ObservableList, ListChanges> NOTIFIER_CALLBACK = new CallbackRegistry.NotifierCallback<OnListChangedListener, ObservableList, ListChanges>() {
        @Override
        public void onNotifyCallback(OnListChangedListener callback, ObservableList sender,
                int notificationType, ListChanges listChanges) {
            switch (notificationType) {
                case CHANGED:
                    callback.onItemRangeChanged(listChanges.start, listChanges.count);
                    break;
                case INSERTED:
                    callback.onItemRangeInserted(listChanges.start, listChanges.count);
                    break;
                case MOVED:
                    callback.onItemRangeMoved(listChanges.start, listChanges.to, listChanges.count);
                    break;
                case REMOVED:
                    callback.onItemRangeRemoved(listChanges.start, listChanges.count);
                    break;
                default:
                    callback.onChanged();
                    break;
            }
            if (listChanges != null) {
                sListChanges.release(listChanges);
            }
        }
    };

    public void notifyChanged(ObservableList list) {
        notifyCallbacks(list, ALL, null);
    }

    public void notifyChanged(ObservableList list, int start, int count) {
        ListChanges listChanges = acquire(start, 0, count);
        notifyCallbacks(list, CHANGED, listChanges);
    }

    public void notifyInserted(ObservableList list, int start, int count) {
        ListChanges listChanges = acquire(start, 0, count);
        notifyCallbacks(list, INSERTED, listChanges);
    }

    public void notifyMoved(ObservableList list, int from, int to, int count) {
        ListChanges listChanges = acquire(from, to, count);
        notifyCallbacks(list, MOVED, listChanges);
    }

    public void notifyRemoved(ObservableList list, int start, int count) {
        ListChanges listChanges = acquire(start, 0, count);
        notifyCallbacks(list, REMOVED, listChanges);
    }

    private static ListChanges acquire(int start, int to, int count) {
        ListChanges listChanges = sListChanges.acquire();
        if (listChanges == null) {
            listChanges = new ListChanges();
        }
        listChanges.start = start;
        listChanges.to = to;
        listChanges.count = count;
        return listChanges;
    }

    /**
     * Creates an EventRegistry that notifies the event with notifier.
     */
    public ListChangeRegistry() {
        super(NOTIFIER_CALLBACK);
    }

    static class ListChanges {
        public int start;
        public int count;
        public int to;
    }
}
