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

package com.android.server.accessibility.utils;

import android.view.MotionEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class helps parse a gesture event log into its individual MotionEvents
 */
public class GestureLogParser {
    /** Gets a MotionEvent from a log line */
    public static MotionEvent getMotionEventFromLogLine(String line) {
        final int downTime;
        final int eventTime;
        int action;
        final int pointerCount;

        final MotionEvent.PointerProperties[] properties;
        final MotionEvent.PointerCoords[] pointerCoords;
        final int metaState;
        final int buttonState = 0;
        final int xPrecision = 1;
        final int yPrecision = 1;
        final int deviceId;
        final int edgeFlags;
        final int source;
        final int flags;
        final int actionIndex;

        downTime = findInt(line, "downTime=(\\d+)");
        eventTime = findInt(line, "eventTime=(\\d+)");
        action = stringToAction(findString(line, "action=(\\w+)"));

        // For pointer indices
        Pattern p = Pattern.compile("action=(\\w+)\\((\\d)");
        Matcher matcher = p.matcher(line);
        if (matcher.find()) {
            actionIndex = Integer.decode(matcher.group(2));
            action = action | (actionIndex << MotionEvent.ACTION_POINTER_INDEX_SHIFT);
        }

        pointerCount = findInt(line, "pointerCount=(\\d+)");
        metaState = findInt(line, "metaState=(\\d+)");
        deviceId = findInt(line, "deviceId=(\\d+)");
        edgeFlags = Integer.decode(findString(line, "edgeFlags=(\\w+)"));
        source = Integer.decode(findString(line, "source=(\\w+)"));
        flags = Integer.decode(findString(line, "flags=(\\w+)"));
        properties = findProperties(line, pointerCount);
        pointerCoords = findCoordinates(line, pointerCount);

        return MotionEvent.obtain(downTime, eventTime, action,
                pointerCount, properties, pointerCoords, metaState, buttonState,
                xPrecision, yPrecision, deviceId, edgeFlags, source, flags);
    }

    private static int findInt(String eventText, String pattern) {
        final Pattern p = Pattern.compile(pattern);
        final Matcher matcher = p.matcher(eventText);
        matcher.find();
        return Integer.decode(matcher.group(1));
    }

    private static float findFloat(String eventText, String pattern) {
        final Pattern p = Pattern.compile(pattern);
        final Matcher matcher = p.matcher(eventText);
        matcher.find();
        return Float.parseFloat(matcher.group(1));
    }

    private static String findString(String eventText, String pattern) {
        final Pattern p = Pattern.compile(pattern);
        final Matcher matcher = p.matcher(eventText);
        matcher.find();
        return matcher.group(1);
    }

    private static MotionEvent.PointerCoords[] findCoordinates(String eventText, int pointerCount) {
        if (pointerCount == 0) {
            return null;
        }

        final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[pointerCount];
        float x;
        float y;
        for (int i = 0; i < pointerCount; i++) {

            x = findFloat(eventText, "x\\[" + i + "\\]=([\\d.]+)");
            y = findFloat(eventText, "y\\[" + i + "\\]=([\\d.]+)");

            MotionEvent.PointerCoords pointerCoords = new MotionEvent.PointerCoords();
            pointerCoords.x = x;
            pointerCoords.y = y;
            pointerCoords.pressure = 1;
            pointerCoords.size = 1;

            coords[i] = pointerCoords;
        }
        return coords;
    }

    private static MotionEvent.PointerProperties[] findProperties(
            String eventText, int pointerCount) {
        if (pointerCount == 0) {
            return null;
        }

        final MotionEvent.PointerProperties[] props =
                new MotionEvent.PointerProperties[pointerCount];
        int id;
        for (int i = 0; i < pointerCount; i++) {
            id = findInt(eventText, "id\\[" + i + "\\]=([\\d])");
            MotionEvent.PointerProperties pointerProps = new MotionEvent.PointerProperties();
            pointerProps.id = id;
            pointerProps.toolType = MotionEvent.TOOL_TYPE_FINGER;
            props[i] = pointerProps;
        }
        return props;
    }

    private static int stringToAction(String action) {
        switch (action) {
            case "ACTION_DOWN":
                return MotionEvent.ACTION_DOWN;
            case "ACTION_UP":
                return MotionEvent.ACTION_UP;
            case "ACTION_CANCEL":
                return MotionEvent.ACTION_CANCEL;
            case "ACTION_OUTSIDE":
                return MotionEvent.ACTION_OUTSIDE;
            case "ACTION_MOVE":
                return MotionEvent.ACTION_MOVE;
            case "ACTION_HOVER_MOVE":
                return MotionEvent.ACTION_HOVER_MOVE;
            case "ACTION_SCROLL":
                return MotionEvent.ACTION_SCROLL;
            case "ACTION_HOVER_ENTER":
                return MotionEvent.ACTION_HOVER_ENTER;
            case "ACTION_HOVER_EXIT":
                return MotionEvent.ACTION_HOVER_EXIT;
            case "ACTION_BUTTON_PRESS":
                return MotionEvent.ACTION_BUTTON_PRESS;
            case "ACTION_BUTTON_RELEASE":
                return MotionEvent.ACTION_BUTTON_RELEASE;
            case "ACTION_POINTER_DOWN":
                return MotionEvent.ACTION_POINTER_DOWN;
            case "ACTION_POINTER_UP":
                return MotionEvent.ACTION_POINTER_UP;
            default:
                return -1;
        }
    }
}
