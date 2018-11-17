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

package com.android.powermodel;

/**
 * Identifiers for well-known apps that have unique characteristics.
 *
 * @more
 * This includes three categories:
 * <ul>
 *   <li><b>Built-in system components</b> – These have predefined UIDs that are
 *   always the same. For example, the system UID is always 1000.</li>
 *   <li><b>Well known apps with shared UIDs</b> – These do not have predefined
 *   UIDs (i.e. are different on each device), but since they have shared UIDs
 *   with varying sets of package names (GmsCore is the canonical example), we
 *   have special logic to capture these into a single entity with a well defined
 *   key. These have the {@link #uid uid} field set to
 *   {@link Uid#UID_VARIES Uid.UID_VARIES}.</li>
 *   <li><b>Synthetic remainder app</b> – The {@link #REMAINDER REMAINDER} app doesn't
 *   represent a real app. It contains accounting for usage which is not attributed
 *   to any UID. This app has the {@link #uid uid} field set to
 *   {@link Uid#UID_SYNTHETIC Uid.UID_SYNTHETIC}.</li>
 * </ul>
 */
public enum SpecialApp {

    /**
     * Synthetic app that accounts for the remaining amount of resources used
     * that is unaccounted for by apps, or overcounted because of inaccuracies
     * in the model.
     */
    REMAINDER(Uid.UID_SYNTHETIC),

    /**
     * Synthetic app that holds system-wide numbers, for example the total amount
     * of various resources used, device-wide.
     */
    GLOBAL(Uid.UID_SYNTHETIC),

    SYSTEM(1000),

    GOOGLE_SERVICES(Uid.UID_VARIES);

    /**
     * Constants for SpecialApps where the uid is not actually a UID.
     */
    public static class Uid {
        /**
         * Constant to indicate that this special app does not have a fixed UID.
         */
        public static final int UID_VARIES = -1;

        /**
         * Constant to indicate that this special app is not actually an app with a UID.
         * 
         * @see SpecialApp#REMAINDER
         * @see SpecialApp#GLOBAL
         */
        public static final int UID_SYNTHETIC = -2;
    }

    /**
     * The fixed UID value of this special app, or {@link #UID_VARIES} if there
     * isn't one.
     */
    public final int uid;

    private SpecialApp(int uid) {
        this.uid = uid;
    }
}
