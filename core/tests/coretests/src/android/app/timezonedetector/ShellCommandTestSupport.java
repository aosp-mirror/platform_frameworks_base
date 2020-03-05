/*
 * Copyright 2020 The Android Open Source Project
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
package android.app.timezonedetector;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.os.ShellCommand;

import org.mockito.stubbing.Answer;

import java.util.Arrays;
import java.util.List;

/** Utility methods related to {@link ShellCommand} objects used in several tests. */
final class ShellCommandTestSupport {
    private ShellCommandTestSupport() {}

    static ShellCommand createShellCommandWithArgsAndOptions(String argsWithSpaces) {
        return createShellCommandWithArgsAndOptions(Arrays.asList(argsWithSpaces.split(" ")));
    }

    static ShellCommand createShellCommandWithArgsAndOptions(List<String> args) {
        ShellCommand command = mock(ShellCommand.class);
        class ArgProvider {
            private int mCount;

            String getNext() {
                if (mCount >= args.size()) {
                    return null;
                }
                return args.get(mCount++);
            }

            String getNextRequired() {
                String next = getNext();
                if (next == null) {
                    throw new IllegalArgumentException("No next");
                }
                return next;
            }
        }
        ArgProvider argProvider = new ArgProvider();
        when(command.getNextArg()).thenAnswer(
                (Answer<String>) invocation -> argProvider.getNext());
        when(command.getNextOption()).thenAnswer(
                (Answer<String>) invocation -> argProvider.getNext());
        when(command.getNextArgRequired()).thenAnswer(
                (Answer<String>) invocation -> argProvider.getNextRequired());
        return command;
    }
}
