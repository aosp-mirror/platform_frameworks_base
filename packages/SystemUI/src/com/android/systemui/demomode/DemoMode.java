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

package com.android.systemui.demomode;

import com.google.android.collect.Lists;

import java.util.ArrayList;
import java.util.List;

/**
 * Interface defining what it means to implement DemoMode. A DemoMode implementation should
 * register with DemoModeController, providing a list of commands for wish to listen.
 *
 * If you only need to listen to commands, but *do not* care about demo mode state changes, you
 * can implement DemoModeCommandReceiver instead
 */
public interface DemoMode extends DemoModeCommandReceiver {

    List<String> NO_COMMANDS = new ArrayList<>();

    /** Provide a set of commands to listen to, only acceptable values are the COMMAND_* keys */
    default List<String> demoCommands() {
        return NO_COMMANDS;
    }

    /** Something simple enough to be recognizable in dumpsys logs */
    default String logName() {
        if (this.getClass().isAnonymousClass()) {
            return getClass().getName();
        } else {
            return getClass().getSimpleName();
        }
    }

    String ACTION_DEMO = "com.android.systemui.demo";

    String EXTRA_COMMAND = "command";

    /** Enter and exit are non-register-able; override started/finished to observe these states */
    String COMMAND_ENTER = "enter";
    String COMMAND_EXIT = "exit";

    /** Observable commands to register a listener for  */
    String COMMAND_CLOCK = "clock";
    String COMMAND_BATTERY = "battery";
    String COMMAND_NETWORK = "network";
    String COMMAND_BARS = "bars";
    String COMMAND_STATUS = "status";
    String COMMAND_NOTIFICATIONS = "notifications";
    String COMMAND_VOLUME = "volume";
    String COMMAND_OPERATOR = "operator";

    /** New keys need to be added here */
    List<String> COMMANDS = Lists.newArrayList(
            COMMAND_BARS,
            COMMAND_BATTERY,
            COMMAND_CLOCK,
            COMMAND_NETWORK,
            COMMAND_NOTIFICATIONS,
            COMMAND_OPERATOR,
            COMMAND_STATUS,
            COMMAND_VOLUME
    );
}

