/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.media.tv.interactive;

import android.media.tv.interactive.ITvIAppSession;

/**
 * Helper interface for ITvIAppSession to allow TvIAppService to notify the system service when
 * there is a related event.
 * @hide
 */
oneway interface ITvIAppSessionCallback {
    void onSessionCreated(in ITvIAppSession session);
    void onLayoutSurface(int left, int top, int right, int bottom);
}