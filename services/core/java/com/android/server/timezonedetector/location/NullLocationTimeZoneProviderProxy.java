/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.timezonedetector.location;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteCallback;
import android.service.timezone.TimeZoneProviderService;
import android.util.IndentingPrintWriter;

/**
 * A {@link LocationTimeZoneProviderProxy} that provides minimal responses needed for the {@link
 * BinderLocationTimeZoneProvider} to operate correctly when there is no "real" provider
 * configured / enabled. This can be used during development / testing, or in a production build
 * when the platform supports more providers than are needed for an Android deployment.
 *
 * <p>For example, if the {@link LocationTimeZoneProviderController} supports a primary
 * and a secondary {@link LocationTimeZoneProvider}, but only a primary is configured, the secondary
 * config will be left null and the {@link LocationTimeZoneProviderProxy} implementation will be
 * defaulted to a {@link NullLocationTimeZoneProviderProxy}. The {@link
 * NullLocationTimeZoneProviderProxy} sends a "permanent failure" event immediately after being
 * started for the first time, which ensures the {@link LocationTimeZoneProviderController} won't
 * expect any further {@link TimeZoneProviderEvent}s to come from it, and won't attempt to use it
 * again.
 */
class NullLocationTimeZoneProviderProxy extends LocationTimeZoneProviderProxy {

    /** Creates the instance. */
    NullLocationTimeZoneProviderProxy(
            @NonNull Context context, @NonNull ThreadingDomain threadingDomain) {
        super(context, threadingDomain);
    }

    @Override
    void onInitialize() {
        // No-op
    }

    @Override
    void onDestroy() {
        // No-op
    }

    @Override
    void setRequest(@NonNull TimeZoneProviderRequest request) {
        if (request.sendUpdates()) {
            TimeZoneProviderEvent event = TimeZoneProviderEvent.createPermanentFailureEvent(
                    "Provider is disabled");
            handleTimeZoneProviderEvent(event);
        }
    }

    @Override
    void handleTestCommand(@NonNull TestCommand testCommand, @Nullable RemoteCallback callback) {
        if (callback != null) {
            Bundle result = new Bundle();
            result.putBoolean(TimeZoneProviderService.TEST_COMMAND_RESULT_SUCCESS_KEY, false);
            result.putString(TimeZoneProviderService.TEST_COMMAND_RESULT_ERROR_KEY,
                    "Provider is disabled");
            callback.sendResult(result);
        }
    }

    @Override
    public void dump(@NonNull IndentingPrintWriter ipw, @Nullable String[] args) {
        synchronized (mSharedLock) {
            ipw.println("{NullLocationTimeZoneProviderProxy}");
        }
    }
}
