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
package android.view.intelligence;

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.assist.AssistStructure;
import android.view.autofill.AutofillId;

//TODO(b/111276913): add javadocs / implement Parcelable / implement
//TODO(b/111276913): for now it's extending ViewNode directly as it needs most of its properties,
// but it might be better to create a common, abstract android.view.ViewNode class that both extend
// instead
/** @hide */
@SystemApi
public final class ViewNode extends AssistStructure.ViewNode {

    /** @hide */
    public ViewNode() {
    }

    /**
     * Returns the {@link AutofillId} of this view's parent, if the parent is also part of the
     * screen observation tree.
     */
    @Nullable
    public AutofillId getParentAutofillId() {
        //TODO(b/111276913): implement
        return null;
    }
}
