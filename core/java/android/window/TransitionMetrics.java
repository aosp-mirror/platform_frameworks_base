/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.window;

import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Singleton;

/**
 * A helper class for who plays transition animation can report its metrics easily.
 * @hide
 */
public class TransitionMetrics {

    private final ITransitionMetricsReporter mTransitionMetricsReporter;

    private TransitionMetrics(ITransitionMetricsReporter reporter) {
        mTransitionMetricsReporter = reporter;
    }

    /** Reports the current timestamp as when the transition animation starts. */
    public void reportAnimationStart(IBinder transitionToken) {
        try {
            mTransitionMetricsReporter.reportAnimationStart(transitionToken,
                    SystemClock.elapsedRealtime());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** Gets the singleton instance of TransitionMetrics. */
    public static TransitionMetrics getInstance() {
        return sTransitionMetrics.get();
    }

    private static final Singleton<TransitionMetrics> sTransitionMetrics = new Singleton<>() {
        @Override
        protected TransitionMetrics create() {
            return new TransitionMetrics(WindowOrganizer.getTransitionMetricsReporter());
        }
    };
}
