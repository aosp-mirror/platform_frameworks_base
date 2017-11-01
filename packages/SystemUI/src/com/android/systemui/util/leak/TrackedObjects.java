/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.util.leak;

import java.util.Collection;
import java.util.WeakHashMap;

/**
 * Tracks instances of classes.
 */
public class TrackedObjects {

    private final TrackedCollections mTrackedCollections;
    private final WeakHashMap<Class<?>, TrackedClass<?>> mTrackedClasses = new WeakHashMap<>();

    public TrackedObjects(TrackedCollections trackedCollections) {
        mTrackedCollections = trackedCollections;
    }

    /**
     * @see LeakDetector#trackInstance(Object)
     */
    public synchronized <T> void track(T object) {
        Class<?> clazz = object.getClass();
        @SuppressWarnings("unchecked")
        TrackedClass<T> trackedClass = (TrackedClass<T>) mTrackedClasses.get(clazz);

        if (trackedClass == null) {
            trackedClass = new TrackedClass<T>();
            mTrackedClasses.put(clazz, trackedClass);
        }

        trackedClass.track(object);
        mTrackedCollections.track(trackedClass, clazz.getName());
    }

    public static boolean isTrackedObject(Collection<?> collection) {
        return collection instanceof TrackedClass;
    }

    private static class TrackedClass<T> extends AbstractCollection<T> {
        final WeakIdentityHashMap<T, Void> instances = new WeakIdentityHashMap<>();

        void track(T object) {
            instances.put(object, null);
        }

        @Override
        public int size() {
            return instances.size();
        }

        @Override
        public boolean isEmpty() {
            return instances.isEmpty();
        }

    }

}
