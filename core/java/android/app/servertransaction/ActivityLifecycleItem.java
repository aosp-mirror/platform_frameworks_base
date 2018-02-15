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
import android.os.Parcel;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Request for lifecycle state that an activity should reach.
 * @hide
 */
public abstract class ActivityLifecycleItem extends ClientTransactionItem {
    private String mDescription;

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


    protected ActivityLifecycleItem() {
    }

    protected ActivityLifecycleItem(Parcel in) {
        mDescription = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mDescription);
    }

    /**
     * Sets a description that can be retrieved later for debugging purposes.
     * @param description Description to set.
     * @return The {@link ActivityLifecycleItem}.
     */
    public ActivityLifecycleItem setDescription(String description) {
        mDescription = description;
        return this;
    }

    /**
     * Retrieves description if set through {@link #setDescription(String)}.
     */
    public String getDescription() {
        return mDescription;
    }

    void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "target state:" + getTargetState());
        pw.println(prefix + "description: " + mDescription);
    }

    @Override
    public void recycle() {
        setDescription(null);
    }
}
