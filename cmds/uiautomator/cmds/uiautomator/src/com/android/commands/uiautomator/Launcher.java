/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.commands.uiautomator;

import android.os.Process;

import java.util.Arrays;

/**
 * Entry point into the uiautomator command line
 *
 * This class maintains the list of sub commands, and redirect the control into it based on the
 * command line arguments. It also prints out help arguments for each sub commands.
 *
 * To add a new sub command, implement {@link Command} and add an instance into COMMANDS array
 */
public class Launcher {

    /**
     * A simple abstraction class for supporting generic sub commands
     */
    public static abstract class Command {
        private String mName;

        public Command(String name) {
            mName = name;
        }

        /**
         * Returns the name of the sub command
         * @return
         */
        public String name() {
            return mName;
        }

        /**
         * Returns a one-liner of the function of this command
         * @return
         */
        public abstract String shortHelp();

        /**
         * Returns a detailed explanation of the command usage
         *
         * Usage may have multiple lines, indentation of 4 spaces recommended.
         * @return
         */
        public abstract String detailedOptions();

        /**
         * Starts the command with the provided arguments
         * @param args
         */
        public abstract void run(String args[]);
    }

    public static void main(String[] args) {
        // show a meaningful process name in `ps`
        Process.setArgV0("uiautomator");
        if (args.length >= 1) {
            Command command = findCommand(args[0]);
            if (command != null) {
                String[] args2 = {};
                if (args.length > 1) {
                    // consume the first arg
                    args2 = Arrays.copyOfRange(args, 1, args.length);
                }
                command.run(args2);
                return;
            }
        }
        HELP_COMMAND.run(args);
    }

    private static Command findCommand(String name) {
        for (Command command : COMMANDS) {
            if (command.name().equals(name)) {
                return command;
            }
        }
        return null;
    }

    private static Command HELP_COMMAND = new Command("help") {
        @Override
        public void run(String[] args) {
            System.err.println("Usage: uiautomator <subcommand> [options]\n");
            System.err.println("Available subcommands:\n");
            for (Command command : COMMANDS) {
                String shortHelp = command.shortHelp();
                String detailedOptions = command.detailedOptions();
                if (shortHelp == null) {
                    shortHelp = "";
                }
                if (detailedOptions == null) {
                    detailedOptions = "";
                }
                System.err.println(String.format("%s: %s", command.name(), shortHelp));
                System.err.println(detailedOptions);
            }
        }

        @Override
        public String detailedOptions() {
            return null;
        }

        @Override
        public String shortHelp() {
            return "displays help message";
        }
    };

    private static Command[] COMMANDS = new Command[] {
        HELP_COMMAND,
        new RunTestCommand(),
        new DumpCommand(),
        new EventsCommand(),
    };
}