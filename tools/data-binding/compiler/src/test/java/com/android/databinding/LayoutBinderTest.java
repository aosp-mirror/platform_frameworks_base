/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.databinding;


import com.android.databinding.ClassAnalyzer;
import com.android.databinding.LayoutBinder;
import com.android.databinding.expr.Expr;
import com.android.databinding.expr.ExprModel;
import com.android.databinding.expr.FieldAccessExpr;
import com.android.databinding.expr.IdentifierExpr;
import com.android.databinding.expr.StaticIdentifierExpr;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class LayoutBinderTest {
    LayoutBinder mLayoutBinder;
    ExprModel mExprModel;
    @Before
    public void setUp() throws Exception {
        ClassAnalyzer.initForTests();
        mLayoutBinder = new LayoutBinder(null);
        mExprModel = mLayoutBinder.getModel();
    }

    @Test
    public void testRegisterId() {
        mLayoutBinder.addVariable("test", "java.lang.String");
        assertEquals(1, mExprModel.size());
        final Map.Entry<String, Expr> entry = mExprModel.getExprMap().entrySet().iterator().next();
        final Expr value = entry.getValue();
        assertEquals(value.getClass(), IdentifierExpr.class);
        final IdentifierExpr id = (IdentifierExpr) value;
        assertEquals("test", id.getName());
        assertEquals(String.class, id.getResolvedType());
        assertTrue(id.isDynamic());
    }

    @Test
    public void testRegisterImport() {
        mLayoutBinder.addImport("test", "java.lang.String");
        assertEquals(1, mExprModel.size());
        final Map.Entry<String, Expr> entry = mExprModel.getExprMap().entrySet().iterator().next();
        final Expr value = entry.getValue();
        assertEquals(value.getClass(), StaticIdentifierExpr.class);
        final IdentifierExpr id = (IdentifierExpr) value;
        assertEquals("test", id.getName());
        assertEquals(String.class, id.getResolvedType());
        assertFalse(id.isDynamic());
    }

    @Test
    public void testParse() {
        mLayoutBinder.addVariable("user", "com.android.databinding2.LayoutBinderTest.TestUser");
        mLayoutBinder.parse("user.name");
        mLayoutBinder.parse("user.lastName");
        assertEquals(3, mExprModel.size());
        final List<Expr> bindingExprs = mExprModel.getBindingExpressions();
        assertEquals(2, bindingExprs.size());
        IdentifierExpr id = mExprModel.identifier("user");
        assertTrue(bindingExprs.get(0) instanceof FieldAccessExpr);
        assertTrue(bindingExprs.get(1) instanceof FieldAccessExpr);
        assertEquals(2, id.getParents().size());
        assertTrue(bindingExprs.get(0).getChildren().contains(id));
        assertTrue(bindingExprs.get(1).getChildren().contains(id));
    }

    @Test
    public void testParseWithMethods() {
        mLayoutBinder.addVariable("user", "com.android.databinding.LayoutBinderTest.TestUser");
        mLayoutBinder.parse("user.fullName");
        Expr item = mExprModel.getBindingExpressions().get(0);
        assertTrue(item instanceof FieldAccessExpr);
        IdentifierExpr id = mExprModel.identifier("user");
        FieldAccessExpr fa = (FieldAccessExpr) item;
        fa.getResolvedType();
        final ClassAnalyzer.Callable getter = fa.getGetter();
        assertTrue(getter.type == ClassAnalyzer.Callable.Type.METHOD);
        assertSame(id, fa.getParent());
        assertTrue(fa.isDynamic());
    }

    static class TestUser {
        public String name;
        public String lastName;

        public String fullName() {
            return name + " " + lastName;
        }
    }
}
