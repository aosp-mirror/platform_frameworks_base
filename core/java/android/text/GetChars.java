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
 * Please implement this interface if your CharSequence has a
 * getChars() method like the one in String that is faster than
 * calling charAt() multiple times.
 */
public interface GetChars
extends CharSequence
{
    /**
     * Exactly like String.getChars(): copy chars <code>start</code>
     * through <code>end - 1</code> from this CharSequence into <code>dest</code>
     * beginning at offset <code>destoff</code>.
     */
    public void getChars(int start, int end, char[] dest, int destoff);
}
