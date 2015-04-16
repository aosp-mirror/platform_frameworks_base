/*
 * Copyright (C) 2013 The Android Open Source Project
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

import com.android.ide.common.rendering.api.DataBindingItem;
import com.android.ide.common.rendering.api.LayoutlibCallback;
import com.android.ide.common.rendering.api.LayoutLog;
import com.android.ide.common.rendering.api.ResourceReference;
import com.android.ide.common.rendering.api.IProjectCallback.ViewAttribute;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.impl.RenderAction;
import com.android.util.Pair;

import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Checkable;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * A Helper class to do fake data binding in {@link AdapterView} objects.
 */
@SuppressWarnings("deprecation")
public class AdapterHelper {

    static Pair<View, Boolean> getView(AdapterItem item, AdapterItem parentItem, ViewGroup parent,
            LayoutlibCallback callback, ResourceReference adapterRef, boolean skipCallbackParser) {
        // we don't care about recycling here because we never scroll.
        DataBindingItem dataBindingItem = item.getDataBindingItem();

        BridgeContext context = RenderAction.getCurrentContext();

        Pair<View, Boolean> pair = context.inflateView(dataBindingItem.getViewReference(),
                parent, false /*attachToRoot*/, skipCallbackParser);

        View view = pair.getFirst();
        skipCallbackParser |= pair.getSecond();

        if (view != null) {
            fillView(context, view, item, parentItem, callback, adapterRef);
        } else {
            // create a text view to display an error.
            TextView tv = new TextView(context);
            tv.setText("Unable to find layout: " + dataBindingItem.getViewReference().getName());
            view = tv;
        }

        return Pair.of(view, skipCallbackParser);
    }

    private static void fillView(BridgeContext context, View view, AdapterItem item,
            AdapterItem parentItem, LayoutlibCallback callback, ResourceReference adapterRef) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            final int count = group.getChildCount();
            for (int i = 0 ; i < count ; i++) {
                fillView(context, group.getChildAt(i), item, parentItem, callback, adapterRef);
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
                        Object value = callback.getAdapterItemValue(
                                adapterRef, context.getViewKey(view),
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

                        Object value = callback.getAdapterItemValue(
                                adapterRef, context.getViewKey(view),
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

                        Object value = callback.getAdapterItemValue(
                                adapterRef, context.getViewKey(view),
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
