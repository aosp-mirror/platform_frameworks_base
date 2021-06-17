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
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;
import android.util.Log;
import android.view.contentcapture.ContentCaptureManager;
import android.view.contentcapture.ContentCaptureManager.ContentCaptureClient;

import com.android.internal.annotations.VisibleForTesting;

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
     * List of activities explicitly allowlisted for content capture (or {@code null} if allowlisted
     * for all acitivites in the package).
     */
    @Nullable
    @SuppressLint("NullableCollection")
    public final ArraySet<ComponentName> whitelistedComponents;

    /**
     * Used to enable just a small set of APIs so it can used by activities belonging to the
     * content capture service APK.
     */
    public final boolean lite;

    /**
     * Constructor for "lite" objects that are just used to enable a {@link ContentCaptureManager}
     * for contexts belonging to the content capture service app.
     */
    public ContentCaptureOptions(int loggingLevel) {
        this(/* lite= */ true, loggingLevel, /* maxBufferSize= */ 0,
                /* idleFlushingFrequencyMs= */ 0, /* textChangeFlushingFrequencyMs= */ 0,
                /* logHistorySize= */ 0, /* whitelistedComponents= */ null);
    }

    /**
     * Default constructor.
     */
    public ContentCaptureOptions(int loggingLevel, int maxBufferSize, int idleFlushingFrequencyMs,
            int textChangeFlushingFrequencyMs, int logHistorySize,
            @SuppressLint("NullableCollection")
            @Nullable ArraySet<ComponentName> whitelistedComponents) {
        this(/* lite= */ false, loggingLevel, maxBufferSize, idleFlushingFrequencyMs,
                textChangeFlushingFrequencyMs, logHistorySize, whitelistedComponents);
    }

    /** @hide */
    @VisibleForTesting
    public ContentCaptureOptions(@Nullable ArraySet<ComponentName> whitelistedComponents) {
        this(ContentCaptureManager.LOGGING_LEVEL_VERBOSE,
                ContentCaptureManager.DEFAULT_MAX_BUFFER_SIZE,
                ContentCaptureManager.DEFAULT_IDLE_FLUSHING_FREQUENCY_MS,
                ContentCaptureManager.DEFAULT_TEXT_CHANGE_FLUSHING_FREQUENCY_MS,
                ContentCaptureManager.DEFAULT_LOG_HISTORY_SIZE, whitelistedComponents);
    }

    private ContentCaptureOptions(boolean lite, int loggingLevel, int maxBufferSize,
            int idleFlushingFrequencyMs, int textChangeFlushingFrequencyMs, int logHistorySize,
            @Nullable ArraySet<ComponentName> whitelistedComponents) {
        this.lite = lite;
        this.loggingLevel = loggingLevel;
        this.maxBufferSize = maxBufferSize;
        this.idleFlushingFrequencyMs = idleFlushingFrequencyMs;
        this.textChangeFlushingFrequencyMs = textChangeFlushingFrequencyMs;
        this.logHistorySize = logHistorySize;
        this.whitelistedComponents = whitelistedComponents;
    }

    public static ContentCaptureOptions forWhitelistingItself() {
        final ActivityThread at = ActivityThread.currentActivityThread();
        if (at == null) {
            throw new IllegalStateException("No ActivityThread");
        }

        final String packageName = at.getApplication().getPackageName();

        if (!"android.contentcaptureservice.cts".equals(packageName)
                && !"android.translation.cts".equals(packageName)) {
            Log.e(TAG, "forWhitelistingItself(): called by " + packageName);
            throw new SecurityException("Thou shall not pass!");
        }

        final ContentCaptureOptions options =
                new ContentCaptureOptions(/* whitelistedComponents= */ null);
        // Always log, as it's used by test only
        Log.i(TAG, "forWhitelistingItself(" + packageName + "): " + options);

        return options;
    }

    /** @hide */
    @VisibleForTesting
    public boolean isWhitelisted(@NonNull Context context) {
        if (whitelistedComponents == null) return true; // whole package is allowlisted
        final ContentCaptureClient client = context.getContentCaptureClient();
        if (client == null) {
            // Shouldn't happen, but it doesn't hurt to check...
            Log.w(TAG, "isWhitelisted(): no ContentCaptureClient on " + context);
            return false;
        }
        return whitelistedComponents.contains(client.contentCaptureClientGetComponentName());
    }

    @Override
    public String toString() {
        if (lite) {
            return "ContentCaptureOptions [loggingLevel=" + loggingLevel + " (lite)]";
        }
        final StringBuilder string = new StringBuilder("ContentCaptureOptions [");
        string.append("loggingLevel=").append(loggingLevel)
            .append(", maxBufferSize=").append(maxBufferSize)
            .append(", idleFlushingFrequencyMs=").append(idleFlushingFrequencyMs)
            .append(", textChangeFlushingFrequencyMs=").append(textChangeFlushingFrequencyMs)
            .append(", logHistorySize=").append(logHistorySize);
        if (whitelistedComponents != null) {
            string.append(", whitelisted=").append(whitelistedComponents);
        }
        return string.append(']').toString();
    }

    /** @hide */
    public void dumpShort(@NonNull PrintWriter pw) {
        pw.print("logLvl="); pw.print(loggingLevel);
        if (lite) {
            pw.print(", lite");
            return;
        }
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
        parcel.writeBoolean(lite);
        parcel.writeInt(loggingLevel);
        if (lite) return;

        parcel.writeInt(maxBufferSize);
        parcel.writeInt(idleFlushingFrequencyMs);
        parcel.writeInt(textChangeFlushingFrequencyMs);
        parcel.writeInt(logHistorySize);
        parcel.writeArraySet(whitelistedComponents);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ContentCaptureOptions> CREATOR =
            new Parcelable.Creator<ContentCaptureOptions>() {

                @Override
                public ContentCaptureOptions createFromParcel(Parcel parcel) {
                    final boolean lite = parcel.readBoolean();
                    final int loggingLevel = parcel.readInt();
                    if (lite) {
                        return new ContentCaptureOptions(loggingLevel);
                    }
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
