/*
 * Copyright (C) 2008 The Android Open Source Project
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

/**
 * Supplies Requests to a Connection
 */

package android.net.http;

import org.apache.http.HttpHost;

/**
 * {@hide}
 */
interface RequestFeeder {

    Request getRequest();
    Request getRequest(HttpHost host);

    /**
     * @return true if a request for this host is available
     */
    boolean haveRequest(HttpHost host);

    /**
     * Put request back on head of queue
     */
    void requeueRequest(Request request);
}
