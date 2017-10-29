/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * Helper class used to change a child view of a {@link android.widget.RemoteViews presentation
 * template} at runtime, using the values of fields contained in the screen.
 *
 * <p>Typically used by {@link CustomDescription} to provide a customized autofill save UI.
 */
public interface Transformation {
}
