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

package android.app.slice;

import android.annotation.NonNull;
import android.content.Context;
import android.metrics.LogMaker;
import android.net.Uri;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

/**
 * Metrics interface for slices.
 *
 * This is called by SliceView, so Slice developers should
 * not need to reference this class.
 *
 * @see androidx.slice.widget.SliceView
 */
public class SliceMetrics {

    private static final String TAG = "SliceMetrics";
    private MetricsLogger mMetricsLogger;
    private LogMaker mLogMaker;

    /**
     * An object to be used throughout the life of a slice to register events.
     */
    public SliceMetrics(@NonNull Context context, @NonNull Uri uri) {
        mMetricsLogger = new MetricsLogger();
        mLogMaker = new LogMaker(MetricsEvent.VIEW_UNKNOWN);
        mLogMaker.addTaggedData(MetricsEvent.FIELD_SLICE_AUTHORITY, uri.getAuthority());
        mLogMaker.addTaggedData(MetricsEvent.FIELD_SLICE_PATH, uri.getPath());
    }

    /**
     * To be called whenever the slice becomes visible to the user.
     */
    public void logVisible() {
        synchronized (mLogMaker)  {
            mLogMaker.setCategory(MetricsEvent.SLICE)
                    .setType(MetricsEvent.TYPE_OPEN);
            mMetricsLogger.write(mLogMaker);
        }
    }

    /**
     * To be called whenever the slice becomes invisible to the user.
     */
    public void logHidden() {
        synchronized (mLogMaker)  {
            mLogMaker.setCategory(MetricsEvent.SLICE)
                    .setType(MetricsEvent.TYPE_CLOSE);
            mMetricsLogger.write(mLogMaker);
        }
    }

    /**
     * To be called whenever the user invokes a discrete action via a slice.
     *
     * <P>
     *     Use this for discrete events like a tap or the end of a drag,
     *     not for a continuous streams of events, such as the motion during a gesture.
     * </P>
     *
     * @see androidx.slice.widget.EventInfo#actionType
     *
     * @param actionType The type of the event.
     * @param subSlice The URI of the sub-slice that is the subject of the interaction.
     */
    public void logTouch(int actionType, @NonNull Uri subSlice) {
        synchronized (mLogMaker)  {
            mLogMaker.setCategory(MetricsEvent.SLICE)
                    .setType(MetricsEvent.TYPE_ACTION)
                    .addTaggedData(MetricsEvent.FIELD_SUBSLICE_AUTHORITY, subSlice.getAuthority())
                    .addTaggedData(MetricsEvent.FIELD_SUBSLICE_PATH, subSlice.getPath());
            mMetricsLogger.write(mLogMaker);
        }
    }
}
