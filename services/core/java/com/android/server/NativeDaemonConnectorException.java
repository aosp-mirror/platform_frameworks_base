/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server;

import android.os.Parcel;

/**
 * An exception that indicates there was an error with a
 * {@link NativeDaemonConnector} operation.
 */
public class NativeDaemonConnectorException extends Exception {
    private String mCmd;
    private NativeDaemonEvent mEvent;

    public NativeDaemonConnectorException(String detailMessage) {
        super(detailMessage);
    }

    public NativeDaemonConnectorException(String detailMessage, Throwable throwable) {
        super(detailMessage, throwable);
    }

    public NativeDaemonConnectorException(String cmd, NativeDaemonEvent event) {
        super("command '" + cmd + "' failed with '" + event + "'");
        mCmd = cmd;
        mEvent = event;
    }

    public int getCode() {
        return mEvent.getCode();
    }

    public String getCmd() {
        return mCmd;
    }

    /**
     * Rethrow as a {@link RuntimeException} subclass that is handled by
     * {@link Parcel#writeException(Exception)}.
     */
    public IllegalArgumentException rethrowAsParcelableException() {
        throw new IllegalStateException(getMessage(), this);
    }
}
