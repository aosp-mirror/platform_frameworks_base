/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.commands.uinput;

import android.annotation.Nullable;
import android.util.SparseArray;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import src.com.android.commands.uinput.InputAbsInfo;

/**
 * Parser for the <a href="https://gitlab.freedesktop.org/libevdev/evemu">FreeDesktop evemu</a>
 * event recording format.
 */
public class EvemuParser implements EventParser {
    private static final String TAG = "UinputEvemuParser";

    /**
     * The device ID to use for all events. Since evemu files only support single-device
     * recordings, this will always be the same.
     */
    private static final int DEVICE_ID = 1;
    private static final int REGISTRATION_DELAY_MILLIS = 500;

    private static class CommentAwareReader {
        private final BufferedReader mReader;
        private String mNextLine;

        CommentAwareReader(BufferedReader in) throws IOException {
            mReader = in;
            mNextLine = findNextLine();
        }

        private @Nullable String findNextLine() throws IOException {
            String line = "";
            while (line != null && line.length() == 0) {
                String unstrippedLine = mReader.readLine();
                if (unstrippedLine == null) {
                    // End of file.
                    return null;
                }
                line = stripComments(unstrippedLine);
            }
            return line;
        }

        private static String stripComments(String line) {
            int index = line.indexOf('#');
            // 'N:' lines (which contain the name of the input device) do not support trailing
            // comments, to support recording device names that contain #s.
            if (index < 0 || line.startsWith("N: ")) {
                return line;
            } else {
                return line.substring(0, index).strip();
            }
        }

        /**
         * Returns the next line of the file that isn't blank when stripped of comments, or
         * {@code null} if the end of the file is reached. However, it does not advance to the
         * next line of the file.
         */
        public @Nullable String peekLine() {
            return mNextLine;
        }

        /** Moves to the next line of the file. */
        public void advance() throws IOException {
            mNextLine = findNextLine();
        }

        public boolean isAtEndOfFile() {
            return mNextLine == null;
        }
    }

    private final CommentAwareReader mReader;
    /**
     * The timestamp of the last event returned, of the head of {@link #mQueuedEvents} if there is
     * one, or -1 if no events have been returned yet.
     */
    private long mLastEventTimeMicros = -1;
    private final Queue<Event> mQueuedEvents = new ArrayDeque<>(2);

    public EvemuParser(Reader in) throws IOException {
        mReader = new CommentAwareReader(new BufferedReader(in));
        mQueuedEvents.add(parseRegistrationEvent());

        // The kernel takes a little time to set up an evdev device after the initial
        // registration. Any events that we try to inject during this period would be silently
        // dropped, so we delay for a short period after registration and before injecting any
        // events.
        final Event.Builder delayEb = new Event.Builder();
        delayEb.setId(DEVICE_ID);
        delayEb.setCommand(Event.Command.DELAY);
        delayEb.setDurationMillis(REGISTRATION_DELAY_MILLIS);
        mQueuedEvents.add(delayEb.build());
    }

    /**
     * Returns the next event in the evemu recording.
     */
    public Event getNextEvent() throws IOException {
        if (!mQueuedEvents.isEmpty()) {
            return mQueuedEvents.remove();
        }

        if (mReader.isAtEndOfFile()) {
            return null;
        }

        final String[] parts = expectLineWithParts("E", 4);
        final String[] timeParts = parts[0].split("\\.");
        if (timeParts.length != 2) {
            throw new RuntimeException("Invalid timestamp (does not contain a '.')");
        }
        // TODO(b/310958309): use timeMicros to set the timestamp on the event being sent.
        final long timeMicros =
                Long.parseLong(timeParts[0]) * 1_000_000 + Integer.parseInt(timeParts[1]);
        final Event.Builder eb = new Event.Builder();
        eb.setId(DEVICE_ID);
        eb.setCommand(Event.Command.INJECT);
        final int eventType = Integer.parseInt(parts[1], 16);
        final int eventCode = Integer.parseInt(parts[2], 16);
        final int value = Integer.parseInt(parts[3]);
        eb.setInjections(new int[] {eventType, eventCode, value});

        if (mLastEventTimeMicros == -1) {
            // This is the first event being injected, so send it straight away.
            mLastEventTimeMicros = timeMicros;
            return eb.build();
        } else {
            final long delayMicros = timeMicros - mLastEventTimeMicros;
            // The shortest delay supported by Handler.sendMessageAtTime (used for timings by the
            // Device class) is 1ms, so ignore time differences smaller than that.
            if (delayMicros < 1000) {
                mLastEventTimeMicros = timeMicros;
                return eb.build();
            } else {
                // Send a delay now, and queue the actual event for the next call.
                mQueuedEvents.add(eb.build());
                mLastEventTimeMicros = timeMicros;
                final Event.Builder delayEb = new Event.Builder();
                delayEb.setId(DEVICE_ID);
                delayEb.setCommand(Event.Command.DELAY);
                delayEb.setDurationMillis((int) (delayMicros / 1000));
                return delayEb.build();
            }
        }
    }

