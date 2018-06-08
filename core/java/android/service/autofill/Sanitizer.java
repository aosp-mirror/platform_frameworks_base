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
 * Helper class used to sanitize user input before using it in a save request.
 *
 * <p>Typically used to avoid displaying the save UI for values that are autofilled but reformatted
 * by the app&mdash;for example, if the autofill service sends a credit card number
 * value as "004815162342108" and the app automatically changes it to "0048 1516 2342 108".
 */
public interface Sanitizer {
}
