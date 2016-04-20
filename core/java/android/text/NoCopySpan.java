/*
 * Copyright (C) 2009 The Android Open Source Project
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
 * This interface should be added to a span object that should not be copied
 * into a new Spanned when performing a slice or copy operation on the original
 * Spanned it was placed in.
 */
public interface NoCopySpan {
    /**
     * Convenience equivalent for when you would just want a new Object() for
     * a span but want it to be no-copy.  Use this instead.
     */
    public class Concrete implements NoCopySpan {
    }
}
