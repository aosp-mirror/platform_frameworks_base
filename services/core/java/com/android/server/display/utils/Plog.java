/*
 * Copyright 2018 The Android Open Source Project
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

package com.android.server.display.utils;


import java.lang.StringBuilder;
import java.lang.System;

import android.util.Slog;

/**
 * A utility to log multiple points and curves in a structured way so they can be easily consumed
 * by external tooling
 *
 * To start a plot, call {@link Plog.start} with the plot's title; to add a point to it, call
 * {@link Plog.logPoint} with the point name (that will appear in the legend) and coordinates; and
 * to log a curve, call {@link Plog.logCurve} with its name and points.
 */
public abstract class Plog {
    // A unique identifier used to group points and curves that belong on the same plot.
    private long mId;

    /**
     * Returns a Plog instance that emits messages to the system log.
     *
     * @param tag The tag of the emitted messages in the system log.
     * @return A plog instance that emits messages to the system log.
     */
    public static Plog createSystemPlog(String tag) {
        return new SystemPlog(tag);
    }

    /**
     * Start a new plot.
     *
     * @param title The plot title.
     * @return The Plog instance (for chaining).
     */
    public Plog start(String title) {
        mId = System.currentTimeMillis();
        write(formatTitle(title));
        return this;
    }

    /**
     * Adds a point to the current plot.
     *
     * @param name The point name (that will appear in the legend).
     * @param x The point x coordinate.
     * @param y The point y coordinate.
     * @return The Plog instance (for chaining).
     */
    public Plog logPoint(String name, float x, float y) {
        write(formatPoint(name, x, y));
        return this;
    }

    /**
     * Adds a curve to the current plot.
     *
     * @param name The curve name (that will appear in the legend).
     * @param xs The curve x coordinates.
     * @param ys The curve y coordinates.
     * @return The Plog instance (for chaining).
     */
    public Plog logCurve(String name, float[] xs, float[] ys) {
        write(formatCurve(name, xs, ys));
        return this;
    }

    private String formatTitle(String title) {
        return "title: " + title;
    }

    private String formatPoint(String name, float x, float y) {
        return "point: " + name + ": (" + x + "," + y + ")";
    }

    private String formatCurve(String name, float[] xs, float[] ys) {
        StringBuilder sb = new StringBuilder();
        sb.append("curve: " + name + ": [");
        int n = xs.length <= ys.length ? xs.length : ys.length;
        for (int i = 0; i < n; i++) {
            sb.append("(" + xs[i] + "," + ys[i] + "),");
        }
        sb.append("]");
        return sb.toString();
    }

    private void write(String message) {
        emit("[PLOG " + mId + "] " + message);
    }

    /**
     * Emits a message (depending on the concrete Plog implementation).
     *
     * @param message The message.
     */
    protected abstract void emit(String message);

    /**
     * A Plog that emits messages to the system log.
     */
    public static class SystemPlog extends Plog {
        // The tag of the emitted messages in the system log.
        private final String mTag;

        /**
         * Returns a Plog instance that emits messages to the system log.
         *
         * @param tag The tag of the emitted messages in the system log.
         * @return A Plog instance that emits messages to the system log.
         */
        public SystemPlog(String tag) {
            mTag = tag;
        }

        /**
         * Emits a message to the system log.
         *
         * @param message The message.
         */
        protected void emit(String message) {
            Slog.d(mTag, message);
        }
    }
}