    private Event parseRegistrationEvent() throws IOException {
        // The registration details at the start of a recording are specified by a set of lines
        // that have to be in this order: N, I, P, B, A, L, S. Recordings must have exactly one N
        // (name) and I (IDs) line. The remaining lines are optional, and there may be multiple
        // of those lines.

        final Event.Builder eb = new Event.Builder();
        eb.setId(DEVICE_ID);
        eb.setCommand(Event.Command.REGISTER);
        eb.setName(expectLine("N"));

        final String[] idStrings = expectLineWithParts("I", 4);
        eb.setBusId(Integer.parseInt(idStrings[0], 16));
        eb.setVid(Integer.parseInt(idStrings[1], 16));
        eb.setPid(Integer.parseInt(idStrings[2], 16));
        // TODO(b/302297266): support setting the version ID, and set it to idStrings[3].

        final SparseArray<int[]> config = new SparseArray<>();
        config.append(Event.UinputControlCode.UI_SET_PROPBIT.getValue(), parseProperties());

        parseAxisBitmaps(config);

        eb.setConfiguration(config);
        if (config.contains(Event.UinputControlCode.UI_SET_FFBIT.getValue())) {
            // If the device specifies any force feedback effects, the kernel will require the
            // ff_effects_max value to be set.
            eb.setFfEffectsMax(config.get(Event.UinputControlCode.UI_SET_FFBIT.getValue()).length);
        }

        eb.setAbsInfo(parseAbsInfos());

        // L: and S: lines allow the initial states of the device's LEDs and switches to be
        // recorded. However, the FreeDesktop implementation doesn't support actually setting these
        // states at the start of playback (apparently due to concerns over race conditions), and we
        // have no need for this feature either, so for now just skip over them.
        skipUnsupportedLines("L");
        skipUnsupportedLines("S");

        return eb.build();
    }

    private int[] parseProperties() throws IOException {
        final List<String> propBitmapParts = new ArrayList<>();
        String line = acceptLine("P");
        while (line != null) {
            propBitmapParts.addAll(List.of(line.strip().split(" ")));
            line = acceptLine("P");
        }
        return hexStringBitmapToEventCodes(propBitmapParts);
    }

    private void parseAxisBitmaps(SparseArray<int[]> config) throws IOException {
        final Map<Integer, List<String>> axisBitmapParts = new HashMap<>();
        String line = acceptLine("B");
        while (line != null) {
            final String[] parts = line.strip().split(" ");
            if (parts.length < 2) {
                throw new RuntimeException(
                        "Expected event type and at least one bitmap byte on 'B:' line; only found "
                                + parts.length + " elements");
            }
            final int eventType = Integer.parseInt(parts[0], 16);
            // EV_SYN cannot be configured through uinput, so skip it.
            if (eventType != Event.EV_SYN) {
                if (!axisBitmapParts.containsKey(eventType)) {
                    axisBitmapParts.put(eventType, new ArrayList<>());
                }
                for (int i = 1; i < parts.length; i++) {
                    axisBitmapParts.get(eventType).add(parts[i]);
                }
            }
            line = acceptLine("B");
        }
        final List<Integer> eventTypesToSet = new ArrayList<>();
        for (var entry : axisBitmapParts.entrySet()) {
            if (entry.getValue().size() == 0) {
                continue;
            }
            final Event.UinputControlCode controlCode =
                    Event.UinputControlCode.forEventType(entry.getKey());
            final int[] eventCodes = hexStringBitmapToEventCodes(entry.getValue());
            if (controlCode != null && eventCodes.length > 0) {
                config.append(controlCode.getValue(), eventCodes);
                eventTypesToSet.add(entry.getKey());
            }
        }
        config.append(
                Event.UinputControlCode.UI_SET_EVBIT.getValue(), unboxIntList(eventTypesToSet));
    }

