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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import com.android.databinding.reflection.ReflectionAnalyzer;
import com.android.databinding.reflection.ReflectionClass;

abstract public class Expr {

    public static final int NO_ID = -1;
    protected List<Expr> mChildren = new ArrayList<>();

    // any expression that refers to this. Useful if this expr is duplicate and being replaced
    private List<Expr> mParents = new ArrayList<>();

    private Boolean mIsDynamic;

    private ReflectionClass mResolvedType;

    private String mUniqueKey;

    private List<Dependency> mDependencies;

    private List<Dependency> mDependants = Lists.newArrayList();

    private int mId = NO_ID;

    private int mRequirementId = NO_ID;

    // means this expression can directly be invalidated by the user
    private boolean mCanBeInvalidated = false;

    /**
     * This set denotes the times when this expression is invalid.
     * If it is an Identifier expression, it is its index
     * If it is a composite expression, it is the union of invalid flags of its descendants
     */
    private BitSet mInvalidFlags;

    /**
     * Set when this expression is registered to a model
     */
    private ExprModel mModel;

    /**
     * This set denotes the times when this expression must be read.
     *
     * It is the union of invalidation flags of all of its non-conditional dependants.
     */
    BitSet mShouldReadFlags;

    BitSet mReadSoFar = new BitSet();// i've read this variable for these flags

    /**
     * calculated on initialization, assuming all conditionals are true
     */
    BitSet mShouldReadWithConditionals;

    private boolean mIsBindingExpression;

    /**
     * Used by generators when this expression is resolved.
     */
    private boolean mRead;
    private boolean mIsUsed = false;

    Expr(Iterable<Expr> children) {
        for (Expr expr : children) {
            mChildren.add(expr);
        }
        addParents();
    }

    Expr(Expr... children) {
        Collections.addAll(mChildren, children);
        addParents();
    }

    public int getId() {
        Preconditions.checkState(mId != NO_ID, "if getId is called on an expression, it should have"
                + " and id");
        return mId;
    }

    public void setId(int id) {
        Preconditions.checkState(mId == NO_ID, "ID is already set on " + this);
        mId = id;
    }

    public ExprModel getModel() {
        return mModel;
    }

    public BitSet getInvalidFlags() {
        if (mInvalidFlags == null) {
            mInvalidFlags = resolveInvalidFlags();
        }
        return mInvalidFlags;
    }

    private BitSet resolveInvalidFlags() {
        BitSet bitSet = new BitSet();
        if (mCanBeInvalidated) {
            bitSet.set(getId(), true);
        }
        for (Dependency dependency : getDependencies()) {
            // TODO optional optimization: do not invalidate for conditional flags
            bitSet.or(dependency.getOther().getInvalidFlags());
        }
        return bitSet;
    }

    public void setBindingExpression(boolean isBindingExpression) {
        mIsBindingExpression = isBindingExpression;
    }

    public boolean isBindingExpression() {
        return mIsBindingExpression;
    }

    public boolean isObservable() {
        return ReflectionAnalyzer.getInstance().isObservable(getResolvedType());
    }

    public BitSet getShouldReadFlags() {
        if (mShouldReadFlags == null) {
            getShouldReadFlagsWithConditionals();
            mShouldReadFlags = resolveShouldReadFlags();
        }
        return mShouldReadFlags;
    }

    public BitSet getShouldReadFlagsWithConditionals() {
        if (mShouldReadWithConditionals == null) {
            mShouldReadWithConditionals = resolveShouldReadWithConditionals();
        }
        return mShouldReadWithConditionals;
    }

    public void setModel(ExprModel model) {
        mModel = model;
    }

    private BitSet resolveShouldReadWithConditionals() {
        // ensure we have invalid flags
        BitSet bitSet = new BitSet();
        // if i'm invalid, that DOES NOT mean i should be read :/.
        if (mIsBindingExpression) {
            bitSet.or(getInvalidFlags());
        }

        for (Dependency dependency : getDependants()) {
            // first traverse non-conditionals because we'll avoid adding conditionals if we are get because of these anyways
            if (dependency.getCondition() == null) {
                bitSet.or(dependency.getDependant().getShouldReadFlagsWithConditionals());
            } else {
                bitSet.set(dependency.getDependant()
                        .getRequirementFlagIndex(dependency.getExpectedOutput()));
            }
        }
        return bitSet;
    }

