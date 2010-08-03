/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.dumprendertree2;

import android.util.Log;
import android.webkit.ConsoleMessage;

import java.net.MalformedURLException;
import java.net.URL;

/**
 * A class that stores consoles messages, database callbacks, alert messages, etc.
 */
public class AdditionalTextOutput {
    private static final String LOG_TAG = "AdditionalTextOutput";

    private enum OutputType {
        EXCEEDED_DB_QUOTA_MESSAGE,
        CONSOLE_MESSAGE;
    }

    StringBuilder[] mOutputs = new StringBuilder[OutputType.values().length];

    public void appendExceededDbQuotaMessage(String urlString, String databaseIdentifier) {
        int index = OutputType.EXCEEDED_DB_QUOTA_MESSAGE.ordinal();
        if (mOutputs[index] == null) {
            mOutputs[index] = new StringBuilder();
        }

        String protocol = "";
        String host = "";
        int port = 0;

        try {
            URL url = new URL(urlString);
            protocol = url.getProtocol();
            host = url.getHost();
            if (url.getPort() > -1) {
                port = url.getPort();
            }
        } catch (MalformedURLException e) {
            Log.e(LOG_TAG + "::appendDatabaseCallback", e.getMessage());
        }

        mOutputs[index].append("UI DELEGATE DATABASE CALLBACK: ");
        mOutputs[index].append("exceededDatabaseQuotaForSecurityOrigin:{");
        mOutputs[index].append(protocol + ", " + host + ", " + port + "} ");
        mOutputs[index].append("database:" + databaseIdentifier + "\n");
    }

    public void appendConsoleMessage(ConsoleMessage consoleMessage) {
        int index = OutputType.CONSOLE_MESSAGE.ordinal();
        if (mOutputs[index] == null) {
            mOutputs[index] = new StringBuilder();
        }

        mOutputs[index].append("CONSOLE MESSAGE: line " + consoleMessage.lineNumber());
        mOutputs[index].append(": " + consoleMessage.message() + "\n");
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < mOutputs.length; i++) {
            if (mOutputs[i] != null) {
                result.append(mOutputs[i].toString());
            }
        }
        return result.toString();
    }
}