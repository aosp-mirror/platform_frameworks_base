/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.tools.layoutlib.java;

import com.android.tools.layoutlib.create.ReplaceMethodCallsAdapter;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Provides alternate implementation to java.util.LinkedHashMap#eldest(), which is present as a
 * non-public method in the Android VM, but not present on the host VM. This is injected in the
 * layoutlib using {@link ReplaceMethodCallsAdapter}.
 */
public class LinkedHashMap_Delegate {
    public static <K,V> Map.Entry<K,V> eldest(LinkedHashMap<K,V> map) {
        Iterator<Entry<K, V>> iterator = map.entrySet().iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }
}
