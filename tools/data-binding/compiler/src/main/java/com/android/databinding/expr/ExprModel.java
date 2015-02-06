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

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import com.android.databinding.ClassAnalyzer;
import com.android.databinding.util.L;
import com.android.databinding.writer.FlagSet;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ExprModel {

    Map<String, Expr> mExprMap = new HashMap<>();

    List<Expr> mBindingExpressions = new ArrayList<>();

    private int mInvalidateableFieldLimit = 0;

    private int mRequirementIdCount = 0;

    private static final String TRUE_KEY_SUFFIX = "== true";
    private static final String FALSE_KEY_SUFFIX = "== false";

    /**
     * Used by code generation. Keeps the list of expressions that are waiting to be evaluated.
     */
    private List<Expr> mPendingExpressions;

    /**
     * Used for converting flags into identifiers while debugging.
     */
    private String[] mFlagMapping;

    private BitSet mInvalidateableFlags;
    private BitSet mConditionalFlags;

    private int mFlagBucketCount;// how many buckets we use to identify flags

    private List<Expr> mObservables;

    /**
     * Adds the expression to the list of expressions and returns it.
     * If it already exists, returns existing one.
     *
     * @param expr The new parsed expression
     * @return The expression itself or another one if the same thing was parsed before
     */
    public <T extends Expr> T register(T expr) {
        T existing = (T) mExprMap.get(expr.getUniqueKey());
        if (existing != null) {
            Preconditions.checkState(expr.getParents().isEmpty(),
                    "If an expression already exists, it should've never been added to a parent,"
                            + "if thats the case, somewhere we are creating an expression w/o"
                            + "calling expression model");
            // tell the expr that it is being swapped so that if it was added to some other expr
            // as a parent, those can swap their references
            expr.onSwappedWith(existing);
            return existing;
        }
        mExprMap.put(expr.getUniqueKey(), expr);
        expr.setModel(this);
        return expr;
    }

    public Map<String, Expr> getExprMap() {
        return mExprMap;
    }

    public int size() {
        return mExprMap.size();
    }

    public ComparisonExpr comparison(String op, Expr left, Expr right) {
        return register(new ComparisonExpr(op, left, right));
    }

    public FieldAccessExpr field(Expr parent, String name) {
        return register(new FieldAccessExpr(parent, name));
    }

    public FieldAccessExpr observableField(Expr parent, String name) {
        return register(new FieldAccessExpr(parent, name, true));
    }

    public SymbolExpr symbol(String text, Class type) {
        return register(new SymbolExpr(text, type));
    }

    public TernaryExpr ternary(Expr pred, Expr ifTrue, Expr ifFalse) {
        return register(new TernaryExpr(pred, ifTrue, ifFalse));
    }

    public IdentifierExpr identifier(String name) {
        return register(new IdentifierExpr(name));
    }

    public StaticIdentifierExpr staticIdentifier(String name) {
        return register(new StaticIdentifierExpr(name));
    }

    public MethodCallExpr methodCall(Expr target, String name, List<Expr> args) {
        return register(new MethodCallExpr(target, name, args));
    }

    public MathExpr math(Expr left, String op, Expr right) {
        return register(new MathExpr(left, op, right));
    }

    public Expr group(Expr grouped) {
        return register(new GroupExpr(grouped));
    }

    public Expr resourceExpr(String resourceText) {
        return register(new ResourceExpr(resourceText));
    }

    public Expr bracketExpr(Expr variableExpr, Expr argExpr) {
        return register(new BracketExpr(variableExpr, argExpr));
    }

    public List<Expr> getBindingExpressions() {
        return mBindingExpressions;
    }

    /**
     * The actual thingy that is set on the binding target.
     *
     * Input must be already registered
     */
    public Expr bindingExpr(Expr bindingExpr) {
        Preconditions.checkArgument(mExprMap.containsKey(bindingExpr.getUniqueKey()),
                "Main expression should already be registered");
        mBindingExpressions.add(bindingExpr);
        return bindingExpr;
    }

    /**
     * Nodes to which no one depends
     */
    public Iterable<Expr> findRootNodes() {
        return Iterables.filter(mExprMap.values(), new Predicate<Expr>() {
            @Override
            public boolean apply(Expr input) {
                return input.getParents().isEmpty();
            }
        });
    }

    /**
     * Nodes, which do not depend on any other node
     */
    public Iterable<Expr> findLeafNodes() {
        return Iterables.filter(mExprMap.values(), new Predicate<Expr>() {
            @Override
            public boolean apply(Expr input) {
                return input.getChildren().isEmpty();
            }
        });
    }

    public List<Expr> getObservables() {
        return mObservables;
    }

    /**
     * Give id to each expression. Will be useful if we serialize.
     */
    public void seal() {
        List<Expr> notifiableExpressions = new ArrayList<>();
        //ensure class analyzer. We need to know observables at this point
        final ClassAnalyzer classAnalyzer = ClassAnalyzer.getInstance();

        ArrayList<Expr> processedExprs = new ArrayList<>();
        ArrayList<Expr> exprs = new ArrayList<>();
        do {
            exprs.clear();
            exprs.addAll(mExprMap.values());
            exprs.removeAll(processedExprs);
            for (Expr expr: exprs) {
                expr.updateExpr(classAnalyzer);
            }
            processedExprs.addAll(exprs);
        } while (!exprs.isEmpty());

        int counter = 0;
        final Iterable<Expr> observables = filterObservables(classAnalyzer);
        List<String> flagMapping = Lists.newArrayList();
        mObservables = Lists.newArrayList();
        for (Expr expr : observables) {
            // observables gets initial ids
            flagMapping.add(expr.getUniqueKey());
            expr.setId(counter++);
            mObservables.add(expr);
            notifiableExpressions.add(expr);
            L.d("observable %s", expr.getUniqueKey());
        }

        // non-observable identifiers gets next ids
        final Iterable<Expr> nonObservableIds = filterNonObservableIds(classAnalyzer);
        for (Expr expr : nonObservableIds) {
            flagMapping.add(expr.getUniqueKey());
            expr.setId(counter++);
            notifiableExpressions.add(expr);
            L.d("non-observable %s", expr.getUniqueKey());
        }

        // descendents of observables gets following ids
        for (Expr expr : observables) {
            for (Expr parent : expr.getParents()) {
                if (parent.hasId()) {
                    continue;// already has some id, means observable
                }
                // only fields earn an id
                if (parent instanceof FieldAccessExpr && parent.isDynamic() &&
                        !((FieldAccessExpr) parent).getName().isEmpty()) {
                    flagMapping.add(parent.getUniqueKey());
                    parent.setId(counter++);
                    notifiableExpressions.add(parent);
                    L.d("notifiable field %s : %s for %s : %s", parent.getUniqueKey(),
                            Integer.toHexString(System.identityHashCode(parent)),
                            expr.getUniqueKey(),
                            Integer.toHexString(System.identityHashCode(expr)));
                }
            }
        }

        // non-dynamic binding expressions receive some ids so that they can be invalidated
        for (Expr expr : mBindingExpressions) {
            if (!(expr.isDynamic() || !expr.hasId())) {
                L.d("Expr " + expr + " is dynamic? " + expr.isDynamic() + ", has ID? " + expr.hasId());
            }
            Preconditions.checkState(expr.isDynamic() || !expr.hasId());
            if (!expr.isDynamic()) {
                // give it an id for invalidateAll
                expr.setId(counter ++);
                notifiableExpressions.add(expr);
            }
        }

        for (Expr expr : notifiableExpressions) {
            expr.enableDirectInvalidation();
        }

        // make sure all dependencies are resolved to avoid future race conditions
        for (Expr expr : mExprMap.values()) {
            expr.getDependencies();
        }

        mInvalidateableFieldLimit = counter;
        mInvalidateableFlags = new BitSet();
        for (int i = 0; i < mInvalidateableFieldLimit; i++) {
            mInvalidateableFlags.set(i, true);
        }

        // make sure all dependencies are resolved to avoid future race conditions
        for (Expr expr : mExprMap.values()) {
            if (expr.isConditional()) {
                expr.setRequirementId(counter);
                flagMapping.add(expr.getUniqueKey() + FALSE_KEY_SUFFIX);
                flagMapping.add(expr.getUniqueKey() + TRUE_KEY_SUFFIX);
                counter += 2;
            }
        }
        mConditionalFlags = new BitSet();
        for (int i = mInvalidateableFieldLimit; i < counter; i++) {
            mConditionalFlags.set(i, true);
        }

        mRequirementIdCount = (counter - mInvalidateableFieldLimit) / 2;

        // everybody gets an id
        for (Map.Entry<String, Expr> entry : mExprMap.entrySet()) {
            final Expr value = entry.getValue();
            if (!value.hasId()) {
                value.setId(counter++);
            }
        }
        mFlagMapping = new String[flagMapping.size()];
        flagMapping.toArray(mFlagMapping);

        for (Expr expr : mExprMap.values()) {
            expr.getShouldReadFlagsWithConditionals();
        }

        for (Expr expr : mExprMap.values()) {
            // ensure all types are calculated
            expr.getResolvedType();
        }

        mFlagBucketCount = 1 + (getTotalFlagCount() / FlagSet.sBucketSize);
    }

    public int getFlagBucketCount() {
        return mFlagBucketCount;
    }

    public int getTotalFlagCount() {
        return mRequirementIdCount * 2 + mInvalidateableFieldLimit;
    }

    public int getInvalidateableFieldLimit() {
        return mInvalidateableFieldLimit;
    }

    public String[] getFlagMapping() {
        return mFlagMapping;
    }

    public String getFlag(int id) {
        return mFlagMapping[id];
    }

    private Iterable<Expr> filterNonObservableIds(final ClassAnalyzer classAnalyzer) {
        return Iterables.filter(mExprMap.values(), new Predicate<Expr>() {
            @Override
            public boolean apply(Expr input) {
                return input instanceof IdentifierExpr
                        && !input.hasId()
                        && !classAnalyzer.isObservable(input.getResolvedType())
                        && input.isDynamic();
            }
        });
    }

    private Iterable<Expr> filterObservables(final ClassAnalyzer classAnalyzer) {
        return Iterables.filter(mExprMap.values(), new Predicate<Expr>() {
            @Override
            public boolean apply(Expr input) {
                return classAnalyzer.isObservable(input.getResolvedType());
            }
        });
    }

    public List<Expr> getPendingExpressions() {
        if (mPendingExpressions == null) {
            mPendingExpressions = Lists.newArrayList();
            for (Expr expr : mExprMap.values()) {
                if (!expr.isRead() && expr.isDynamic()) {
                    mPendingExpressions.add(expr);
                }
            }
        }
        return mPendingExpressions;
    }

    public boolean markBitsRead() {
        L.d("marking bits as done");
        // each has should read flags, we set them back on them
        for (Expr expr : filterShouldRead(getPendingExpressions())) {
            expr.markFlagsAsRead(expr.getShouldReadFlags());
        }
        return pruneDone();
    }

    private boolean pruneDone() {
        boolean marked = true;
        List<Expr> markedAsReadList = Lists.newArrayList();
        while (marked) {
            marked = false;
            for (Expr expr : mExprMap.values()) {
                if (expr.isRead()) {
                    continue;
                }
                if (expr.markAsReadIfDone()) {
                    L.d("marked %s as read ", expr.getUniqueKey());
                    marked = true;
                    markedAsReadList.add(expr);
                }

            }
        }
        boolean elevated = false;
        for (Expr markedAsRead : markedAsReadList) {
            for (Dependency dependency : markedAsRead.getDependants()) {
                if (dependency.getDependant().considerElevatingConditionals(markedAsRead)) {
                    elevated = true;
                }
            }
        }
        if (elevated) {
            // some conditionals are elevated. We should re-calculate flags
            for (Expr expr : getPendingExpressions()) {
                if (!expr.isRead()) {
                    expr.invalidateReadFlags();
                }
            }
            mPendingExpressions = null;
        }
        return elevated;
    }

    public Iterable<Expr> filterShouldRead(Iterable<Expr> exprs) {
        return Iterables.filter(exprs, sShouldReadPred);
    }

    public Iterable<Expr> filterCanBeReadNow(Iterable<Expr> exprs) {
        return Iterables.filter(exprs, sReadNowPred);
    }

    private static final Predicate<Expr> sShouldReadPred = new Predicate<Expr>() {
        @Override
        public boolean apply(final Expr expr) {
            return !expr.getShouldReadFlags().isEmpty() && !Iterables.any(
                    expr.getDependencies(), new Predicate<Dependency>() {
                        @Override
                        public boolean apply(Dependency dependency) {
                            final boolean result = dependency.isConditional() ||
                                    dependency.getOther().hasNestedCannotRead();
                            return result;
                        }
                    });
        }
    };

    private static final  Predicate<Expr> sReadNowPred = new Predicate<Expr>() {
        @Override
        public boolean apply(Expr input) {
            return !input.getShouldReadFlags().isEmpty() &&
                    !Iterables.any(input.getDependencies(), new Predicate<Dependency>() {
                        @Override
                        public boolean apply(Dependency input) {
                            return !input.getOther().isRead();
                        }
                    });
        }
    };

    public Expr findFlagExpression(int flag) {
        final String key = mFlagMapping[flag];
        if (mExprMap.containsKey(key)) {
            return mExprMap.get(key);
        }
        int falseIndex = key.indexOf(FALSE_KEY_SUFFIX);
        if (falseIndex > -1) {
            final String trimmed = key.substring(0, falseIndex);
            return mExprMap.get(trimmed);
        }
        int trueIndex = key.indexOf(TRUE_KEY_SUFFIX);
        if (trueIndex > -1) {
            final String trimmed = key.substring(0, trueIndex);
            return mExprMap.get(trimmed);
        }
        Preconditions.checkArgument(false, "cannot find expression for flag %d", flag);
        return null;
    }
}