    private BitSet resolveShouldReadFlags() {
        // ensure we have invalid flags
        BitSet bitSet = new BitSet();
        if (isRead()) {
            return bitSet;
        }
        if (mIsBindingExpression) {
            bitSet.or(getInvalidFlags());
        }
        for (Dependency dependency : getDependants()) {
            final boolean isElevated = unreadElevatedCheck.apply(dependency);
            if (dependency.isConditional()) {
                continue; // TODO
            }
            if (isElevated) {
                // if i already have all flags that will require my dependant's predicate to
                // be read, that means i'm already read thus can avoid adding its conditional
                // dependency
                if (!dependency.getDependant().getAllCalculationPaths().areAllPathsSatisfied(
                        mReadSoFar)) {
                    bitSet.set(dependency.getDependant()
                            .getRequirementFlagIndex(dependency.getExpectedOutput()));
                }
            } else {
                bitSet.or(dependency.getDependant().getShouldReadFlags());
            }
        }
        bitSet.andNot(mReadSoFar);
        // should read w/ conditionals does eleminate for unnecessary re-reads
        bitSet.and(mShouldReadWithConditionals);
        return bitSet;
    }

    Predicate<Dependency> unreadElevatedCheck = new Predicate<Dependency>() {
        @Override
        public boolean apply(Dependency input) {
            return input.isElevated() && !input.getDependant().isRead();
        }
    };

    private void addParents() {
        for (Expr expr : mChildren) {
            expr.mParents.add(this);
        }
    }

    public void onSwappedWith(Expr existing) {
        for (Expr child : mChildren) {
            child.onParentSwapped(this, existing);
        }
    }

    private void onParentSwapped(Expr oldParent, Expr newParent) {
        Preconditions.checkState(mParents.remove(oldParent));
        mParents.add(newParent);
    }

    public List<Expr> getChildren() {
        return mChildren;
    }

    public List<Expr> getParents() {
        return mParents;
    }

    /**
     * Whether the result of this expression can change or not.
     *
     * For example, 3 + 5 can not change vs 3 + x may change.
     *
     * Default implementations checks children and returns true if any of them returns true
     *
     * @return True if the result of this expression may change due to variables
     */
    public boolean isDynamic() {
        if (mIsDynamic == null) {
            mIsDynamic = isAnyChildDynamic();
        }
        return mIsDynamic;
    }

    private boolean isAnyChildDynamic() {
        return Iterables.any(mChildren, new Predicate<Expr>() {
            @Override
            public boolean apply(Expr input) {
                return input.isDynamic();
            }
        });

    }

    public ReflectionClass getResolvedType() {
        if (mResolvedType == null) {
            // TODO not get instance
            mResolvedType = resolveType(ReflectionAnalyzer.getInstance());
        }
        return mResolvedType;
    }

    abstract protected ReflectionClass resolveType(ReflectionAnalyzer reflectionAnalyzer);

    abstract protected List<Dependency> constructDependencies();

    /**
     * Creates a dependency for each dynamic child. Should work for any expression besides
     * conditionals.
     */
    protected List<Dependency> constructDynamicChildrenDependencies() {
        List<Dependency> dependencies = new ArrayList<>();
        for (Expr node : mChildren) {
            if (!node.isDynamic()) {
                continue;
            }
            dependencies.add(new Dependency(this, node));
        }
        return dependencies;
    }

    public final List<Dependency> getDependencies() {
        if (mDependencies == null) {
            mDependencies = constructDependencies();
        }
        return mDependencies;
    }

    void addDependant(Dependency dependency) {
        mDependants.add(dependency);
    }

    public List<Dependency> getDependants() {
        return mDependants;
    }

    protected static final String KEY_JOIN = "~";
    protected static final Joiner sUniqueKeyJoiner = Joiner.on(KEY_JOIN);

