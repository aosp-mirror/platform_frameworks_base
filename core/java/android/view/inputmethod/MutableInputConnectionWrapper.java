/*
 * Copyright (C) 2007-2008 The Android Open Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view.inputmethod;


/**
 * Special version of {@link InputConnectionWrapper} that allows the base
 * input connection to be modified after it is initially set.
 */
public class MutableInputConnectionWrapper extends InputConnectionWrapper {
    public MutableInputConnectionWrapper(InputConnection base) {
        super(base);
    }

    /**
     * Change the base InputConnection for this wrapper. All calls will then be
     * delegated to the base input connection.
     * 
     * @param base The new base InputConnection for this wrapper.
     */
    public void setBaseInputConnection(InputConnection base) {
        mBase = base;
    }
}
