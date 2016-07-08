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
 * An {@link CheckedTask} that guards work with checks that a paired {@link Activity}
 * is still alive. Instances of this class make no progress.
 *
 * @template Owner Activity type.
 * @template Input input type
 * @template Output output type
 */
abstract class PairedTask<Owner extends Activity, Input, Output>
        extends CheckedTask<Input, Output> {

    protected final Owner mOwner;

    public PairedTask(Owner owner) {
        super(owner::isDestroyed);
        mOwner = owner;
    }
}
