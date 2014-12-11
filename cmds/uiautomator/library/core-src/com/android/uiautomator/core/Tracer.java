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
package com.android.uiautomator.core;

import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Class that creates traces of the calls to the UiAutomator API and outputs the
 * traces either to logcat or a logfile. Each public method in the UiAutomator
 * that needs to be traced should include a call to Tracer.trace in the
 * beginning. Tracing is turned off by defualt and needs to be enabled
 * explicitly.
 * @hide
 */
public class Tracer {
    private static final String UNKNOWN_METHOD_STRING = "(unknown method)";
    private static final String UIAUTOMATOR_PACKAGE = "com.android.uiautomator.core";
    private static final int CALLER_LOCATION = 6;
    private static final int METHOD_TO_TRACE_LOCATION = 5;
    private static final int MIN_STACK_TRACE_LENGTH = 7;

    /**
     * Enum that determines where the trace output goes. It can go to either
     * logcat, log file or both.
     */
    public enum Mode {
        NONE,
        FILE,
        LOGCAT,
        ALL
    }

    private interface TracerSink {
        public void log(String message);

        public void close();
    }

    private class FileSink implements TracerSink {
        private PrintWriter mOut;
        private SimpleDateFormat mDateFormat;

        public FileSink(File file) throws FileNotFoundException {
            mOut = new PrintWriter(file);
            mDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        }

        public void log(String message) {
            mOut.printf("%s %s\n", mDateFormat.format(new Date()), message);
        }

        public void close() {
            mOut.close();
        }
    }

    private class LogcatSink implements TracerSink {

        private static final String LOGCAT_TAG = "UiAutomatorTrace";

        public void log(String message) {
            Log.i(LOGCAT_TAG, message);
        }

        public void close() {
            // nothing is needed
        }
    }

    private Mode mCurrentMode = Mode.NONE;
    private List<TracerSink> mSinks = new ArrayList<TracerSink>();
    private File mOutputFile;

    private static Tracer mInstance = null;

    /**
     * Returns a reference to an instance of the tracer. Useful to set the
     * parameters before the trace is collected.
     *
     * @return
     */
    public static Tracer getInstance() {
        if (mInstance == null) {
            mInstance = new Tracer();
        }
        return mInstance;
    }

    /**
     * Sets where the trace output will go. Can be either be logcat or a file or
     * both. Setting this to NONE will turn off tracing.
     *
     * @param mode
     */
    public void setOutputMode(Mode mode) {
        closeSinks();
        mCurrentMode = mode;
        try {
            switch (mode) {
                case FILE:
                    if (mOutputFile == null) {
                        throw new IllegalArgumentException("Please provide a filename before " +
                                "attempting write trace to a file");
                    }
                    mSinks.add(new FileSink(mOutputFile));
                    break;
                case LOGCAT:
                    mSinks.add(new LogcatSink());
                    break;
                case ALL:
                    mSinks.add(new LogcatSink());
                    if (mOutputFile == null) {
                        throw new IllegalArgumentException("Please provide a filename before " +
                                "attempting write trace to a file");
                    }
                    mSinks.add(new FileSink(mOutputFile));
                    break;
                default:
                    break;
            }
        } catch (FileNotFoundException e) {
            Log.w("Tracer", "Could not open log file: " + e.getMessage());
        }
    }

    private void closeSinks() {
        for (TracerSink sink : mSinks) {
            sink.close();
        }
        mSinks.clear();
    }

    /**
     * Sets the name of the log file where tracing output will be written if the
     * tracer is set to write to a file.
     *
     * @param filename name of the log file.
     */
    public void setOutputFilename(String filename) {
        mOutputFile = new File(filename);
    }

    private void doTrace(Object[] arguments) {
        if (mCurrentMode == Mode.NONE) {
            return;
        }

        String caller = getCaller();
        if (caller == null) {
            return;
        }

        log(String.format("%s (%s)", caller, join(", ", arguments)));
    }

    private void log(String message) {
        for (TracerSink sink : mSinks) {
            sink.log(message);
        }
    }

    /**
     * Queries whether the tracing is enabled.
     * @return true if tracing is enabled, false otherwise.
     */
    public boolean isTracingEnabled() {
        return mCurrentMode != Mode.NONE;
    }

    /**
     * Public methods in the UiAutomator should call this function to generate a
     * trace. The trace will include the method thats is being called, it's
     * arguments and where in the user's code the method is called from. If a
     * public method is called internally from UIAutomator then this will not
     * output a trace entry. Only calls from outise the UiAutomator package will
     * produce output.
     *
     * Special note about array arguments. You can safely pass arrays of reference types
     * to this function. Like String[] or Integer[]. The trace function will print their
     * contents by calling toString() on each of the elements. This will not work for
     * array of primitive types like int[] or float[]. Before passing them to this function
     * convert them to arrays of reference types manually. Example: convert int[] to Integer[].
     *
     * @param arguments arguments of the method being traced.
     */
    public static void trace(Object... arguments) {
        Tracer.getInstance().doTrace(arguments);
    }

    private static String join(String separator, Object[] strings) {
        if (strings.length == 0)
            return "";

        StringBuilder builder = new StringBuilder(objectToString(strings[0]));
        for (int i = 1; i < strings.length; i++) {
            builder.append(separator);
            builder.append(objectToString(strings[i]));
        }
        return builder.toString();
    }

    /**
     * Special toString method to handle arrays. If the argument is a normal object then this will
     * return normal output of obj.toString(). If the argument is an array this will return a
     * string representation of the elements of the array.
     *
     * This method will not work for arrays of primitive types. Arrays of primitive types are
     * expected to be converted manually by the caller. If the array is not converter then
     * this function will only output "[...]" instead of the contents of the array.
     *
     * @param obj object to convert to a string
     * @return String representation of the object.
     */
    private static String objectToString(Object obj) {
        if (obj.getClass().isArray()) {
            if (obj instanceof Object[]) {
                return Arrays.deepToString((Object[])obj);
            } else {
                return "[...]";
            }
        } else {
            return obj.toString();
        }
    }

    /**
     * This method outputs which UiAutomator method was called and where in the
     * user code it was called from. If it can't deside which method is called
     * it will output "(unknown method)". If the method was called from inside
     * the UiAutomator then it returns null.
     *
     * @return name of the method called and where it was called from. Null if
     *         method was called from inside UiAutomator.
     */
    private static String getCaller() {
        StackTraceElement stackTrace[] = Thread.currentThread().getStackTrace();
        if (stackTrace.length < MIN_STACK_TRACE_LENGTH) {
            return UNKNOWN_METHOD_STRING;
        }

        StackTraceElement caller = stackTrace[METHOD_TO_TRACE_LOCATION];
        StackTraceElement previousCaller = stackTrace[CALLER_LOCATION];

        if (previousCaller.getClassName().startsWith(UIAUTOMATOR_PACKAGE)) {
            return null;
        }

        int indexOfDot = caller.getClassName().lastIndexOf('.');
        if (indexOfDot < 0) {
            indexOfDot = 0;
        }

        if (indexOfDot + 1 >= caller.getClassName().length()) {
            return UNKNOWN_METHOD_STRING;
        }

        String shortClassName = caller.getClassName().substring(indexOfDot + 1);
        return String.format("%s.%s from %s() at %s:%d", shortClassName, caller.getMethodName(),
                previousCaller.getMethodName(), previousCaller.getFileName(),
                previousCaller.getLineNumber());
    }
}
