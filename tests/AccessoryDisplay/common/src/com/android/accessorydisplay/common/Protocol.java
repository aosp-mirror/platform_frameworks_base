/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.accessorydisplay.common;

/**
 * Defines message types.
 */
public class Protocol {
    // Message header.
    //   0: service id (16 bits)
    //   2: what (16 bits)
    //   4: content size (32 bits)
    //   8: ... content follows ...
    static final int HEADER_SIZE = 8;

    // Maximum size of a message envelope including the header and contents.
    static final int MAX_ENVELOPE_SIZE = 64 * 1024;

    /**
     * Maximum message content size.
     */
    public static final int MAX_CONTENT_SIZE = MAX_ENVELOPE_SIZE - HEADER_SIZE;

    public static final class DisplaySinkService {
        private DisplaySinkService() { }

        public static final int ID = 1;

        // Query sink capabilities.
        // Replies with sink available or not available.
        public static final int MSG_QUERY = 1;

        // Send MPEG2-TS H.264 encoded content.
        public static final int MSG_CONTENT = 2;
    }

    public static final class DisplaySourceService {
        private DisplaySourceService() { }

        public static final int ID = 2;

        // Sink is now available for use.
        //   0: width (32 bits)
        //   4: height (32 bits)
        //   8: density dpi (32 bits)
        public static final int MSG_SINK_AVAILABLE = 1;

        // Sink is no longer available for use.
        public static final int MSG_SINK_NOT_AVAILABLE = 2;
    }
}
