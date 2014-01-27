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

package com.android.layoutlib.bridge.impl.binding;

import com.android.ide.common.rendering.api.AdapterBinding;
import com.android.ide.common.rendering.api.DataBindingItem;
import com.android.ide.common.rendering.api.IProjectCallback;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.IProjectCallback.ViewAttribute;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.impl.RenderAction;
import com.android.util.Pair;

import android.database.DataSetObserver;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Checkable;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Base adapter to do fake data binding in {@link AdapterView} objects.
 */
public class BaseAdapter {

    /**
     * This is the items provided by the adapter. They are dynamically generated.
     */
    protected final static class AdapterItem {
        private final DataBindingItem mItem;
        private final int mType;
        private final int mFullPosition;
        private final int mPositionPerType;
        private List<AdapterItem> mChildren;

        protected AdapterItem(DataBindingItem item, int type, int fullPosition,
                int positionPerType) {
            mItem = item;
            mType = type;
            mFullPosition = fullPosition;
            mPositionPerType = positionPerType;
        }

        void addChild(AdapterItem child) {
            if (mChildren == null) {
                mChildren = new ArrayList<AdapterItem>();
            }

            mChildren.add(child);
        }

        List<AdapterItem> getChildren() {
            if (mChildren != null) {
                return mChildren;
            }

            return Collections.emptyList();
        }

        int getType() {
            return mType;
        }

        int getFullPosition() {
            return mFullPosition;
        }

        int getPositionPerType() {
            return mPositionPerType;
        }

        DataBindingItem getDataBindingItem() {
            return mItem;
        }
    }

    private final AdapterBinding mBinding;
    private final IProjectCallback mCallback;
    private final ResourceReference mAdapterRef;
    private boolean mSkipCallbackParser = false;

    protected final List<AdapterItem> mItems = new ArrayList<AdapterItem>();

    protected BaseAdapter(ResourceReference adapterRef, AdapterBinding binding,
            IProjectCallback callback) {
        mAdapterRef = adapterRef;
        mBinding = binding;
        mCallback = callback;
    }

    // ------- Some Adapter method used by all children classes.

    public boolean areAllItemsEnabled() {
        return true;
    }

    public boolean hasStableIds() {
        return true;
    }

    public boolean isEmpty() {
        return mItems.size() == 0;
    }

    public void registerDataSetObserver(DataSetObserver observer) {
        // pass
    }

    public void unregisterDataSetObserver(DataSetObserver observer) {
        // pass
    }

    // -------


    protected AdapterBinding getBinding() {
        return mBinding;
    }

    protected View getView(AdapterItem item, AdapterItem parentItem, View convertView,
            ViewGroup parent) {
        // we don't care about recycling here because we never scroll.
        DataBindingItem dataBindingItem = item.getDataBindingItem();

        BridgeContext context = RenderAction.getCurrentContext();

        Pair<View, Boolean> pair = context.inflateView(dataBindingItem.getViewReference(),
                parent, false /*attachToRoot*/, mSkipCallbackParser);

        View view = pair.getFirst();
        mSkipCallbackParser |= pair.getSecond();

        if (view != null) {
            fillView(context, view, item, parentItem);
        } else {
            // create a text view to display an error.
            TextView tv = new TextView(context);
            tv.setText("Unable to find layout: " + dataBindingItem.getViewReference().getName());
            view = tv;
        }

        return view;
    }

    private void fillView(BridgeContext context, View view, AdapterItem item,
            AdapterItem parentItem) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            final int count = group.getChildCount();
            for (int i = 0 ; i < count ; i++) {
                fillView(context, group.getChildAt(i), item, parentItem);
            }
        } else {
            int id = view.getId();
            if (id != 0) {
                ResourceReference resolvedRef = context.resolveId(id);
                if (resolvedRef != null) {
                    int fullPosition = item.getFullPosition();
                    int positionPerType = item.getPositionPerType();
                    int fullParentPosition = parentItem != null ? parentItem.getFullPosition() : 0;
                    int parentPositionPerType = parentItem != null ?
                            parentItem.getPositionPerType() : 0;

                    if (view instanceof TextView) {
                        TextView tv = (TextView) view;
                        Object value = mCallback.getAdapterItemValue(
                                mAdapterRef, context.getViewKey(view),
                                item.getDataBindingItem().getViewReference(),
                                fullPosition, positionPerType,
                                fullParentPosition, parentPositionPerType,
                                resolvedRef, ViewAttribute.TEXT, tv.getText().toString());
                        if (value != null) {
                            if (value.getClass() != ViewAttribute.TEXT.getAttributeClass()) {
                                Bridge.getLog().error(LayoutLog.TAG_BROKEN, String.format(
                                        "Wrong Adapter Item value class for TEXT. Expected String, got %s",
                                        value.getClass().getName()), null);
                            } else {
                                tv.setText((String) value);
                            }
                        }
                    }

                    if (view instanceof Checkable) {
                        Checkable cb = (Checkable) view;

                        Object value = mCallback.getAdapterItemValue(
                                mAdapterRef, context.getViewKey(view),
                                item.getDataBindingItem().getViewReference(),
                                fullPosition, positionPerType,
                                fullParentPosition, parentPositionPerType,
                                resolvedRef, ViewAttribute.IS_CHECKED, cb.isChecked());
                        if (value != null) {
                            if (value.getClass() != ViewAttribute.IS_CHECKED.getAttributeClass()) {
                                Bridge.getLog().error(LayoutLog.TAG_BROKEN, String.format(
                                        "Wrong Adapter Item value class for TEXT. Expected Boolean, got %s",
                                        value.getClass().getName()), null);
                            } else {
                                cb.setChecked((Boolean) value);
                            }
                        }
                    }

                    if (view instanceof ImageView) {
                        ImageView iv = (ImageView) view;

                        Object value = mCallback.getAdapterItemValue(
                                mAdapterRef, context.getViewKey(view),
                                item.getDataBindingItem().getViewReference(),
                                fullPosition, positionPerType,
                                fullParentPosition, parentPositionPerType,
                                resolvedRef, ViewAttribute.SRC, iv.getDrawable());
                        if (value != null) {
                            if (value.getClass() != ViewAttribute.SRC.getAttributeClass()) {
                                Bridge.getLog().error(LayoutLog.TAG_BROKEN, String.format(
                                        "Wrong Adapter Item value class for TEXT. Expected Boolean, got %s",
                                        value.getClass().getName()), null);
                            } else {
                                // FIXME
                            }
                        }
                    }
                }
            }
        }
    }
}
