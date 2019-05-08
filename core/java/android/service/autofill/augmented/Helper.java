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
package android.service.autofill.augmented;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.metrics.LogMaker;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;

/** @hide */
public final class Helper {

    private static final MetricsLogger sMetricsLogger = new MetricsLogger();

    /**
     * Logs a {@code MetricsEvent.AUTOFILL_AUGMENTED_RESPONSE} event.
     */
    public static void logResponse(int type, @NonNull String servicePackageName,
            @NonNull ComponentName componentName, int mSessionId, long durationMs) {
        final LogMaker log = new LogMaker(MetricsEvent.AUTOFILL_AUGMENTED_RESPONSE)
                .setType(type)
                .setComponentName(componentName)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SESSION_ID, mSessionId)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE, servicePackageName)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_DURATION, durationMs);
        System.out.println("LOGGGO: " + log.getEntries()); // felipeal: tmp
        sMetricsLogger.write(log);
    }

    private Helper() {
        throw new UnsupportedOperationException("contains only static methods");
    }
}
