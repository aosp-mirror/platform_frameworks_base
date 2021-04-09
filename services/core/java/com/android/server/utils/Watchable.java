/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.utils;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;

/**
 * Notify registered {@link Watcher}s when the content changes.
 */
public interface Watchable {

    /**
     * Ensures an observer is in the list, exactly once. The observer cannot be null.  The
     * function quietly returns if the observer is already in the list.
     *
     * @param observer The {@link Watcher} to be notified when the {@link Watchable} changes.
     */
    public void registerObserver(@NonNull Watcher observer);

    /**
     * Ensures an observer is not in the list. The observer must not be null.  The function
     * quietly returns if the objserver is not in the list.
     *
     * @param observer The {@link Watcher} that should not be in the notification list.
     */
    public void unregisterObserver(@NonNull Watcher observer);

    /**
     * Return true if the {@link Watcher) is a registered observer.
     * @param observer A {@link Watcher} that might be registered
     * @return true if the observer is registered with this {@link Watchable}.
     */
    public boolean isRegisteredObserver(@NonNull Watcher observer);

    /**
     * Invokes {@link Watcher#onChange} on each registered observer.  The method can be called
     * with the {@link Watchable} that generated the event.  In a tree of {@link Watchable}s, this
     * is generally the first (deepest) {@link Watchable} to detect a change.
     *
     * @param what The {@link Watchable} that generated the event.
     */
    public void dispatchChange(@Nullable Watchable what);

    /**
     * Verify that all @Watched {@link Watchable} attributes are being watched by this
     * class.  This requires reflection and only runs in engineering or user debug
     * builds.
     * @param base The object that contains watched attributes.
     * @param observer The {@link Watcher} that should be watching these attributes.
     * @param logOnly If true then log errors; if false then throw an RuntimeExecption on error.
     */
    static void verifyWatchedAttributes(Object base, Watcher observer, boolean logOnly) {
        if (!(Build.IS_ENG || Build.IS_USERDEBUG)) {
            return;
        }
        for (Field f : base.getClass().getDeclaredFields()) {
            final Watched annotation = f.getAnnotation(Watched.class);
            if (annotation != null) {
                final String fn = base.getClass().getName() + "." + f.getName();
                try {
                    f.setAccessible(true);
                    final Object o = f.get(base);
                    if (o instanceof Watchable) {
                        Watchable attr = (Watchable) (o);
                        if (attr != null && !attr.isRegisteredObserver(observer)) {
                            handleVerifyError("Watchable " + fn + " missing an observer", logOnly);
                        }
                    } else if (!annotation.manual()) {
                        handleVerifyError("@Watched annotated field " + fn + " is not a watchable"
                                + " type and is not flagged for manual watching.", logOnly);
                    }
                } catch (IllegalAccessException e) {
                    // The field is protected; ignore it.  Other exceptions that may be thrown by
                    // Field.get() are allowed to roll up.
                    handleVerifyError("Watchable " + fn + " not visible", logOnly);
                }
            }
        }
    }

    static void handleVerifyError(String errorMessage, boolean logOnly) {
        if (logOnly) {
            Log.e("Watchable", errorMessage);
        } else {
            throw new RuntimeException(errorMessage);
        }
    }

    /**
     * Verify that all @Watched {@link Watchable} attributes are being watched by this
     * class.  This calls verifyWatchedAttributes() with logOnly set to false.
     * @param base The object that contains watched attributes.
     * @param observer The {@link Watcher} that should be watching these attributes.
     */
    static void verifyWatchedAttributes(Object base, Watcher observer) {
        verifyWatchedAttributes(base, observer, false);
    }
}
