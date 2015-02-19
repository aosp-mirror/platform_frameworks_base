/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

/**
 * Delegate implementing the native methods of {@link RenderNode}
 * <p/>
 * Through the layoutlib_create tool, some native methods of RenderNode have been replaced by calls
 * to methods of the same name in this delegate class.
 *
 * @see DelegateManager
 */
public class RenderNode_Delegate {


    // ---- delegate manager ----
    private static final DelegateManager<RenderNode_Delegate> sManager =
            new DelegateManager<RenderNode_Delegate>(RenderNode_Delegate.class);


    private float mLift;
    @SuppressWarnings("UnusedDeclaration")
    private String mName;

    @LayoutlibDelegate
    /*package*/ static long nCreate(String name) {
        RenderNode_Delegate renderNodeDelegate = new RenderNode_Delegate();
        renderNodeDelegate.mName = name;
        return sManager.addNewDelegate(renderNodeDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static void nDestroyRenderNode(long renderNode) {
        sManager.removeJavaReferenceFor(renderNode);
    }

    @LayoutlibDelegate
    /*package*/ static boolean nSetElevation(long renderNode, float lift) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null && delegate.mLift != lift) {
            delegate.mLift = lift;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static float nGetElevation(long renderNode) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null) {
            return delegate.mLift;
        }
        return 0f;
    }
}