    /**
     * Returns a unique string key that can identify this expression.
     *
     * It must take into account any dependencies
     *
     * @return A unique identifier for this expression
     */
    public final String getUniqueKey() {
        if (mUniqueKey == null) {
            mUniqueKey = computeUniqueKey();
            Preconditions.checkNotNull(mUniqueKey,
                    "if there are no children, you must override computeUniqueKey");
            Preconditions.checkState(!mUniqueKey.trim().equals(""),
                    "if there are no children, you must override computeUniqueKey");
        }
        return mUniqueKey;
    }

    protected String computeUniqueKey() {
        return computeChildrenKey();
    }

    protected final String computeChildrenKey() {
        return sUniqueKeyJoiner.join(Iterables.transform(mChildren, new Function<Expr, String>() {
            @Override
            public String apply(Expr input) {
                return input.getUniqueKey();
            }
        }));
    }

    public void enableDirectInvalidation() {
        mCanBeInvalidated = true;
    }

    public boolean canBeInvalidated() {
        return mCanBeInvalidated;
    }

    public void trimShouldReadFlags(BitSet bitSet) {
        mShouldReadFlags.andNot(bitSet);
    }

    public boolean isConditional() {
        return false;
    }

    public int getRequirementId() {
        return mRequirementId;
    }

    public void setRequirementId(int requirementId) {
        mRequirementId = requirementId;
    }

    /**
     * This is called w/ a dependency of mine.
     * Base method should thr
     */
    public int getRequirementFlagIndex(boolean expectedOutput) {
        Preconditions.checkState(mRequirementId != NO_ID, "If this is an expression w/ conditional"
                + " dependencies, it must be assigned a requirement ID");
        return expectedOutput ? mRequirementId + 1 : mRequirementId;
    }

    public boolean hasId() {
        return mId != NO_ID;
    }

    public void markFlagsAsRead(BitSet flags) {
        mReadSoFar.or(flags);
    }

    public boolean isRead() {
        return mRead;
    }

    public boolean considerElevatingConditionals(Expr cond) {
        boolean elevated = false;
        for (Dependency dependency : mDependencies) {
            if (dependency.isConditional() && dependency.getCondition() == cond) {
                dependency.elevate();
                // silent elevate because it is not necessary anymore (already calculated)
                // but need to mark dependencies elevated so that we can decide to calculate
                // this expression when all dependencies are elevated
                if (!dependency.getOther().isRead()) {
                    elevated = true;
                }
            }
        }
        return elevated;
    }

    public void invalidateReadFlags() {
        mShouldReadFlags = null;
    }

    public boolean hasNestedCannotRead() {
        if (isRead()) {
            return false;
        }
        if (getShouldReadFlags().isEmpty()) {
            return true;
        }
        return Iterables.any(getDependencies(), hasNestedCannotRead);
    }

    Predicate<Dependency> hasNestedCannotRead = new Predicate<Dependency>() {
        @Override
        public boolean apply(Dependency input) {
            return input.getOther().hasNestedCannotRead();
        }
    };

    public boolean markAsReadIfDone() {
        if (mRead) {
            return false;
        }
        // TODO avoid clone, we can calculate this iteratively
        BitSet clone = (BitSet) mShouldReadWithConditionals.clone();

        clone.andNot(mReadSoFar);
        mRead = clone.isEmpty();
        if (!mRead && !mReadSoFar.isEmpty()) {
            // check if remaining dependencies can be satisfied w/ existing values
            // for predicate flags, this expr may already be calculated to get the predicate
            // to detect them, traverse them later on, see which flags should be calculated to calculate
            // them. If any of them is completely covered w/ our non-conditional flags, no reason
            // to add them to the list since we'll already be calculated due to our non-conditional
            // flags

            for (int i = clone.nextSetBit(0); i != -1; i = clone.nextSetBit(i + 1)) {
                final Expr expr = mModel.findFlagExpression(i);
                if (!expr.isConditional()) {
                    continue;
                }
                final BitSet readForConditional = expr.findConditionalFlags();
                // to calculate that conditional, i should've read /readForConditional/ flags
                // if my read-so-far bits has any common w/ that; that means i would've already
                // read myself
                clone.andNot(readForConditional);
                final BitSet invalidFlags = (BitSet) getInvalidFlags().clone();
                invalidFlags.andNot(readForConditional);
                mRead = invalidFlags.isEmpty() || clone.isEmpty();
            }

        }
        return mRead;
    }

