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

import android.os.AsyncTask;

/**
 * An {@link AsyncTask} that guards work with checks that a paired {@link Check}
 * has not yet given signals to stop progress.
 *
 * <p>Use this type of task for greater safety when executing tasks that might complete
 * after the owner of the task has explicitly given a signal to stop progress.
 *
 * <p>Also useful as tasks can be static, limiting scope, but still have access to
 * signal from the owning class.
 *
 * @template Input input type
 * @template Output output type
 */
abstract class CheckedTask<Input, Output>
        extends AsyncTask<Input, Void, Output> {

    private Check mCheck;

    public CheckedTask(Check check) {
        mCheck = check;
    }

    /** Called prior to run being executed. Analogous to {@link AsyncTask#onPreExecute} */
    void prepare() {}

    /** Analogous to {@link AsyncTask#doInBackground} */
    abstract Output run(Input... input);

    /** Analogous to {@link AsyncTask#onPostExecute} */
    abstract void finish(Output output);

    @Override
    final protected void onPreExecute() {
        if (mCheck.stop()) {
            return;
        }
        prepare();
    }

    @Override
    final protected Output doInBackground(Input... input) {
        if (mCheck.stop()) {
            return null;
        }
        return run(input);
    }

    @Override
    final protected void onPostExecute(Output result) {
        if (mCheck.stop()) {
            return;
        }
        finish(result);
    }

    @FunctionalInterface
    interface Check {
        boolean stop();
    }
}
