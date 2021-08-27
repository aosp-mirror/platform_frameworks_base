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
import android.net.Uri;
import android.os.Bundle;
import android.os.ShellCommand;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A command used to trigger behaviors in a component during tests. Routing to the correct
 * component is not handled by this class. The meaning of the {@code name} and {@code args}
 * properties are component-specific.
 *
 * <p>{@link TestCommand}s can be encoded as arguments in a shell command. See
 * {@link #createFromShellCommandArgs(ShellCommand)} and {@link
 * #printShellCommandEncodingHelp(PrintWriter)}.
 */
final class TestCommand {

    private static final Pattern SHELL_ARG_PATTERN = Pattern.compile("([^=]+)=([^:]+):(.*)");
    private static final Pattern SHELL_ARG_VALUE_SPLIT_PATTERN = Pattern.compile("&");

    @NonNull private final String mName;
    @NonNull private final Bundle mArgs;

    /** Creates a {@link TestCommand} from components. */
    private TestCommand(@NonNull String type, @NonNull Bundle args) {
        mName = Objects.requireNonNull(type);
        mArgs = Objects.requireNonNull(args);
    }

    @VisibleForTesting
    @NonNull
    public static TestCommand createForTests(@NonNull String type, @NonNull Bundle args) {
        return new TestCommand(type, args);
    }

    /**
     * Creates a {@link TestCommand} from a {@link ShellCommand}'s remaining arguments.
     *
     * See {@link #printShellCommandEncodingHelp(PrintWriter)} for encoding details.
     */
    @NonNull
    public static TestCommand createFromShellCommandArgs(@NonNull ShellCommand shellCommand) {
        String name = shellCommand.getNextArgRequired();
        Bundle args = new Bundle();
        String argKeyAndValue;
        while ((argKeyAndValue = shellCommand.getNextArg()) != null) {
            Matcher matcher = SHELL_ARG_PATTERN.matcher(argKeyAndValue);
            if (!matcher.matches()) {
                throw new IllegalArgumentException(
                        argKeyAndValue + " does not match " + SHELL_ARG_PATTERN);
            }
            String key = matcher.group(1);
            String type = matcher.group(2);
            String encodedValue = matcher.group(3);
            Object value = getTypedValue(type, encodedValue);
            args.putObject(key, value);
        }
        return new TestCommand(name, args);
    }

    /**
     * Returns the command's name.
     */
    @NonNull
    public String getName() {
        return mName;
    }

    /**
     * Returns the arg values. Returns an empty bundle if there are no args.
     */
    @NonNull
    public Bundle getArgs() {
        return mArgs.deepCopy();
    }

    @Override
    public String toString() {
        return "TestCommand{"
                + "mName=" + mName
                + ", mArgs=" + mArgs
                + '}';
    }

    /**
     * Prints the text format that {@link #createFromShellCommandArgs(ShellCommand)} understands.
     */
    public static void printShellCommandEncodingHelp(@NonNull PrintWriter pw) {
        pw.println("Test commands are encoded on the command line as: <name> <arg>*");
        pw.println();
        pw.println("The <name> is a string");
        pw.println("The <arg> encoding is: \"key=type:value\"");
        pw.println();
        pw.println("e.g. \"myKey=string:myValue\" represents an argument with the key \"myKey\""
                + " and a string value of \"myValue\"");
        pw.println("Values are one or more URI-encoded strings separated by & characters. Only some"
                + " types support multiple values, e.g. string arrays.");
        pw.println();
        pw.println("Recognized types are: string, boolean, double, long, string_array.");
        pw.println();
        pw.println("When passing test commands via adb shell, the & can be escaped by quoting the"
                + " <arg> and escaping the & with \\");
        pw.println("For example:");
        pw.println("  $ adb shell ... my-command \"key1=string_array:value1\\&value2\"");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TestCommand that = (TestCommand) o;
        return mName.equals(that.mName)
                && mArgs.kindofEquals(that.mArgs);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mName, mArgs);
    }


    private static Object getTypedValue(String type, String encodedValue) {
        // The value is stored in a URL encoding. Multiple value types have values separated with
        // a & character.
        String[] values = SHELL_ARG_VALUE_SPLIT_PATTERN.split(encodedValue);

        // URI decode the values.
        for (int i = 0; i < values.length; i++) {
            values[i] = Uri.decode(values[i]);
        }

        switch (type) {
            case "boolean": {
                checkSingleValue(values);
                return Boolean.parseBoolean(values[0]);
            }
            case "double": {
                checkSingleValue(values);
                return Double.parseDouble(values[0]);
            }
            case "long": {
                checkSingleValue(values);
                return Long.parseLong(values[0]);
            }
            case "string": {
                checkSingleValue(values);
                return values[0];
            }
            case "string_array": {
                return values;
            }
            default: {
                throw new IllegalArgumentException("Unknown type: " + type);
            }
        }

    }

    private static void checkSingleValue(String[] values) {
        if (values.length != 1) {
            throw new IllegalArgumentException("Expected a single value, but there were multiple: "
                    + Arrays.toString(values));
        }
    }
}
