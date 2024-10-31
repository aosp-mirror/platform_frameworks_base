/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.text;

/**
 * An aconfig feature flags that can be accessible from application process without
 * ContentProvider IPCs.
 *
 * When you add new flags, you have to add flag string to {@link TextFlags#TEXT_ACONFIGS_FLAGS}.
 *
 * TODO(nona): Remove this class.
 * @hide
 */
public class ClientFlags {
}
