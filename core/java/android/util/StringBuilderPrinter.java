/*
 * Copyright (C) 2006 The Android Open Source Project
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

/**
 * Implementation of a {@link android.util.Printer} that sends its output
 * to a {@link StringBuilder}.
 */
public class StringBuilderPrinter implements Printer {
    private final StringBuilder mBuilder;
    
    /**
     * Create a new Printer that sends to a StringBuilder object.
     * 
     * @param builder The StringBuilder where you would like output to go.
     */
    public StringBuilderPrinter(StringBuilder builder) {
        mBuilder = builder;
    }
    
    public void println(String x) {
        mBuilder.append(x);
        int len = x.length();
        if (len <= 0 || x.charAt(len-1) != '\n') {
            mBuilder.append('\n');
        }
    }
}
