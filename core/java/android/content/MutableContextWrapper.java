/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content;

/**
 * Special version of {@link ContextWrapper} that allows the base context to
 * be modified after it is initially set.
 */
public class MutableContextWrapper extends ContextWrapper {
    public MutableContextWrapper(Context base) {
        super(base);
    }
    
    /**
     * Change the base context for this ContextWrapper. All calls will then be
     * delegated to the base context.  Unlike ContextWrapper, the base context
     * can be changed even after one is already set.
     * 
     * @param base The new base context for this wrapper.
     */
    public void setBaseContext(Context base) {
        mBase = base;
    }
}
