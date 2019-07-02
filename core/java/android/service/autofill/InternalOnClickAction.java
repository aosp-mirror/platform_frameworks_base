/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.service.autofill;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcelable;
import android.view.ViewGroup;

/**
 * Superclass of all {@link OnClickAction} the system understands. As this is not public, all public
 * subclasses have to implement {@link OnClickAction} again.
 *
 * @hide
 */
@TestApi
public abstract class InternalOnClickAction implements OnClickAction, Parcelable {

    /**
     * Applies the action to the children of the {@code rootView} when clicked.
     */
    public abstract void onClick(@NonNull ViewGroup rootView);
}
