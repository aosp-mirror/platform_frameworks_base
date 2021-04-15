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

package android.bluetooth.annotations;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.Manifest;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * @memberDoc In addition, this requires either the
 *            {@link Manifest.permission#ACCESS_FINE_LOCATION}
 *            permission or a strong assertion that you will never derive the
 *            physical location of the device. You can make this assertion by
 *            declaring {@code usesPermissionFlags="neverForLocation"} on the
 *            relevant {@code <uses-permission>} manifest tag, but it may
 *            restrict the types of Bluetooth devices you can interact with.
 * @hide
 */
@Retention(SOURCE)
@Target({METHOD, FIELD})
public @interface RequiresBluetoothLocationPermission {
}
