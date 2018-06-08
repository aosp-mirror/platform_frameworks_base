/*
 * Copyright 2017 The Android Open Source Project
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

package android.app.servertransaction;

import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Request for lifecycle state that an activity should reach.
 * @hide
 */
public abstract class ActivityLifecycleItem extends ClientTransactionItem {

    @IntDef(prefix = { "UNDEFINED", "PRE_", "ON_" }, value = {
            UNDEFINED,
            PRE_ON_CREATE,
            ON_CREATE,
            ON_START,
            ON_RESUME,
            ON_PAUSE,
            ON_STOP,
            ON_DESTROY,
            ON_RESTART
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface LifecycleState{}
    public static final int UNDEFINED = -1;
    public static final int PRE_ON_CREATE = 0;
    public static final int ON_CREATE = 1;
    public static final int ON_START = 2;
    public static final int ON_RESUME = 3;
    public static final int ON_PAUSE = 4;
    public static final int ON_STOP = 5;
    public static final int ON_DESTROY = 6;
    public static final int ON_RESTART = 7;

    /** A final lifecycle state that an activity should reach. */
    @LifecycleState
    public abstract int getTargetState();

    /** Called by subclasses to make sure base implementation is cleaned up */
    @Override
    public void recycle() {
    }
}
