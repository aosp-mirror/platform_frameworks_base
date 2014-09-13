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

package android.telecom;

/**
 * @hide
 */
public interface Response<IN, OUT> {

    /**
     * Provide a set of results.
     *
     * @param request The original request.
     * @param result The results.
     */
    void onResult(IN request, OUT... result);

    /**
     * Indicates the inability to provide results.
     *
     * @param request The original request.
     * @param code An integer code indicating the reason for failure.
     * @param msg A message explaining the reason for failure.
     */
    void onError(IN request, int code, String msg);
}
