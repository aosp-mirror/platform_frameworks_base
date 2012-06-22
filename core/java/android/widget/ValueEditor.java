/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.widget;

import android.util.ValueModel;

/**
 * An interface for editors of simple values. Classes implementing this interface are normally
 * UI controls (subclasses of {@link android.view.View View}) that can provide a suitable
 * user interface to display and edit values of the specified type. This interface is
 * intended to describe editors for simple types, like {@code boolean}, {@code int} or
 * {@code String}, where the values themselves are immutable.
 * <p>
 * For example, {@link android.widget.CheckBox CheckBox} implements
 * this interface for the Boolean type as it is capable of providing an appropriate
 * mechanism for displaying and changing the value of a Boolean property.
 *
 * @param <T> the value type that this editor supports
 */
public interface ValueEditor<T> {
    /**
     * Return the last value model that was set. If no value model has been set, the editor
     * should return the value {@link android.util.ValueModel#EMPTY}.
     *
     * @return the value model
     */
    public ValueModel<T> getValueModel();

    /**
     * Sets the value model for this editor. When the value model is set, the editor should
     * retrieve the value from the value model, using {@link android.util.ValueModel#get()},
     * and set its internal state accordingly. Likewise, when the editor's internal state changes
     * it should update the value model by calling  {@link android.util.ValueModel#set(T)}
     * with the appropriate value.
     *
     * @param valueModel the new value model for this editor.
     */
    public void setValueModel(ValueModel<T> valueModel);
}