    private SparseArray<InputAbsInfo> parseAbsInfos() throws IOException {
        final SparseArray<InputAbsInfo> absInfos = new SparseArray<>();
        String line = acceptLine("A");
        while (line != null) {
            final String[] parts = line.strip().split(" ");
            if (parts.length < 5 || parts.length > 6) {
                throw new RuntimeException(
                        "'A:' lines should have the format 'A: <index (hex)> <min> <max> <fuzz> "
                                + "<flat> [<resolution>]'; expected 5 or 6 numbers but found "
                                + parts.length);
            }
            final int axisCode = Integer.parseInt(parts[0], 16);
            final InputAbsInfo info = new InputAbsInfo();
            info.minimum = Integer.parseInt(parts[1]);
            info.maximum = Integer.parseInt(parts[2]);
            info.fuzz = Integer.parseInt(parts[3]);
            info.flat = Integer.parseInt(parts[4]);
            info.resolution = parts.length > 5 ? Integer.parseInt(parts[5]) : 0;
            absInfos.append(axisCode, info);
            line = acceptLine("A");
        }
        return absInfos;
    }

    private void skipUnsupportedLines(String type) throws IOException {
        if (acceptLine(type) != null) {
            while (acceptLine(type) != null) {
                // Skip the line.
            }
        }
    }

    /**
     * Returns the contents of the next line in the file if it has the given type, or raises an
     * error if it does not.
     *
     * @param type the type of the line to expect, represented by the letter before the ':'.
     * @return the part of the line after the ": ".
     */
    private String expectLine(String type) throws IOException {
        final String line = acceptLine(type);
        if (line == null) {
            throw new RuntimeException("Expected line of type '" + type + "'");
        } else {
            return line;
        }
    }

    /**
     * Peeks at the next line in the file to see if it has the given type, and if so, returns its
     * contents and advances the reader.
     *
     * @param type the type of the line to accept, represented by the letter before the ':'.
     * @return the part of the line after the ": ", if the type matches; otherwise {@code null}.
     */
    private @Nullable String acceptLine(String type) throws IOException {
        final String line = mReader.peekLine();
        if (line == null) {
            return null;
        }
        final String[] lineParts = line.split(": ", 2);
        if (lineParts.length < 2) {
            // TODO(b/302297266): make a proper exception class for syntax errors, including line
            // numbers, etc.. (We can use LineNumberReader to track them.)
            throw new RuntimeException("Line without ': '");
        }
        if (lineParts[0].equals(type)) {
            mReader.advance();
            return lineParts[1];
        } else {
            return null;
        }
    }

    /**
     * Like {@link #expectLine(String)}, but also checks that the contents of the line is formed of
     * {@code numParts} space-separated parts.
     *
     * @param type the type of the line to expect, represented by the letter before the ':'.
     * @param numParts the number of parts to expect.
     * @return the part of the line after the ": ", split into {@code numParts} sections.
     */
    private String[] expectLineWithParts(String type, int numParts) throws IOException {
        final String[] parts = expectLine(type).strip().split(" ");
        if (parts.length != numParts) {
            throw new RuntimeException("Expected a '" + type + "' line with " + numParts
                    + " parts, found one with " + parts.length);
        }
        return parts;
    }

    private static int[] hexStringBitmapToEventCodes(List<String> strs) {
        final List<Integer> codes = new ArrayList<>();
        for (int iByte = 0; iByte < strs.size(); iByte++) {
            int b = Integer.parseInt(strs.get(iByte), 16);
            if (b < 0x0 || b > 0xff) {
                throw new RuntimeException("Bitmap part '" + strs.get(iByte)
                        + "' invalid; parts must be between 00 and ff.");
            }
            for (int iBit = 0; iBit < 8; iBit++) {
                if ((b & 1) != 0) {
                    codes.add(iByte * 8 + iBit);
                }
                b >>= 1;
            }
        }
        return unboxIntList(codes);
    }

    private static int[] unboxIntList(List<Integer> list) {
        final int[] array = new int[list.size()];
        Arrays.setAll(array, list::get);
        return array;
    }
}
