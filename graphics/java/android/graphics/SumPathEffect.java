/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.graphics;

public class SumPathEffect extends PathEffect {

    /**
     * Construct a PathEffect whose effect is to apply two effects, in sequence.
     * (e.g. first(path) + second(path))
     */
    public SumPathEffect(PathEffect first, PathEffect second) {
        native_instance = nativeCreate(first.native_instance,
                                       second.native_instance);
    }
    
    private static native long nativeCreate(long first, long second);
}

