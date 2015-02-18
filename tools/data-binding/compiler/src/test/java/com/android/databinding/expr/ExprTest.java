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

package com.android.databinding.expr;

import com.android.databinding.reflection.ModelAnalyzer;

import org.junit.Before;
import org.junit.Test;

import java.util.BitSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExprTest{
    private static class DummyExpr extends Expr {
        String mKey;
        public DummyExpr(String key, DummyExpr... children) {
            super(children);
            mKey = key;
        }

        @Override
        protected Class resolveType(ModelAnalyzer modelAnalyzer) {
            return Integer.class;
        }

        @Override
        protected List<Dependency> constructDependencies() {
            return constructDynamicChildrenDependencies();
        }

        @Override
        protected String computeUniqueKey() {
            return mKey + super.computeUniqueKey();
        }

        @Override
        public boolean isDynamic() {
            return true;
        }
    }

    @Before
    public void setUp() throws Exception {
        ModelAnalyzer.initForTests();
    }

    @Test(expected=IllegalStateException.class)
    public void testBadExpr() {
        Expr expr = new Expr() {
            @Override
            protected Class resolveType(ModelAnalyzer modelAnalyzer) {
                return Integer.class;
            }

            @Override
            protected List<Dependency> constructDependencies() {
                return constructDynamicChildrenDependencies();
            }
        };
        expr.getUniqueKey();
    }

    @Test
    public void testBasicInvalidationFlag() {
        DummyExpr d = new DummyExpr("a");
        d.setId(3);
        d.enableDirectInvalidation();
        assertTrue(d.getInvalidFlags().get(3));
    }

    @Test
    public void testCannotBeInvalidated() {
        DummyExpr d = new DummyExpr("a");
        d.setId(3);
        assertTrue(d.getInvalidFlags().isEmpty());
    }

    @Test
    public void testInvalidationInheritance() {
        ExprModel model = new ExprModel();
        DummyExpr a = model.register(new DummyExpr("a"));
        DummyExpr b = model.register(new DummyExpr("b"));
        DummyExpr c = model.register(new DummyExpr("c", a, b));
        a.enableDirectInvalidation();
        b.enableDirectInvalidation();
        c.setBindingExpression(true);
        model.seal();
        assertFlags(c, a, b);
    }

    @Test
    public void testInvalidationInheritance2() {
        ExprModel model = new ExprModel();
        DummyExpr a = model.register(new DummyExpr("a"));
        DummyExpr b = model.register(new DummyExpr("b", a));
        DummyExpr c = model.register(new DummyExpr("c", b));
        a.enableDirectInvalidation();
        b.enableDirectInvalidation();
        c.setBindingExpression(true);
        model.seal();
        assertFlags(c, a, b);
    }

    @Test
    public void testShouldReadFlags() {
        ExprModel model = new ExprModel();
        DummyExpr a = model.register(new DummyExpr("a"));
        a.enableDirectInvalidation();
        a.setBindingExpression(true);
        model.seal();
        assertFlags(a, a);
    }

    @Test
    public void testShouldReadDependencyFlags() {
        ExprModel model = new ExprModel();
        DummyExpr a = model.register(new DummyExpr("a"));
        DummyExpr b = model.register(new DummyExpr("b", a));
        DummyExpr c = model.register(new DummyExpr("c", b));
        a.enableDirectInvalidation();
        b.enableDirectInvalidation();
        b.setBindingExpression(true);
        c.setBindingExpression(true);
        model.seal();
        assertFlags(b, a, b);
        assertFlags(c, a, b);
    }

    private void assertFlags(Expr a, Expr... exprs) {
        BitSet bitSet = a.getShouldReadFlags();
        for (Expr expr : exprs) {
            BitSet clone = (BitSet) bitSet.clone();
            clone.and(expr.getInvalidFlags());
            assertEquals("should read flags of " + a.getUniqueKey() + " should include " + expr
                    .getUniqueKey(), expr.getInvalidFlags(), clone);
        }

        BitSet composite = new BitSet();
        for (Expr expr : exprs) {
            composite.or(expr.getInvalidFlags());
        }
        assertEquals("composite flags should match", composite, bitSet);
    }
}
