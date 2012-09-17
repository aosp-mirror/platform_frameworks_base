/*
 * Copyright (C) 2012 The Android Open Source Project
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
package com.android.internal.policy.impl.keyguard;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;

/**
 * A layout that arranges its children into a special type of grid.
 */
public class KeyguardSubdivisionLayout extends ViewGroup {
    ArrayList<BiTree> mCells = new ArrayList<BiTree>();
    int mNumChildren = -1;
    int mWidth = -1;
    int mHeight = -1;
    int mTopChild = 0;

    public KeyguardSubdivisionLayout(Context context) {
        this(context, null, 0);
    }

    public KeyguardSubdivisionLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardSubdivisionLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        setClipChildren(false);
        setClipToPadding(false);
        setChildrenDrawingOrderEnabled(true);
    }

    private class BiTree {
        Rect rect;
        BiTree left;
        BiTree right;
        int nodeDepth;
        ArrayList<BiTree> leafs;

        public BiTree(Rect r) {
            rect = r;
        }

        public BiTree() {
        }

        boolean isLeaf() {
            return (left == null) && (right == null);
        }

        int depth() {
            if (left != null && right != null) {
                return Math.max(left.depth(), right.depth()) + 1;
            } else if (left != null) {
                return left.depth() + 1;
            } else if (right != null) {
                return right.depth() + 1;
            } else {
                return 1;
            }
        }

        int numLeafs() {
            if (left != null && right != null) {
                return left.numLeafs() + right.numLeafs();
            } else if (left != null) {
                return left.numLeafs();
            } else if (right != null) {
                return right.numLeafs();
            } else {
                return 1;
            }
        }

        BiTree getNextNodeToBranch() {
            if (leafs == null) {
                leafs = new ArrayList<BiTree>();
            }
            leafs.clear();
            getLeafs(leafs, 1);

            // If the tree is complete, then we start a new level at the rightmost side.
            double r = log2(leafs.size());
            if (Math.ceil(r) == Math.floor(r)) {
                return leafs.get(leafs.size() - 1);
            }

            // Tree is not complete, find the first leaf who's depth is less than the depth of
            // the tree.
            int treeDepth = depth();
            for (int i = leafs.size() - 1; i >= 0; i--) {
                BiTree n = leafs.get(i);
                if (n.nodeDepth < treeDepth) {
                    return n;
                }
            }
            return null;
        }

        // Gets leafs in left to right order
        void getLeafs(ArrayList<BiTree> leafs, int depth) {
            if (isLeaf()) {
                this.nodeDepth = depth;
                leafs.add(this);
            } else {
                if (left != null) {
                    left.getLeafs(leafs, depth + 1);
                }
                if (right != null) {
                    right.getLeafs(leafs, depth + 1);
                }
            }
        }
    }

    double log2(double d) {
        return Math.log(d) / Math.log(2);
    }

    private void addCell(BiTree tree) {
        BiTree branch = tree.getNextNodeToBranch();
        Rect r = branch.rect;
        branch.left = new BiTree();
        branch.right = new BiTree();
        int newDepth = tree.depth();

        // For each level of the tree, we alternate between horizontal and vertical division
        if (newDepth % 2 == 0) {
            // Divide the cell vertically
            branch.left.rect = new Rect(r.left, r.top, r.right, r.top + r.height() / 2);
            branch.right.rect = new Rect(r.left, r.top + r.height() / 2, r.right, r.bottom);
        } else {
            // Divide the cell horizontally
            branch.left.rect = new Rect(r.left, r.top, r.left + r.width() / 2, r.bottom);
            branch.right.rect = new Rect(r.left + r.width() / 2, r.top, r.right, r.bottom);
        }
    }

    private void constructGrid(int width, int height, int numChildren) {
        mCells.clear();
        BiTree root = new BiTree(new Rect(0, 0, width, height));

        // We add nodes systematically until the number of leafs matches the number of children
        while (root.numLeafs() < numChildren) {
            addCell(root);
        }

        // Spit out the final list of cells
        root.getLeafs(mCells, 1);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height =  MeasureSpec.getSize(heightMeasureSpec);
        int childCount = getChildCount();

        if (mNumChildren != childCount || width != getMeasuredWidth() ||
                height != getMeasuredHeight()) {
            constructGrid(width, height, childCount);
        }

        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            Rect rect = mCells.get(i).rect;
            child.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);
            Rect rect = mCells.get(i).rect;
            child.layout(rect.left, rect.top, rect.right, rect.bottom);
        }
    }

    public void setTopChild(int top) {
        mTopChild = top;
        invalidate();
    }

    protected int getChildDrawingOrder(int childCount, int i) {
        int ret = i;
        if (i == childCount - 1) {
            ret = mTopChild;
        } else if (i >= mTopChild){
            ret = i + 1;
        }
        return ret;
    }
}