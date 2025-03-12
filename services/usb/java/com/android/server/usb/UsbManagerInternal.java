/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.usb;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.hardware.usb.IUsbOperationInternal;
import android.hardware.usb.UsbPort;
import android.util.ArraySet;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * UsbManagerInternal provides internal APIs for the UsbService to
 * reduce IPC overhead costs and support internal USB data signal stakers.
 *
 * @hide Only for use within the system server.
 */
public abstract class UsbManagerInternal {

  public static final int OS_USB_DISABLE_REASON_AAPM = 0;
  public static final int OS_USB_DISABLE_REASON_LOCKDOWN_MODE = 1;

  @Retention(RetentionPolicy.SOURCE)
  @IntDef(value = {OS_USB_DISABLE_REASON_AAPM,
    OS_USB_DISABLE_REASON_LOCKDOWN_MODE})
  public @interface OsUsbDisableReason {
  }

  public abstract boolean enableUsbData(String portId, boolean enable,
      int operationId, IUsbOperationInternal callback, @OsUsbDisableReason int disableReason);

  public abstract UsbPort[] getPorts();

}