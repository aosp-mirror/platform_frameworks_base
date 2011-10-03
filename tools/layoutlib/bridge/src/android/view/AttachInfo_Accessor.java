/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.view;

import com.android.layoutlib.bridge.android.BridgeWindow;
import com.android.layoutlib.bridge.android.BridgeWindowSession;

import android.os.Handler;
import android.view.View.AttachInfo;

/**
 * Class allowing access to package-protected methods/fields.
 */
public class AttachInfo_Accessor {

    public static void setAttachInfo(View view) {
        AttachInfo info = new AttachInfo(new BridgeWindowSession(), new BridgeWindow(),
                new Handler(), null);
        info.mHasWindowFocus = true;
        info.mWindowVisibility = View.VISIBLE;
        info.mInTouchMode = false; // this is so that we can display selections.
        info.mHardwareAccelerated = false;
        view.dispatchAttachedToWindow(info, 0);
    }

    public static void dispatchOnPreDraw(View view) {
        view.mAttachInfo.mTreeObserver.dispatchOnPreDraw();
    }
}
