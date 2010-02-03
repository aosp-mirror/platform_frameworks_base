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

/**
 * An exception that indicates there was an error with a NativeDaemonConnector operation
 */
public class NativeDaemonConnectorException extends RuntimeException
{
    private int mCode = -1;
    private String mCmd;

    public NativeDaemonConnectorException() {}

    public NativeDaemonConnectorException(String error)
    {
        super(error);
    }

    public NativeDaemonConnectorException(int code, String cmd, String error)
    {
        super(String.format("Cmd {%s} failed with code %d : {%s}", cmd, code, error));
        mCode = code;
        mCmd = cmd;
    }

    public int getCode() {
        return mCode;
    }

    public String getCmd() {
        return mCmd;
    }
}
