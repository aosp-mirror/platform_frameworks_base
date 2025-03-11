/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.app.Activity;
import android.util.ArrayMap;

import com.android.window.flags.Flags;

import java.lang.ref.WeakReference;

/**
 * Utility class providing {@link OnBackInvokedCallback}s to override the default behavior when
 * system back is invoked. e.g. {@link Activity#finish}
 *
 * <p>By registering these callbacks with the {@link OnBackInvokedDispatcher}, the system can
 * trigger specific behaviors and play corresponding ahead-of-time animations when the back
 * gesture is invoked.
 *
 * <p>For example, to trigger the {@link Activity#moveTaskToBack} behavior:
 * <pre>
 *   OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
 *   dispatcher.registerOnBackInvokedCallback(
 *       OnBackInvokedDispatcher.PRIORITY_DEFAULT,
 *       SystemOnBackInvokedCallbacks.moveTaskToBackCallback(activity));
 * </pre>
 */
@SuppressWarnings("SingularCallback")
@FlaggedApi(Flags.FLAG_PREDICTIVE_BACK_SYSTEM_OVERRIDE_CALLBACK)
public final class SystemOnBackInvokedCallbacks {
    private static final OverrideCallbackFactory<Activity> sMoveTaskToBackFactory = new
            MoveTaskToBackCallbackFactory();
    private static final OverrideCallbackFactory<Activity> sFinishAndRemoveTaskFactory = new
            FinishAndRemoveTaskCallbackFactory();

    private SystemOnBackInvokedCallbacks() {
        throw new UnsupportedOperationException("This is a utility class and cannot be "
                + "instantiated");
    }

    /**
     * <p>Get a callback to triggers {@link Activity#moveTaskToBack(boolean)} on the associated
     * {@link Activity}, moving the task containing the activity to the background. The system
     * will play the corresponding transition animation, regardless of whether the activity
     * is the root activity of the task.</p>
     *
     * @param activity The associated {@link Activity}
     * @see Activity#moveTaskToBack(boolean)
     */
    @FlaggedApi(Flags.FLAG_PREDICTIVE_BACK_SYSTEM_OVERRIDE_CALLBACK)
    @NonNull
    public static OnBackInvokedCallback moveTaskToBackCallback(@NonNull Activity activity) {
        return sMoveTaskToBackFactory.getOverrideCallback(activity);
    }

    /**
     * <p>Get a callback to triggers {@link Activity#finishAndRemoveTask()} on the associated
     * {@link Activity}. If the activity is the root activity of its task, the entire task
     * will be removed from the recents task. The activity will be finished in all cases.
     * The system will play the corresponding transition animation.</p>
     *
     * @param activity The associated {@link Activity}
     * @see Activity#finishAndRemoveTask()
     */
    @FlaggedApi(Flags.FLAG_PREDICTIVE_BACK_SYSTEM_OVERRIDE_CALLBACK)
    @NonNull
    public static OnBackInvokedCallback finishAndRemoveTaskCallback(@NonNull Activity activity) {
        return sFinishAndRemoveTaskFactory.getOverrideCallback(activity);
    }

    /**
     * Abstract factory for creating system override {@link SystemOverrideOnBackInvokedCallback}
     * instances.
     *
     * <p>Concrete implementations of this factory are responsible for creating callbacks that
     * override the default system back navigation behavior. These callbacks should be used
     * exclusively for system overrides and should never be invoked directly.</p>
     */
    private abstract static class OverrideCallbackFactory<TYPE> {
        private final ArrayMap<WeakReference<TYPE>,
                WeakReference<SystemOverrideOnBackInvokedCallback>> mObjectMap = new ArrayMap<>();

        protected abstract SystemOverrideOnBackInvokedCallback createCallback(
                @NonNull TYPE context);

        @NonNull SystemOverrideOnBackInvokedCallback getOverrideCallback(@NonNull TYPE object) {
            if (object == null) {
                throw new NullPointerException("Input object cannot be null");
            }
            synchronized (mObjectMap) {
                WeakReference<SystemOverrideOnBackInvokedCallback> callback = null;
                for (int i = mObjectMap.size() - 1; i >= 0; --i) {
                    final WeakReference<TYPE> next = mObjectMap.keyAt(i);
                    if (next.get() == object) {
                        callback = mObjectMap.get(next);
                        break;
                    }
                }
                if (callback != null) {
                    return callback.get();
                }
                final SystemOverrideOnBackInvokedCallback contextCallback = createCallback(object);
                if (contextCallback != null) {
                    mObjectMap.put(new WeakReference<>(object),
                            new WeakReference<>(contextCallback));
                }
                return contextCallback;
            }
        }
    }

    private static class MoveTaskToBackCallbackFactory extends OverrideCallbackFactory<Activity> {
        @Override
        protected SystemOverrideOnBackInvokedCallback createCallback(Activity activity) {
            final WeakReference<Activity> activityRef = new WeakReference<>(activity);
            return new SystemOverrideOnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    if (activityRef.get() != null) {
                        activityRef.get().moveTaskToBack(true /* nonRoot */);
                    }
                }

                @Override
                public int overrideBehavior() {
                    return OVERRIDE_MOVE_TASK_TO_BACK;
                }
            };
        }
    }

    private static class FinishAndRemoveTaskCallbackFactory extends
            OverrideCallbackFactory<Activity> {
        @Override
        protected SystemOverrideOnBackInvokedCallback createCallback(Activity activity) {
            final WeakReference<Activity> activityRef = new WeakReference<>(activity);
            return new SystemOverrideOnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    if (activityRef.get() != null) {
                        activityRef.get().finishAndRemoveTask();
                    }
                }

                @Override
                public int overrideBehavior() {
                    return OVERRIDE_FINISH_AND_REMOVE_TASK;
                }
            };
        }
    }
}
