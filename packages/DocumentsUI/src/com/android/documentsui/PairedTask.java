/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui;

import android.app.Activity;
import android.os.AsyncTask;

/**
 * An {@link AsyncTask} that guards work with checks that a paired {@link Activity}
 * is still alive. Instances of this class make no progress.
 *
 * <p>Use this type of task for greater safety when executing tasks that might complete
 * after an Activity is destroyed.
 *
 * <p>Also useful as tasks can be static, limiting scope, but still have access to
 * the owning class (by way the A template and the mActivity field).
 *
 * @template Owner Activity type.
 * @template Input input type
 * @template Output output type
 */
abstract class PairedTask<Owner extends Activity, Input, Output>
        extends AsyncTask<Input, Void, Output> {

    protected final Owner mOwner;

    public PairedTask(Owner owner) {
        mOwner = owner;
    }

    /** Called prior to run being executed. Analogous to {@link AsyncTask#onPreExecute} */
    void prepare() {}

    /** Analogous to {@link AsyncTask#doInBackground} */
    abstract Output run(Input... input);

    /** Analogous to {@link AsyncTask#onPostExecute} */
    abstract void finish(Output output);

    @Override
    final protected void onPreExecute() {
        if (mOwner.isDestroyed()) {
            return;
        }
        prepare();
    }

    @Override
    final protected Output doInBackground(Input... input) {
        if (mOwner.isDestroyed()) {
            return null;
        }
        return run(input);
    }

    @Override
    final protected void onPostExecute(Output result) {
        if (mOwner.isDestroyed()) {
            return;
        }
        finish(result);
    }
}
