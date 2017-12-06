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

package android.app.slice;

import android.annotation.SystemService;
import android.content.Context;
import android.os.Handler;
import android.os.ServiceManager;
import android.os.ServiceManager.ServiceNotFoundException;

/**
 * @hide
 */
@SystemService(Context.SLICE_SERVICE)
public class SliceManager {

    private final ISliceManager mService;
    private final Context mContext;

    public SliceManager(Context context, Handler handler) throws ServiceNotFoundException {
        mContext = context;
        mService = ISliceManager.Stub.asInterface(
                ServiceManager.getServiceOrThrow(Context.SLICE_SERVICE));
    }
}
