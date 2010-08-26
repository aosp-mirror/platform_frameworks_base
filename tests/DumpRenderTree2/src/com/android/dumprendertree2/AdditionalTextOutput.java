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

    /**
     * Ordering of enums is important as it determines ordering of the toString method!
     * StringBuilders will be printed in the order the corresponding types appear here.
     */
    private enum OutputType {
        JS_DIALOG,
        EXCEEDED_DB_QUOTA_MESSAGE,
        CONSOLE_MESSAGE;
    }

    StringBuilder[] mOutputs = new StringBuilder[OutputType.values().length];

    private StringBuilder getStringBuilderForType(OutputType outputType) {
        int index = outputType.ordinal();
        if (mOutputs[index] == null) {
            mOutputs[index] = new StringBuilder();
        }
        return mOutputs[index];
    }

    public void appendExceededDbQuotaMessage(String urlString, String databaseIdentifier) {
        StringBuilder output = getStringBuilderForType(OutputType.EXCEEDED_DB_QUOTA_MESSAGE);

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
            Log.e(LOG_TAG, "urlString=" + urlString + " databaseIdentifier=" + databaseIdentifier,
                    e);
        }

        output.append("UI DELEGATE DATABASE CALLBACK: ");
        output.append("exceededDatabaseQuotaForSecurityOrigin:{");
        output.append(protocol + ", " + host + ", " + port + "} ");
        output.append("database:" + databaseIdentifier + "\n");
    }

    public void appendConsoleMessage(ConsoleMessage consoleMessage) {
        StringBuilder output = getStringBuilderForType(OutputType.CONSOLE_MESSAGE);

        output.append("CONSOLE MESSAGE: line " + consoleMessage.lineNumber());
        output.append(": " + consoleMessage.message() + "\n");
    }

    public void appendJsAlert(String message) {
        StringBuilder output = getStringBuilderForType(OutputType.JS_DIALOG);

        output.append("ALERT: ");
        output.append(message);
        output.append('\n');
    }

    public void appendJsConfirm(String message) {
        StringBuilder output = getStringBuilderForType(OutputType.JS_DIALOG);

        output.append("CONFIRM: ");
        output.append(message);
        output.append('\n');
    }

    public void appendJsPrompt(String message, String defaultValue) {
        StringBuilder output = getStringBuilderForType(OutputType.JS_DIALOG);

        output.append("PROMPT: ");
        output.append(message);
        output.append(", default text: ");
        output.append(defaultValue);
        output.append('\n');
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