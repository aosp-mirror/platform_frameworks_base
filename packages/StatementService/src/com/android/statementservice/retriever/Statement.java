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

import java.util.Collections;
import java.util.List;


/**
 * An immutable value type representing a statement, consisting of a source, target, and relation.
 * This reflects an assertion that the relation holds for the source, target pair. For example, if a
 * web site has the following in its assetlinks.json file:
 *
 * <pre>
 * {
 * "relation": ["delegate_permission/common.handle_all_urls"],
 * "target"  : {"namespace": "android_app", "package_name": "com.example.app",
 *              "sha256_cert_fingerprints": ["00:11:22:33"] },
 * "relation_extensions": {
 *     "delegate_permission/common_handle_all_urls": {
 *         "dynamic_app_link_components": [
 *             {
 *                 "/": "/foo*",
 *                 "exclude": true,
 *                 "comments": "App should not handle paths that start with foo"
 *             },
 *             {
 *                 "/": "*",
 *                 "comments": "Catch all other paths"
 *             }
 *         ]
 *     }
 * }
 * </pre>
 *
 * Then invoking {@link StatementRetriever#retrieve(AbstractAsset, Network, Continuation)} will
 * return a {@link Statement} with {@link #getSource} equal to the input parameter,
 * {@link #getRelation} equal to
 *
 * <pre>Relation.create("delegate_permission", "common.handle_all_urls");</pre>
 *
 * and with {@link #getTarget} equal to
 *
 * <pre>AbstractAsset.create("{\"namespace\" : \"android_app\","
 *                           + "\"package_name\": \"com.example.app\"}"
 *                           + "\"sha256_cert_fingerprints\": \"[\"00:11:22:33\"]\"}");
 * </pre>
 *
 * If extensions exist for the handle_all_urls relation then {@link #getDynamicAppLinkComponents}
 * will return a list of parsed {@link DynamicAppLinkComponent}s.
 */
public final class Statement {

    private final AbstractAsset mTarget;
    private final Relation mRelation;
    private final AbstractAsset mSource;
    private final List<DynamicAppLinkComponent> mDynamicAppLinkComponents;

    private Statement(AbstractAsset source, AbstractAsset target, Relation relation,
                      List<DynamicAppLinkComponent> components) {
        mSource = source;
        mTarget = target;
        mRelation = relation;
        mDynamicAppLinkComponents = Collections.unmodifiableList(components);
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
     * Returns the relation matching rules of the statement.
     */
    @NonNull
    public List<DynamicAppLinkComponent> getDynamicAppLinkComponents() {
        return mDynamicAppLinkComponents;
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
                                   @NonNull Relation relation,
                                   @NonNull List<DynamicAppLinkComponent> components) {
        return new Statement(source, target, relation, components);
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
        if (!mDynamicAppLinkComponents.equals(statement.mDynamicAppLinkComponents)) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = mTarget.hashCode();
        result = 31 * result + mRelation.hashCode();
        result = 31 * result + mSource.hashCode();
        result = 31 * result + mDynamicAppLinkComponents.hashCode();
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
        if (!mDynamicAppLinkComponents.isEmpty()) {
            statement.append(", ");
            statement.append(mDynamicAppLinkComponents);
        }
        return statement.toString();
    }
}
