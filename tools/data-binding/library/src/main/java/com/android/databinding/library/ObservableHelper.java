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

import java.util.concurrent.CopyOnWriteArraySet;

public class ObservableHelper implements Observable {
    final Observable owner;
    CopyOnWriteArraySet<ObservableListener> mListeners;

    public ObservableHelper(Observable owner) {
        this.owner = owner;
    }

    private synchronized CopyOnWriteArraySet<ObservableListener> getListeners(boolean createIfMissing) {
        if (mListeners == null) {
            if (createIfMissing) {
                mListeners = new CopyOnWriteArraySet<>();
            }
        }
        return mListeners;
    }

    public void fireChange() {
        fireChange(0);
    }
    public void fireChange(int fieldId) {
        final CopyOnWriteArraySet<ObservableListener> listeners = getListeners(false);
        if (listeners == null) {
            return;
        }
        for (ObservableListener listener : listeners) {
            listener.onChange(fieldId);
        }
    }

    @Override
    public void register(ObservableListener listener) {
        getListeners(true).add(listener);
    }

    @Override
    public void unRegister(ObservableListener listener) {
        final CopyOnWriteArraySet<ObservableListener> listeners = getListeners(false);
        if (listener != null) {
            listeners.remove(listener);
        }
    }
}
