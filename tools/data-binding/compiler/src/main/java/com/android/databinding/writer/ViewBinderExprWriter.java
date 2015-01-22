/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.databinding.writer;

import com.android.databinding.ClassAnalyzer;
import com.android.databinding.LayoutBinder;
import com.android.databinding.expr.Expr;

public class ViewBinderExprWriter {

    LayoutBinder mLayoutBinder;

    public ViewBinderExprWriter(LayoutBinder layoutBinder) {
        mLayoutBinder = layoutBinder;
    }

    public String write() {
        StringBuilder sb = new StringBuilder();
        sb.append("DATA TREE");
        sb.append("\n notifiable expressions:");
        for (Expr node : mLayoutBinder.getModel().getNotifiableExpressions()) {
            sb.append("\n");
            append(node, sb);
        }
        sb.append("\n leaf expressions:");
        for (Expr node : mLayoutBinder.getModel().findLeafNodes()) {
            sb.append("\n");
            append(node, sb);
        }
        sb.append("\n root expressions:");
        for (Expr node : mLayoutBinder.getModel().findRootNodes()) {
            sb.append("\n");
            append(node, sb);
        }
        sb.append("\n tree:");
        for (Expr node : mLayoutBinder.getModel().findRootNodes()) {
            sb.append("\n");
            appendRecursive(0, sb, node);
        }
        sb.append("\nDATA TREE END");
        return sb.toString();
    }

    private void append(Expr node, StringBuilder sb) {
        sb.append("[").append(node.getId()).append(" ]").append(node.getUniqueKey()).append(" : ")
                .append(node.getResolvedType().getCanonicalName() + " : " + node.getClass()
                        .getSimpleName());
        if (ClassAnalyzer.getInstance().isObservable(node.getResolvedType())) {
            sb.append("[observable]");
        }
    }

    private void tabs(int i, StringBuilder sb) {
        i = i * 4;
        while (--i > 0) {
            sb.append("-");
        }
        sb.append(">");
    }

    private void appendRecursive(int tab, StringBuilder sb, Expr node) {
        tabs(tab, sb);
        append(node, sb);
        for (Expr child : node.getChildren()) {
            sb.append("\n");
            appendRecursive(tab + 1, sb, child);
        }
    }
}
