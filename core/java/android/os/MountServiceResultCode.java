/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.os;

import java.io.IOException;

/**
 * Class that provides access to constants returned from MountService APIs
 *
 * {@hide}
 */
public class MountServiceResultCode
{
    public static final int OperationSucceeded               =  0;
    public static final int OperationFailedInternalError     = -1;
    public static final int OperationFailedNoMedia           = -2;
    public static final int OperationFailedMediaBlank        = -3;
    public static final int OperationFailedMediaCorrupt      = -4;
    public static final int OperationFailedVolumeNotMounted  = -5;
}
