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
package android.content;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.util.Log;
import android.view.contentcapture.ContentCaptureManager;

import java.io.PrintWriter;

/**
 * Content capture options for a given package.
 *
 * <p>This object is created by the Content Capture System Service and passed back to the app when
 * the application is created.
 *
 * @hide
 */
@TestApi
public final class ContentCaptureOptions implements Parcelable {

    private static final String TAG = ContentCaptureOptions.class.getSimpleName();

    /**
     * Logging level for {@code logcat} statements.
     */
    public final int loggingLevel;

    /**
     * Maximum number of events that are buffered before sent to the app.
     */
    public final int maxBufferSize;

    /**
     * Frequency the buffer is flushed if idle.
     */
    public final int idleFlushingFrequencyMs;

    /**
     * Frequency the buffer is flushed if last event is a text change.
     */
    public final int textChangeFlushingFrequencyMs;

    /**
     * Size of events that are logging on {@code dump}.
     */
    public final int logHistorySize;

    /**
     * List of activities explicitly whitelisted for content capture (or {@code null} if whitelisted
     * for all acitivites in the package).
     */
    @Nullable
    public final ArraySet<ComponentName> whitelistedComponents;

    public ContentCaptureOptions(int loggingLevel, int maxBufferSize, int idleFlushingFrequencyMs,
            int textChangeFlushingFrequencyMs, int logHistorySize,
            @Nullable ArraySet<ComponentName> whitelistedComponents) {
        this.loggingLevel = loggingLevel;
        this.maxBufferSize = maxBufferSize;
        this.idleFlushingFrequencyMs = idleFlushingFrequencyMs;
        this.textChangeFlushingFrequencyMs = textChangeFlushingFrequencyMs;
        this.logHistorySize = logHistorySize;
        this.whitelistedComponents = whitelistedComponents;
    }

    /**
     * @hide
     */
    @TestApi
    public static ContentCaptureOptions forWhitelistingItself() {
        final ActivityThread at = ActivityThread.currentActivityThread();
        if (at == null) {
            throw new IllegalStateException("No ActivityThread");
        }

        final String packageName = at.getApplication().getPackageName();

        if (!"android.contentcaptureservice.cts".equals(packageName)) {
            Log.e(TAG, "forWhitelistingItself(): called by " + packageName);
            throw new SecurityException("Thou shall not pass!");
        }

        final ContentCaptureOptions options = new ContentCaptureOptions(
                ContentCaptureManager.LOGGING_LEVEL_VERBOSE,
                ContentCaptureManager.DEFAULT_MAX_BUFFER_SIZE,
                ContentCaptureManager.DEFAULT_IDLE_FLUSHING_FREQUENCY_MS,
                ContentCaptureManager.DEFAULT_TEXT_CHANGE_FLUSHING_FREQUENCY_MS,
                ContentCaptureManager.DEFAULT_LOG_HISTORY_SIZE,
                /* whitelistedComponents= */ null);
        // Always log, as it's used by test only
        Log.i(TAG, "forWhitelistingItself(" + packageName + "): " + options);

        return options;
    }

    @Override
    public String toString() {
        return "ContentCaptureOptions [loggingLevel=" + loggingLevel + ", maxBufferSize="
                + maxBufferSize + ", idleFlushingFrequencyMs=" + idleFlushingFrequencyMs
                + ", textChangeFlushingFrequencyMs=" + textChangeFlushingFrequencyMs
                + ", logHistorySize=" + logHistorySize + ", whitelistedComponents="
                + whitelistedComponents + "]";
    }

    /** @hide */
    public void dumpShort(@NonNull PrintWriter pw) {
        pw.print("logLvl="); pw.print(loggingLevel);
        pw.print(", bufferSize="); pw.print(maxBufferSize);
        pw.print(", idle="); pw.print(idleFlushingFrequencyMs);
        pw.print(", textIdle="); pw.print(textChangeFlushingFrequencyMs);
        pw.print(", logSize="); pw.print(logHistorySize);
        if (whitelistedComponents != null) {
            pw.print(", whitelisted="); pw.print(whitelistedComponents);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(loggingLevel);
        parcel.writeInt(maxBufferSize);
        parcel.writeInt(idleFlushingFrequencyMs);
        parcel.writeInt(textChangeFlushingFrequencyMs);
        parcel.writeInt(logHistorySize);
        parcel.writeArraySet(whitelistedComponents);
    }

    public static final Parcelable.Creator<ContentCaptureOptions> CREATOR =
            new Parcelable.Creator<ContentCaptureOptions>() {

                @Override
                public ContentCaptureOptions createFromParcel(Parcel parcel) {
                    final int loggingLevel = parcel.readInt();
                    final int maxBufferSize = parcel.readInt();
                    final int idleFlushingFrequencyMs = parcel.readInt();
                    final int textChangeFlushingFrequencyMs = parcel.readInt();
                    final int logHistorySize = parcel.readInt();
                    @SuppressWarnings("unchecked")
                    final ArraySet<ComponentName> whitelistedComponents =
                            (ArraySet<ComponentName>) parcel.readArraySet(null);
                    return new ContentCaptureOptions(loggingLevel, maxBufferSize,
                            idleFlushingFrequencyMs, textChangeFlushingFrequencyMs, logHistorySize,
                            whitelistedComponents);
                }

                @Override
                public ContentCaptureOptions[] newArray(int size) {
                    return new ContentCaptureOptions[size];
                }

    };
}
