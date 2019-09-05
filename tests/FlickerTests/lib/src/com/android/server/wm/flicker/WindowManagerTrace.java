/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm.flicker;

import android.annotation.Nullable;

import com.android.server.wm.flicker.Assertions.Result;
import com.android.server.wm.nano.AppWindowTokenProto;
import com.android.server.wm.nano.StackProto;
import com.android.server.wm.nano.TaskProto;
import com.android.server.wm.nano.WindowManagerTraceFileProto;
import com.android.server.wm.nano.WindowManagerTraceProto;
import com.android.server.wm.nano.WindowStateProto;
import com.android.server.wm.nano.WindowTokenProto;

import com.google.protobuf.nano.InvalidProtocolBufferNanoException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Contains a collection of parsed WindowManager trace entries and assertions to apply over
 * a single entry.
 *
 * Each entry is parsed into a list of {@link WindowManagerTrace.Entry} objects.
 */
public class WindowManagerTrace {
    private static final int DEFAULT_DISPLAY = 0;
    private final List<Entry> mEntries;
    @Nullable
    final private Path mSource;

    private WindowManagerTrace(List<Entry> entries, Path source) {
        this.mEntries = entries;
        this.mSource = source;
    }

    /**
     * Parses {@code WindowManagerTraceFileProto} from {@code data} and uses the proto to
     * generates a list of trace entries.
     *
     * @param data   binary proto data
     * @param source Path to source of data for additional debug information
     */
    static WindowManagerTrace parseFrom(byte[] data, Path source) {
        List<Entry> entries = new ArrayList<>();

        WindowManagerTraceFileProto fileProto;
        try {
            fileProto = WindowManagerTraceFileProto.parseFrom(data);
        } catch (InvalidProtocolBufferNanoException e) {
            throw new RuntimeException(e);
        }
        for (WindowManagerTraceProto entryProto : fileProto.entry) {
            entries.add(new Entry(entryProto));
        }
        return new WindowManagerTrace(entries, source);
    }

    static WindowManagerTrace parseFrom(byte[] data) {
        return parseFrom(data, null);
    }

    public List<Entry> getEntries() {
        return mEntries;
    }

    Entry getEntry(long timestamp) {
        Optional<Entry> entry = mEntries.stream()
                .filter(e -> e.getTimestamp() == timestamp)
                .findFirst();
        if (!entry.isPresent()) {
            throw new RuntimeException("Entry does not exist for timestamp " + timestamp);
        }
        return entry.get();
    }

    Optional<Path> getSource() {
        return Optional.ofNullable(mSource);
    }

    /**
     * Represents a single WindowManager trace entry.
     */
    static class Entry implements ITraceEntry {
        private final WindowManagerTraceProto mProto;

        Entry(WindowManagerTraceProto proto) {
            mProto = proto;
        }

        private static Result isWindowVisible(String windowTitle,
                WindowTokenProto[] windowTokenProtos) {
            boolean titleFound = false;
            for (WindowTokenProto windowToken : windowTokenProtos) {
                for (WindowStateProto windowState : windowToken.windows) {
                    if (windowState.identifier.title.contains(windowTitle)) {
                        titleFound = true;
                        if (isVisible(windowState)) {
                            return new Result(true /* success */,
                                    windowState.identifier.title + " is visible");
                        }
                    }
                }
            }

            String reason;
            if (!titleFound) {
                reason = windowTitle + " cannot be found";
            } else {
                reason = windowTitle + " is invisible";
            }
            return new Result(false /* success */, reason);
        }

        private static boolean isVisible(WindowStateProto windowState) {
            return windowState.windowContainer.visible;
        }

        @Override
        public long getTimestamp() {
            return mProto.elapsedRealtimeNanos;
        }

        /**
         * Returns window title of the top most visible app window.
         */
        private String getTopVisibleAppWindow() {
            StackProto[] stacks = mProto.windowManagerService.rootWindowContainer
                    .displays[DEFAULT_DISPLAY].stacks;
            for (StackProto stack : stacks) {
                for (TaskProto task : stack.tasks) {
                    for (AppWindowTokenProto token : task.appWindowTokens) {
                        for (WindowStateProto windowState : token.windowToken.windows) {
                            if (windowState.windowContainer.visible) {
                                return task.appWindowTokens[0].name;
                            }
                        }
                    }
                }
            }

            return "";
        }

        /**
         * Checks if aboveAppWindow with {@code windowTitle} is visible.
         */
        Result isAboveAppWindowVisible(String windowTitle) {
            WindowTokenProto[] windowTokenProtos = mProto.windowManagerService
                    .rootWindowContainer
                    .displays[DEFAULT_DISPLAY].aboveAppWindows;
            Result result = isWindowVisible(windowTitle, windowTokenProtos);
            return new Result(result.success, getTimestamp(), "showsAboveAppWindow", result.reason);
        }

        /**
         * Checks if belowAppWindow with {@code windowTitle} is visible.
         */
        Result isBelowAppWindowVisible(String windowTitle) {
            WindowTokenProto[] windowTokenProtos = mProto.windowManagerService
                    .rootWindowContainer
                    .displays[DEFAULT_DISPLAY].belowAppWindows;
            Result result = isWindowVisible(windowTitle, windowTokenProtos);
            return new Result(result.success, getTimestamp(), "isBelowAppWindowVisible",
                    result.reason);
        }

        /**
         * Checks if imeWindow with {@code windowTitle} is visible.
         */
        Result isImeWindowVisible(String windowTitle) {
            WindowTokenProto[] windowTokenProtos = mProto.windowManagerService
                    .rootWindowContainer
                    .displays[DEFAULT_DISPLAY].imeWindows;
            Result result = isWindowVisible(windowTitle, windowTokenProtos);
            return new Result(result.success, getTimestamp(), "isImeWindowVisible",
                    result.reason);
        }

        /**
         * Checks if app window with {@code windowTitle} is on top.
         */
        Result isVisibleAppWindowOnTop(String windowTitle) {
            String topAppWindow = getTopVisibleAppWindow();
            boolean success = topAppWindow.contains(windowTitle);
            String reason = "wanted=" + windowTitle + " found=" + topAppWindow;
            return new Result(success, getTimestamp(), "isAppWindowOnTop", reason);
        }

        /**
         * Checks if app window with {@code windowTitle} is visible.
         */
        Result isAppWindowVisible(String windowTitle) {
            final String assertionName = "isAppWindowVisible";
            boolean titleFound = false;
            StackProto[] stacks = mProto.windowManagerService.rootWindowContainer
                    .displays[DEFAULT_DISPLAY].stacks;
            for (StackProto stack : stacks) {
                for (TaskProto task : stack.tasks) {
                    for (AppWindowTokenProto token : task.appWindowTokens) {
                        if (token.name.contains(windowTitle)) {
                            titleFound = true;
                            for (WindowStateProto windowState : token.windowToken.windows) {
                                if (windowState.windowContainer.visible) {
                                    return new Result(true /* success */, getTimestamp(),
                                            assertionName, "Window " + token.name +
                                            "is visible");
                                }
                            }
                        }
                    }
                }
            }
            String reason;
            if (!titleFound) {
                reason = "Window " + windowTitle + " cannot be found";
            } else {
                reason = "Window " + windowTitle + " is invisible";
            }
            return new Result(false /* success */, getTimestamp(), assertionName, reason);
        }
    }
}