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
 * Simple interface for printing text, allowing redirection to various
 * targets.  Standard implementations are {@link android.util.LogPrinter},
 * {@link android.util.StringBuilderPrinter}, and
 * {@link android.util.PrintWriterPrinter}.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public interface Printer {
    /**
     * Write a line of text to the output.  There is no need to terminate
     * the given string with a newline.
     */
    void println(String x);
}