    BitSet mConditionalFlags;

    private BitSet findConditionalFlags() {
        Preconditions.checkState(isConditional(), "should not call this on a non-conditional expr");
        if (mConditionalFlags == null) {
            mConditionalFlags = new BitSet();
            resolveConditionalFlags(mConditionalFlags);
        }
        return mConditionalFlags;
    }

    private void resolveConditionalFlags(BitSet flags) {
        flags.or(getPredicateInvalidFlags());
        // if i have only 1 dependency which is conditional, traverse it as well
        if (getDependants().size() == 1) {
            final Dependency dependency = getDependants().get(0);
            if (dependency.getCondition() != null) {
                flags.or(dependency.getDependant().findConditionalFlags());
                flags.set(dependency.getDependant()
                        .getRequirementFlagIndex(dependency.getExpectedOutput()));
            }
        }
    }


    @Override
    public String toString() {
        return getUniqueKey();
    }

    public BitSet getReadSoFar() {
        return mReadSoFar;
    }

    private Node mCalculationPaths = null;

    protected Node getAllCalculationPaths() {
        if (mCalculationPaths == null) {
            Node node = new Node();
            // TODO distant parent w/ conditionals are still not traversed :/
            if (isConditional()) {
                node.mBitSet.or(getPredicateInvalidFlags());
            } else {
                node.mBitSet.or(getInvalidFlags());
            }
            for (Dependency dependency : getDependants()) {
                final Expr dependant = dependency.getDependant();
                if (dependency.getCondition() != null) {
                    Node cond = new Node();
                    cond.setConditionFlag(
                            dependant.getRequirementFlagIndex(dependency.getExpectedOutput()));
                    cond.mParents.add(dependant.getAllCalculationPaths());
                } else {
                    node.mParents.add(dependant.getAllCalculationPaths());
                }
            }
            mCalculationPaths = node;
        }
        return mCalculationPaths;
    }

    public String getDefaultValue() {
        return ReflectionAnalyzer.getInstance().getDefaultValue(getResolvedType().toJavaCode());
    }

    protected BitSet getPredicateInvalidFlags() {
        throw new IllegalStateException(
                "must override getPredicateInvalidFlags in " + getClass().getSimpleName());
    }

    /**
     * Used by code generation
     */
    public boolean shouldReadNow(final Iterable<Expr> justRead) {
        return !getShouldReadFlags().isEmpty() &&
                !Iterables.any(getDependencies(), new Predicate<Dependency>() {
                    @Override
                    public boolean apply(Dependency input) {
                        return !(input.getOther().isRead() || (justRead != null && Iterables
                                .contains(justRead, input.getOther())));
                    }
                });
    }

    public boolean isEqualityCheck() {
        return false;
    }

    public void setIsUsed(boolean isUsed) {
        mIsUsed = isUsed;
    }

    public boolean isUsed() {
        return mIsUsed;
    }

    public void updateExpr(ReflectionAnalyzer reflectionAnalyzer) {
    }

    static class Node {

        BitSet mBitSet = new BitSet();
        List<Node> mParents = new ArrayList<>();
        int mConditionFlag = -1;

        public boolean areAllPathsSatisfied(BitSet readSoFar) {
            if (mConditionFlag != -1) {
                return readSoFar.get(mConditionFlag) || mParents.get(0)
                        .areAllPathsSatisfied(readSoFar);
            } else {
                final BitSet clone = (BitSet) readSoFar.clone();
                readSoFar.and(mBitSet);
                if (!readSoFar.isEmpty()) {
                    return true;
                }
                if (mParents.isEmpty()) {
                    return false;
                }
                for (Node parent : mParents) {
                    if (!parent.areAllPathsSatisfied(readSoFar)) {
                        return false;
                    }
                }
                return true;
            }
        }

        public void setConditionFlag(int requirementFlagIndex) {
            mConditionFlag = requirementFlagIndex;
        }
    }
}
