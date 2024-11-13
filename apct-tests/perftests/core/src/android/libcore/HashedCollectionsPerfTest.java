/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.libcore;

import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** How do the various hash maps compare? */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class HashedCollectionsPerfTest {
    @Rule public PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    @Test
    public void timeHashMapGet() {
        HashMap<String, String> map = new HashMap<String, String>();
        map.put("hello", "world");
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            map.get("hello");
        }
    }

    @Test
    public void timeHashMapGet_Synchronized() {
        HashMap<String, String> map = new HashMap<String, String>();
        synchronized (map) {
            map.put("hello", "world");
        }
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            synchronized (map) {
                map.get("hello");
            }
        }
    }

    @Test
    public void timeHashtableGet() {
        Hashtable<String, String> map = new Hashtable<String, String>();
        map.put("hello", "world");
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            map.get("hello");
        }
    }

    @Test
    public void timeLinkedHashMapGet() {
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        map.put("hello", "world");
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            map.get("hello");
        }
    }

    @Test
    public void timeConcurrentHashMapGet() {
        ConcurrentHashMap<String, String> map = new ConcurrentHashMap<String, String>();
        map.put("hello", "world");
        BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            map.get("hello");
        }
    }
}
