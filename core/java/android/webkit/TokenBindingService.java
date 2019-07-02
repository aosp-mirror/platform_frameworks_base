/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.webkit;

import android.annotation.SystemApi;

/**
 * Enables the token binding procotol, and provides access to the keys. See
 * https://tools.ietf.org/html/draft-ietf-tokbind-protocol-03
 *
 * All methods are required to be called on the UI thread where WebView is
 * attached to the View hierarchy.
 * @hide
 * @deprecated this is no longer supported.
 */
@SystemApi
@Deprecated
public abstract class TokenBindingService {
}
