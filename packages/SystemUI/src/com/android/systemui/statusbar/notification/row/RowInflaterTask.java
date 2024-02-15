/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.asynclayoutinflater.view.AsyncLayoutFactory;
import androidx.asynclayoutinflater.view.AsyncLayoutInflater;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.InflationTask;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import javax.inject.Inject;

/**
 * An inflater task that asynchronously inflates a ExpandableNotificationRow
 */
public class RowInflaterTask implements InflationTask, AsyncLayoutInflater.OnInflateFinishedListener {

    private static final String TAG = "RowInflaterTask";
    private static final boolean TRACE_ORIGIN = true;

    private RowInflationFinishedListener mListener;
    private NotificationEntry mEntry;
    private boolean mCancelled;
    private Throwable mInflateOrigin;

    @Inject
    public RowInflaterTask() {
    }

    /**
     * Inflates a new notificationView. This should not be called twice on this object
     */
    public void inflate(Context context, ViewGroup parent, NotificationEntry entry,
            RowInflationFinishedListener listener) {
        if (TRACE_ORIGIN) {
            mInflateOrigin = new Throwable("inflate requested here");
        }
        mListener = listener;
        AsyncLayoutInflater inflater = com.android.systemui.Flags.notificationRowUserContext()
                ? new AsyncLayoutInflater(context, new RowAsyncLayoutInflater(entry))
                : new AsyncLayoutInflater(context);
        mEntry = entry;
        entry.setInflationTask(this);
        inflater.inflate(R.layout.status_bar_notification_row, parent, this);
    }

    @VisibleForTesting
    static class RowAsyncLayoutInflater implements AsyncLayoutFactory {
        private final NotificationEntry mEntry;

        RowAsyncLayoutInflater(NotificationEntry entry) {
            mEntry = entry;
        }

        @Nullable
        @Override
        public View onCreateView(@Nullable View parent, @NonNull String name,
                @NonNull Context context, @NonNull AttributeSet attrs) {
            if (name.equals(ExpandableNotificationRow.class.getName())) {
                return new ExpandableNotificationRow(context, attrs, mEntry);
            }
            return null;
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull String name, @NonNull Context context,
                @NonNull AttributeSet attrs) {
            return null;
        }
    }

    @Override
    public void abort() {
        mCancelled = true;
    }

    @Override
    public void onInflateFinished(View view, int resid, ViewGroup parent) {
        if (!mCancelled) {
            try {
                mEntry.onInflationTaskFinished();
                mListener.onInflationFinished((ExpandableNotificationRow) view);
            } catch (Throwable t) {
                if (mInflateOrigin != null) {
                    Log.e(TAG, "Error in inflation finished listener: " + t, mInflateOrigin);
                    t.addSuppressed(mInflateOrigin);
                }
                throw t;
            }
        }
    }

    public interface RowInflationFinishedListener {
        void onInflationFinished(ExpandableNotificationRow row);
    }
}
