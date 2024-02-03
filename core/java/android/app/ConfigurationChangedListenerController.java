/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.app;

import android.annotation.NonNull;
import android.os.IBinder;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Manages listeners for unfiltered configuration changes.
 * @hide
 */
class ConfigurationChangedListenerController {

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final List<ListenerContainer> mListenerContainers = new ArrayList<>();

    /**
     * Adds a listener to receive updates when they are dispatched. This only dispatches updates and
     * does not relay the last emitted value. If called with the same listener then this method does
     * not have any effect.
     * @param executor an executor that is used to dispatch the updates.
     * @param consumer a listener interested in receiving updates.
     */
    void addListener(@NonNull Executor executor,
            @NonNull Consumer<IBinder> consumer) {
        synchronized (mLock) {
            if (indexOf(consumer) > -1) {
                return;
            }
            mListenerContainers.add(new ListenerContainer(executor, consumer));
        }
    }

    /**
     * Removes the listener that was previously registered. If the listener was not registered this
     * method does not have any effect.
     */
    void removeListener(@NonNull Consumer<IBinder> consumer) {
        synchronized (mLock) {
            final int index = indexOf(consumer);
            if (index > -1) {
                mListenerContainers.remove(index);
            }
        }
    }

    /**
     * Dispatches the update to all registered listeners
     * @param activityToken a token for the {@link Activity} that received a configuration update.
     */
    void dispatchOnConfigurationChanged(@NonNull IBinder activityToken) {
        final List<ListenerContainer> consumers;
        synchronized (mLock) {
            consumers = new ArrayList<>(mListenerContainers);
        }
        for (int i = 0; i < consumers.size(); i++) {
            consumers.get(i).accept(activityToken);
        }
    }

    @GuardedBy("mLock")
    private int indexOf(Consumer<IBinder> consumer) {
        for (int i = 0; i < mListenerContainers.size(); i++) {
            if (mListenerContainers.get(i).isMatch(consumer)) {
                return i;
            }
        }
        return -1;
    }

    private static final class ListenerContainer {

        @NonNull
        private final Executor mExecutor;
        @NonNull
        private final Consumer<IBinder> mConsumer;

        ListenerContainer(@NonNull Executor executor,
                @NonNull Consumer<IBinder> consumer) {
            mExecutor = executor;
            mConsumer = consumer;
        }

        public boolean isMatch(@NonNull Consumer<IBinder> consumer) {
            return mConsumer.equals(consumer);
        }

        public void accept(@NonNull IBinder activityToken) {
            mExecutor.execute(() -> mConsumer.accept(activityToken));
        }

    }
}
