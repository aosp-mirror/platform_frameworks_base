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

package android.text;

/**
 * AndroidCharacter exposes some character properties that are not
 * easily accessed from java.lang.Character.
 */
public class AndroidCharacter
{
    /**
     * Fill in the first <code>count</code> bytes of <code>dest</code> with the
     * directionalities from the first <code>count</code> chars of <code>src</code>.
     * This is just like Character.getDirectionality() except it is a
     * batch operation.
     */
    public native static void getDirectionalities(char[] src, byte[] dest,
                                                  int count);
    /**
     * Replace the specified slice of <code>text</code> with the chars'
     * right-to-left mirrors (if any), returning true if any
     * replacements were made.
     */
    public native static boolean mirror(char[] text, int start, int count);

    /**
     * Return the right-to-left mirror (or the original char if none)
     * of the specified char.
     */
    public native static char getMirror(char ch);
}
