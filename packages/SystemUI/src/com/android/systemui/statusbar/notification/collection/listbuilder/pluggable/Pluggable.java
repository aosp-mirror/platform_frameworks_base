/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.listbuilder.pluggable;

import android.annotation.Nullable;

import com.android.systemui.statusbar.notification.collection.NotifPipeline;

/**
 * Generic superclass for chunks of code that can plug into the {@link NotifPipeline}.
 *
 * A pluggable is fundamentally three things:
 * 1. A name (for debugging purposes)
 * 2. The functionality that the pluggable provides to the pipeline (this is determined by the
 *    subclass).
 * 3. A way for the pluggable to inform the pipeline that its state has changed and the pipeline
 *    should be rerun (in this case, the invalidate() method).
 *
 * @param <This> The type of the subclass. Subclasses should bind their own type here.
 */
public abstract class Pluggable<This> {
    private final String mName;
    @Nullable private PluggableListener<This> mListener;

    Pluggable(String name) {
        mName = name;
    }

    public final String getName() {
        return mName;
    }

    /**
     * Call this method when something has caused this pluggable's behavior to change. The pipeline
     * will be re-run.
     */
    public final void invalidateList() {
        if (mListener != null) {
            mListener.onPluggableInvalidated((This) this);
        }
    }

    /** Set a listener to be notified when a pluggable is invalidated. */
    public void setInvalidationListener(PluggableListener<This> listener) {
        mListener = listener;
    }

    /**
     * Listener interface for when pluggables are invalidated.
     *
     * @param <T> The type of pluggable that is being listened to.
     */
    public interface PluggableListener<T> {
        /** Called whenever {@link #invalidateList()} is called on this pluggable. */
        void onPluggableInvalidated(T pluggable);
    }
}
