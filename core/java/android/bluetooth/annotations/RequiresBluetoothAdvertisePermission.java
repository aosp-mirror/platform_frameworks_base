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
import android.os.Build;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * @memberDoc For apps targeting {@link Build.VERSION_CODES#S} or or higher,
 *            this requires the {@link Manifest.permission#BLUETOOTH_ADVERTISE}
 *            permission which can be gained with
 *            {@link android.app.Activity#requestPermissions(String[], int)}.
 * @hide
 */
@Retention(SOURCE)
@Target({METHOD, FIELD})
public @interface RequiresBluetoothAdvertisePermission {
}
