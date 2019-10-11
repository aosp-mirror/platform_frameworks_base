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

/**
 * Class used to define an action to be performed when a child view in a
 * {@link android.widget.RemoteViews presentation} is clicked.
 *
 * <p>Typically used to switch the visibility of other views in a
 * {@link CustomDescription custom save UI}.
 *
 * <p><b>Note:</b> This interface is not meant to be implemented by app developers; only
 * implementations provided by the Android System can be used in other Autofill APIs.
 */
public interface OnClickAction {
}
