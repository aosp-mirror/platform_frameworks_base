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

package com.android.databinding2;

import com.android.databinding.ClassAnalyzer;
import com.android.databinding.ExpressionParser;
import com.android.databinding.expr.ComparisonExpr;
import com.android.databinding.expr.Dependency;
import com.android.databinding.expr.Expr;
import com.android.databinding.expr.ExprModel;
import com.android.databinding.expr.FieldAccessExpr;
import com.android.databinding.expr.IdentifierExpr;
import com.android.databinding.expr.MethodCallExpr;
import com.android.databinding.expr.SymbolExpr;
import com.android.databinding.expr.TernaryExpr;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ExpressionVisitorTest {
    ExpressionParser mParser = new ExpressionParser(new ExprModel());

    @Before
    public void setUp() throws Exception {
        ClassAnalyzer.initForTests();
    }

    private <T extends Expr> T parse(String input, Class<T> klass) {
        final Expr parsed = mParser.parse(input);
        assertSame(klass, parsed.getClass());
        return (T) parsed;
    }

    @Test
    public void testSymbol() {
        final SymbolExpr res = parse("null", SymbolExpr.class);
        assertEquals(1, mParser.getModel().size());
        assertEquals("null", res.getText());
        assertSame(res.getResolvedType(), Object.class);
        assertEquals(0, res.getDependencies().size());
    }


    @RunWith(Parameterized.class)
    public static class ComparisonExprTests {
        ExpressionParser mParser = new ExpressionParser(new ExprModel());
        private final String mOp;

        @Before
        public void setUp() throws Exception {
            ClassAnalyzer.initForTests();
        }

        @Parameterized.Parameters
        public static List<Object[]> data() {
            return Arrays.asList(new Object[][] {
                    {"=="}, {"<="}, {">="}, {">"}, {"<"}
            });
        }

        public ComparisonExprTests(String op) {
            mOp = op;
        }

        @Test
        public void testComparison() {
            final Expr res = mParser.parse("3 " + mOp + " 5");
            assertEquals(3, mParser.getModel().size());
            assertTrue(res instanceof ComparisonExpr);
            // 0 because they are both static
            assertEquals(0, res.getDependencies().size());
        }
    }



    @Test
    public void testSimpleFieldAccess() {
        final FieldAccessExpr expr = parse("a.b", FieldAccessExpr.class);
        assertEquals(2, mParser.mModel.size());
        assertEquals("b", expr.getName());
        assertEquals(1, expr.getChildren().size());
        final Expr parent = expr.getChildren().get(0);
        assertTrue(parent instanceof IdentifierExpr);
        final IdentifierExpr id = (IdentifierExpr) parent;
        assertEquals("a", id.getName());
        assertEquals(0, id.getDependencies().size());
        assertEquals(1, expr.getDependencies().size());
    }

    @Test
    public void testIdentifier() {
        final IdentifierExpr id = parse("myStr", IdentifierExpr.class);
        assertEquals(1, mParser.mModel.size());
        assertEquals("myStr", id.getName());
        id.setUserDefinedType("java.lang.String");
        assertSame(String.class, id.getResolvedType());
    }

    @Test
    public void testTernary() {
        final TernaryExpr parsed = parse("a > b ? 5 : 4", TernaryExpr.class);
        assertEquals(6, mParser.getModel().size());
        assertTrue(parsed.getPred() instanceof ComparisonExpr);
        assertTrue(parsed.getIfTrue() instanceof SymbolExpr);
        assertTrue(parsed.getIfFalse() instanceof SymbolExpr);
        ComparisonExpr pred = (ComparisonExpr) parsed.getPred();
        SymbolExpr ifTrue = (SymbolExpr) parsed.getIfTrue();
        SymbolExpr ifFalse = (SymbolExpr) parsed.getIfFalse();
        assertEquals("5", ifTrue.getText());
        assertEquals("4", ifFalse.getText());
        assertEquals(1, parsed.getDependencies().size());
        for (Dependency dependency : parsed.getDependencies()) {
            assertEquals(dependency.getOther() != pred, dependency.isConditional());
        }
    }

    @Test
    public void testInheritedFieldResolution() {
        final FieldAccessExpr parsed = parse("myStr.length", FieldAccessExpr.class);
        assertTrue(parsed.getParent() instanceof IdentifierExpr);
        final IdentifierExpr id = (IdentifierExpr) parsed.getParent();
        id.setUserDefinedType("java.lang.String");
        assertSame(int.class, parsed.getResolvedType());
        ClassAnalyzer.Callable getter = parsed.getGetter();
        assertEquals(ClassAnalyzer.Callable.Type.METHOD, getter.type);
        assertEquals("length", getter.name);
        assertEquals(1, parsed.getDependencies().size());
        final Dependency dep = parsed.getDependencies().get(0);
        assertSame(id, dep.getOther());
        assertFalse(dep.isConditional());
    }

    @Test
    public void testGetterResolution() {
        final FieldAccessExpr parsed = parse("myStr.bytes", FieldAccessExpr.class);
        assertTrue(parsed.getParent() instanceof IdentifierExpr);
        final IdentifierExpr id = (IdentifierExpr) parsed.getParent();
        id.setUserDefinedType("java.lang.String");
        assertSame(byte[].class, parsed.getResolvedType());
        ClassAnalyzer.Callable getter = parsed.getGetter();
        assertEquals(ClassAnalyzer.Callable.Type.METHOD, getter.type);
        assertEquals("getBytes", getter.name);
        assertEquals(1, parsed.getDependencies().size());
        final Dependency dep = parsed.getDependencies().get(0);
        assertSame(id, dep.getOther());
        assertFalse(dep.isConditional());
    }

    @Test
    public void testMethodCall() {
        final MethodCallExpr parsed = parse("user.getName()", MethodCallExpr.class);
        assertTrue(parsed.getTarget() instanceof IdentifierExpr);
        assertEquals("getName", parsed.getName());
        assertEquals(0, parsed.getArgs().size());
        assertEquals(1, parsed.getDependencies().size());
        final Dependency dep = parsed.getDependencies().get(0);
        assertSame(mParser.parse("user"), dep.getOther());
        assertFalse(dep.isConditional());
    }

    @Test
    public void testMethodCallWithArgs() {
        final MethodCallExpr parsed = parse("str.substring(1, a)", MethodCallExpr.class);
        assertTrue(parsed.getTarget() instanceof IdentifierExpr);
        assertEquals("substring", parsed.getName());
        final List<Expr> args = parsed.getArgs();
        assertEquals(2, args.size());
        assertTrue(args.get(0) instanceof SymbolExpr);
        assertTrue(args.get(1) instanceof IdentifierExpr);
        final List<Dependency> deps = parsed.getDependencies();
        assertEquals(2, deps.size());
    }

}
