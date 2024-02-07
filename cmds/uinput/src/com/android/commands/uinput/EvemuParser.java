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

import java.io.IOException;
import java.io.LineNumberReader;
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
    private static final int REGISTRATION_DELAY_NANOS = 500_000_000;

    private static class CommentAwareReader {
        private final LineNumberReader mReader;
        private String mPreviousLine;
        private String mNextLine;

        CommentAwareReader(LineNumberReader in) throws IOException {
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
            mPreviousLine = mNextLine;
            mNextLine = findNextLine();
        }

        public boolean isAtEndOfFile() {
            return mNextLine == null;
        }

        /** Returns the previous line, for error messages. */
        public String getPreviousLine() {
            return mPreviousLine;
        }

        /** Returns the number of the <b>previous</b> line. */
        public int getPreviousLineNumber() {
            return mReader.getLineNumber() - 1;
        }
    }

    public static class ParsingException extends RuntimeException {
        private final int mLineNumber;
        private final String mLine;

        ParsingException(String message, CommentAwareReader reader) {
            this(message, reader.getPreviousLine(), reader.getPreviousLineNumber());
        }

        ParsingException(String message, String line, int lineNumber) {
            super(message);
            mLineNumber = lineNumber;
            mLine = line;
        }

        /** Returns a nicely formatted error message, including the line number and line. */
        public String makeErrorMessage() {
            return String.format("""
                    Parsing error on line %d: %s
                    --> %s
                    """, mLineNumber, getMessage(), mLine);
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
        mReader = new CommentAwareReader(new LineNumberReader(in));
        mQueuedEvents.add(parseRegistrationEvent());

        // The kernel takes a little time to set up an evdev device after the initial
        // registration. Any events that we try to inject during this period would be silently
        // dropped, so we delay for a short period after registration and before injecting any
        // events.
        final Event.Builder delayEb = new Event.Builder();
        delayEb.setId(DEVICE_ID);
        delayEb.setCommand(Event.Command.DELAY);
        delayEb.setDurationNanos(REGISTRATION_DELAY_NANOS);
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

        final String line = expectLine("E");
        final String[] parts = expectParts(line, 4);
        final String[] timeParts = parts[0].split("\\.");
        if (timeParts.length != 2) {
            throw new ParsingException(
                    "Invalid timestamp '" + parts[0] + "' (should contain a single '.')", mReader);
        }
        // TODO(b/310958309): use timeMicros to set the timestamp on the event being sent.
        final long timeMicros =
                parseLong(timeParts[0], 10) * 1_000_000 + parseInt(timeParts[1], 10);
        final Event.Builder eb = new Event.Builder();
        eb.setId(DEVICE_ID);
        eb.setCommand(Event.Command.INJECT);
        final int eventType = parseInt(parts[1], 16);
        final int eventCode = parseInt(parts[2], 16);
        final int value = parseInt(parts[3], 10);
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
                delayEb.setDurationNanos(delayMicros * 1000);
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

        final String idsLine = expectLine("I");
        final String[] idStrings = expectParts(idsLine, 4);
        eb.setBusId(parseInt(idStrings[0], 16));
        eb.setVendorId(parseInt(idStrings[1], 16));
        eb.setProductId(parseInt(idStrings[2], 16));
        eb.setVersionId(parseInt(idStrings[3], 16));

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
        final ArrayList<Integer> propBitmapParts = new ArrayList<>();
        String line = acceptLine("P");
        while (line != null) {
            String[] parts = line.strip().split(" ");
            propBitmapParts.ensureCapacity(propBitmapParts.size() + parts.length);
            for (String part : parts) {
                propBitmapParts.add(parseBitmapPart(part, line));
            }
            line = acceptLine("P");
        }
        return bitmapToEventCodes(propBitmapParts);
    }

    private void parseAxisBitmaps(SparseArray<int[]> config) throws IOException {
        final Map<Integer, ArrayList<Integer>> axisBitmapParts = new HashMap<>();
        String line = acceptLine("B");
        while (line != null) {
            final String[] parts = line.strip().split(" ");
            if (parts.length < 2) {
                throw new ParsingException(
                        "Expected event type and at least one bitmap byte on 'B:' line; only found "
                                + parts.length + " elements", mReader);
            }
            final int eventType = parseInt(parts[0], 16);
            // EV_SYN cannot be configured through uinput, so skip it.
            if (eventType != Event.EV_SYN) {
                if (!axisBitmapParts.containsKey(eventType)) {
                    axisBitmapParts.put(eventType, new ArrayList<>());
                }
                ArrayList<Integer> bitmapParts = axisBitmapParts.get(eventType);
                bitmapParts.ensureCapacity(bitmapParts.size() + parts.length);
                for (int i = 1; i < parts.length; i++) {
                    axisBitmapParts.get(eventType).add(parseBitmapPart(parts[i], line));
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
            final int[] eventCodes = bitmapToEventCodes(entry.getValue());
            if (controlCode != null && eventCodes.length > 0) {
                config.append(controlCode.getValue(), eventCodes);
                eventTypesToSet.add(entry.getKey());
            }
        }
        config.append(
                Event.UinputControlCode.UI_SET_EVBIT.getValue(), unboxIntList(eventTypesToSet));
    }

    private int parseBitmapPart(String part, String line) {
        int b = parseInt(part, 16);
        if (b < 0x0 || b > 0xff) {
            throw new ParsingException("Bitmap part '" + part
                    + "' invalid; parts must be hexadecimal values between 00 and ff.", mReader);
        }
        return b;
    }

    private SparseArray<InputAbsInfo> parseAbsInfos() throws IOException {
        final SparseArray<InputAbsInfo> absInfos = new SparseArray<>();
        String line = acceptLine("A");
        while (line != null) {
            final String[] parts = line.strip().split(" ");
            if (parts.length < 5 || parts.length > 6) {
                throw new ParsingException(
                        "AbsInfo lines should have the format 'A: <index (hex)> <min> <max> <fuzz> "
                                + "<flat> [<resolution>]'; expected 5 or 6 numbers but found "
                                + parts.length, mReader);
            }
            final int axisCode = parseInt(parts[0], 16);
            final InputAbsInfo info = new InputAbsInfo();
            info.minimum = parseInt(parts[1], 10);
            info.maximum = parseInt(parts[2], 10);
            info.fuzz = parseInt(parts[3], 10);
            info.flat = parseInt(parts[4], 10);
            info.resolution = parts.length > 5 ? parseInt(parts[5], 10) : 0;
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
            throw new ParsingException("Expected line of type '" + type + "'. (Lines should be in "
                    + "the order N, I, P, B, A, L, S, E.)",
                    mReader.peekLine(), mReader.getPreviousLineNumber() + 1);
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
            throw new ParsingException("Missing type separator ': '",
                    line, mReader.getPreviousLineNumber() + 1);
        }
        if (lineParts[0].equals(type)) {
            mReader.advance();
            return lineParts[1];
        } else {
            return null;
        }
    }

    private String[] expectParts(String line, int numParts) {
        final String[] parts = line.strip().split(" ");
        if (parts.length != numParts) {
            throw new ParsingException(
                    "Expected a line with " + numParts + " space-separated parts, but found one "
                            + "with " + parts.length, mReader);
        }
        return parts;
    }

    private int parseInt(String s, int radix) {
        try {
            return Integer.parseInt(s, radix);
        } catch (NumberFormatException ex) {
            throw new ParsingException(
                    "'" + s + "' is not a valid integer of base " + radix, mReader);
        }
    }

    private long parseLong(String s, int radix) {
        try {
            return Long.parseLong(s, radix);
        } catch (NumberFormatException ex) {
            throw new ParsingException("'" + s + "' is not a valid long of base " + radix, mReader);
        }
    }

    private static int[] bitmapToEventCodes(List<Integer> bytes) {
        final List<Integer> codes = new ArrayList<>();
        for (int iByte = 0; iByte < bytes.size(); iByte++) {
            int b = bytes.get(iByte);
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
