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

package android.media.update;

import android.annotation.SystemApi;
import android.widget.MediaController2;

/**
 * Interface for connecting the public API to an updatable implementation.
 *
 * This interface provides access to constructors and static methods that are otherwise not directly
 * accessible via an implementation object.
 *
 * @hide
 */
// TODO @SystemApi
public interface StaticProvider {
    MediaController2Provider createMediaController2(
            MediaController2 instance, ViewProvider superProvider);
}
