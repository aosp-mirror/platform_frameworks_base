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
package com.android.server.usb.descriptors.report;

import com.android.server.usb.descriptors.UsbDescriptor;

/**
 * Declares the Reporter interface to provide HTML reporting for UsbDescriptor (sub)classes.
 *
 * NOTE: It is the responsibility of the implementor of this interface to correctly
 * interpret/decode the SPECIFIC UsbDescriptor subclass (perhaps with 'instanceof') that is
 * passed and handle that in the appropriate manner. This appears to be a
 * not very object-oriented approach, and that is true. This approach DOES however move the
 * complexity and 'plumbing' of reporting into the Reporter implementation and avoids needing
 * a (trivial) type-specific call to 'report()' in each UsbDescriptor (sub)class, instead
 * having just one in the top-level UsbDescriptor class. It also removes the need to add new
 * type-specific 'report()' methods to be added to Reporter interface whenever a
 * new UsbDescriptor subclass is defined. This seems like a pretty good trade-off.
 *
 * See HTMLReporter.java in this package for an example of type decoding.
 */
public interface Reporter {
    /**
     * Generate report for this UsbDescriptor descriptor
     */
    void report(UsbDescriptor descriptor);
}
