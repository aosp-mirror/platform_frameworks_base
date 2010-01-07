/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.util;

import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @deprecated This class is no longer functional.
 * Use {@link android.util.EventLog} instead.
 */
public class EventLogTags {
    public static class Description {
        public final int mTag;
        public final String mName;

        Description(int tag, String name) {
            mTag = tag;
            mName = name;
        }
    }

    public EventLogTags() throws IOException {}

    public EventLogTags(BufferedReader input) throws IOException {}

    public Description get(String name) { return null; }

    public Description get(int tag) { return null; }
}
