/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui;

import android.content.Context;

/**
 * Starts up SystemUI using the AOSP {@link SystemUIInitializerImpl}.
 *
 * This initializer relies on reflection to start everything up and should be considered deprecated.
 * Instead, create your own {@link SystemUIAppComponentFactoryBase}, specify it in your
 * AndroidManifest.xml and construct your own {@link SystemUIInitializer} directly.
 *
 * @deprecated Define your own SystemUIAppComponentFactoryBase implementation and use that. This
 *             implementation may be changed or removed in future releases.
 */
@Deprecated
public class SystemUIAppComponentFactory extends SystemUIAppComponentFactoryBase {
    @Override
    protected SystemUIInitializer createSystemUIInitializer(Context context) {
        return SystemUIInitializerFactory.createWithContext(context);
    }
}
