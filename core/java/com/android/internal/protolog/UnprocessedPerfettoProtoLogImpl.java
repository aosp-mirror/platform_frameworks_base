/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.protolog;

import android.annotation.NonNull;
import android.os.ServiceManager;

import com.android.internal.protolog.ProtoLogConfigurationServiceImpl.RegisterClientArgs;
import com.android.internal.protolog.common.IProtoLogGroup;

public class UnprocessedPerfettoProtoLogImpl extends PerfettoProtoLogImpl {
    public UnprocessedPerfettoProtoLogImpl(@NonNull IProtoLogGroup[] groups)
            throws ServiceManager.ServiceNotFoundException {
        this(() -> {}, groups);
    }

    public UnprocessedPerfettoProtoLogImpl(@NonNull Runnable cacheUpdater,
            @NonNull IProtoLogGroup[] groups) throws ServiceManager.ServiceNotFoundException {
        super(cacheUpdater, groups);
        readyToLogToLogcat();
    }

    @NonNull
    @Override
    protected RegisterClientArgs createConfigurationServiceRegisterClientArgs() {
        return new RegisterClientArgs();
    }

    @Override
    void dumpViewerConfig() {
        // No-op
    }

    @NonNull
    @Override
    String getLogcatMessageString(@NonNull Message message) {
        String messageString;
        messageString = message.getMessage();

        if (messageString == null) {
            throw new RuntimeException("Failed to decode message for logcat. "
                    + "Message not available without ViewerConfig to decode the hash.");
        }

        return messageString;
    }
}
