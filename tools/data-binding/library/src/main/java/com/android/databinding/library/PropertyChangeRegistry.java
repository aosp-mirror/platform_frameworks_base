/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.databinding.library;

public class PropertyChangeRegistry extends
        CallbackRegistry<OnPropertyChangedListener, Observable, Void> {

    private static final CallbackRegistry.NotifierCallback<OnPropertyChangedListener, Observable, Void> NOTIFIER_CALLBACK = new CallbackRegistry.NotifierCallback<OnPropertyChangedListener, Observable, Void>() {
        @Override
        public void onNotifyCallback(OnPropertyChangedListener callback, Observable sender,
                int arg, Void notUsed) {
            callback.onPropertyChanged(sender, arg);
        }
    };

    public PropertyChangeRegistry() {
        super(NOTIFIER_CALLBACK);
    }

    public void notifyChange(Observable observable, int propertyId) {
        notifyCallbacks(observable, propertyId, null);
    }
}
