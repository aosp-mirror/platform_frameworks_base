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

package com.android.statementservice.retriever;

import android.annotation.NonNull;
import android.net.Network;

import com.android.statementservice.network.retriever.StatementRetriever;

import kotlin.coroutines.Continuation;

/**
 * An immutable value type representing a statement, consisting of a source, target, and relation.
 * This reflects an assertion that the relation holds for the source, target pair. For example, if a
 * web site has the following in its assetlinks.json file:
 *
 * <pre>
 * {
 * "relation": ["delegate_permission/common.handle_all_urls"],
 * "target"  : {"namespace": "android_app", "package_name": "com.example.app",
 *              "sha256_cert_fingerprints": ["00:11:22:33"] }
 * }
 * </pre>
 *
 * Then invoking {@link StatementRetriever#retrieve(AbstractAsset, Network, Continuation)} will
 * return a {@link Statement} with {@link #getSource} equal to the input parameter,
 * {@link #getRelation} equal to
 *
 * <pre>Relation.create("delegate_permission", "common.get_login_creds");</pre>
 *
 * and with {@link #getTarget} equal to
 *
 * <pre>AbstractAsset.create("{\"namespace\" : \"android_app\","
 *                           + "\"package_name\": \"com.example.app\"}"
 *                           + "\"sha256_cert_fingerprints\": \"[\"00:11:22:33\"]\"}");
 * </pre>
 */
public final class Statement {

    private final AbstractAsset mTarget;
    private final Relation mRelation;
    private final AbstractAsset mSource;

    private Statement(AbstractAsset source, AbstractAsset target, Relation relation) {
        mSource = source;
        mTarget = target;
        mRelation = relation;
    }

    /**
     * Returns the source asset of the statement.
     */
    @NonNull
    public AbstractAsset getSource() {
        return mSource;
    }

    /**
     * Returns the target asset of the statement.
     */
    @NonNull
    public AbstractAsset getTarget() {
        return mTarget;
    }

    /**
     * Returns the relation of the statement.
     */
    @NonNull
    public Relation getRelation() {
        return mRelation;
    }

    /**
     * Creates a new Statement object for the specified target asset and relation. For example:
     * <pre>
     *   Asset asset = Asset.Factory.create(
     *       "{\"namespace\" : \"web\",\"site\": \"https://www.test.com\"}");
     *   Relation relation = Relation.create("delegate_permission", "common.get_login_creds");
     *   Statement statement = Statement.create(asset, relation);
     * </pre>
     */
    public static Statement create(@NonNull AbstractAsset source, @NonNull AbstractAsset target,
                                   @NonNull Relation relation) {
        return new Statement(source, target, relation);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        Statement statement = (Statement) o;

        if (!mRelation.equals(statement.mRelation)) {
            return false;
        }
        if (!mTarget.equals(statement.mTarget)) {
            return false;
        }
        if (!mSource.equals(statement.mSource)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = mTarget.hashCode();
        result = 31 * result + mRelation.hashCode();
        result = 31 * result + mSource.hashCode();
        return result;
    }

    @Override
    public String toString() {
        StringBuilder statement = new StringBuilder();
        statement.append("Statement: ");
        statement.append(mSource);
        statement.append(", ");
        statement.append(mTarget);
        statement.append(", ");
        statement.append(mRelation);
        return statement.toString();
    }
}
