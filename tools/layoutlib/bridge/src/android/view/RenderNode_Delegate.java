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

import android.graphics.Matrix;

import libcore.util.NativeAllocationRegistry_Delegate;

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
    private static long sFinalizer = -1;

    private float mLift;
    private float mTranslationX;
    private float mTranslationY;
    private float mTranslationZ;
    private float mRotation;
    private float mScaleX = 1;
    private float mScaleY = 1;
    private float mPivotX;
    private float mPivotY;
    private boolean mPivotExplicitlySet;
    private int mLeft;
    private int mRight;
    private int mTop;
    private int mBottom;
    @SuppressWarnings("UnusedDeclaration")
    private String mName;

    @LayoutlibDelegate
    /*package*/ static long nCreate(String name) {
        RenderNode_Delegate renderNodeDelegate = new RenderNode_Delegate();
        renderNodeDelegate.mName = name;
        return sManager.addNewDelegate(renderNodeDelegate);
    }

    @LayoutlibDelegate
    /*package*/ static long nGetNativeFinalizer() {
        synchronized (RenderNode_Delegate.class) {
            if (sFinalizer == -1) {
                sFinalizer = NativeAllocationRegistry_Delegate.createFinalizer(sManager::removeJavaReferenceFor);
            }
        }
        return sFinalizer;
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

    @LayoutlibDelegate
    /*package*/ static boolean nSetTranslationX(long renderNode, float translationX) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null && delegate.mTranslationX != translationX) {
            delegate.mTranslationX = translationX;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static float nGetTranslationX(long renderNode) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null) {
            return delegate.mTranslationX;
        }
        return 0f;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nSetTranslationY(long renderNode, float translationY) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null && delegate.mTranslationY != translationY) {
            delegate.mTranslationY = translationY;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static float nGetTranslationY(long renderNode) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null) {
            return delegate.mTranslationY;
        }
        return 0f;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nSetTranslationZ(long renderNode, float translationZ) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null && delegate.mTranslationZ != translationZ) {
            delegate.mTranslationZ = translationZ;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static float nGetTranslationZ(long renderNode) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null) {
            return delegate.mTranslationZ;
        }
        return 0f;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nSetRotation(long renderNode, float rotation) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null && delegate.mRotation != rotation) {
            delegate.mRotation = rotation;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static float nGetRotation(long renderNode) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null) {
            return delegate.mRotation;
        }
        return 0f;
    }

    @LayoutlibDelegate
    /*package*/ static void getMatrix(RenderNode renderNode, Matrix outMatrix) {
        outMatrix.reset();
        if (renderNode != null) {
            float rotation = renderNode.getRotation();
            float translationX = renderNode.getTranslationX();
            float translationY = renderNode.getTranslationY();
            float pivotX = renderNode.getPivotX();
            float pivotY = renderNode.getPivotY();
            float scaleX = renderNode.getScaleX();
            float scaleY = renderNode.getScaleY();

            outMatrix.setTranslate(translationX, translationY);
            outMatrix.preRotate(rotation, pivotX, pivotY);
            outMatrix.preScale(scaleX, scaleY, pivotX, pivotY);
        }
    }

    @LayoutlibDelegate
    /*package*/ static boolean nSetLeft(long renderNode, int left) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null && delegate.mLeft != left) {
            delegate.mLeft = left;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nSetTop(long renderNode, int top) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null && delegate.mTop != top) {
            delegate.mTop = top;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nSetRight(long renderNode, int right) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null && delegate.mRight != right) {
            delegate.mRight = right;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nSetBottom(long renderNode, int bottom) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null && delegate.mBottom != bottom) {
            delegate.mBottom = bottom;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nSetLeftTopRightBottom(long renderNode, int left, int top, int right,
            int bottom) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null && (delegate.mLeft != left || delegate.mTop != top || delegate
                .mRight != right || delegate.mBottom != bottom)) {
            delegate.mLeft = left;
            delegate.mTop = top;
            delegate.mRight = right;
            delegate.mBottom = bottom;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nIsPivotExplicitlySet(long renderNode) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        return delegate != null && delegate.mPivotExplicitlySet;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nSetPivotX(long renderNode, float pivotX) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null) {
            delegate.mPivotX = pivotX;
            delegate.mPivotExplicitlySet = true;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static float nGetPivotX(long renderNode) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null) {
            if (delegate.mPivotExplicitlySet) {
                return delegate.mPivotX;
            } else {
                return (delegate.mRight - delegate.mLeft) / 2.0f;
            }
        }
        return 0f;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nSetPivotY(long renderNode, float pivotY) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null) {
            delegate.mPivotY = pivotY;
            delegate.mPivotExplicitlySet = true;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static float nGetPivotY(long renderNode) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null) {
            if (delegate.mPivotExplicitlySet) {
                return delegate.mPivotY;
            } else {
                return (delegate.mBottom - delegate.mTop) / 2.0f;
            }
        }
        return 0f;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nSetScaleX(long renderNode, float scaleX) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null && delegate.mScaleX != scaleX) {
            delegate.mScaleX = scaleX;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static float nGetScaleX(long renderNode) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null) {
            return delegate.mScaleX;
        }
        return 0f;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nSetScaleY(long renderNode, float scaleY) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null && delegate.mScaleY != scaleY) {
            delegate.mScaleY = scaleY;
            return true;
        }
        return false;
    }

    @LayoutlibDelegate
    /*package*/ static float nGetScaleY(long renderNode) {
        RenderNode_Delegate delegate = sManager.getDelegate(renderNode);
        if (delegate != null) {
            return delegate.mScaleY;
        }
        return 0f;
    }
}
