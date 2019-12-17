/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.commands.svc;

/**
 * @deprecated Please use adb shell cmd phone data enabled/disable instead.
 */
@Deprecated
public class DataCommand extends Svc.Command {

    private static final String DECPRECATED_MESSAGE =
            "adb shell svc data enable/disable is deprecated;"
            + "please use adb shell cmd phone data enable/disable instead.";

    public DataCommand() {
        super("data");
    }

    public String shortHelp() {
        return "Control mobile data connectivity";
    }

    public String longHelp() {
        return shortHelp() + "\n"
                + "\n"
                + DECPRECATED_MESSAGE;
    }

    public void run(String[] args) {
        System.err.println(DECPRECATED_MESSAGE);
    }
}
