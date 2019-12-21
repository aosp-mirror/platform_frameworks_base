/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.integrity.serializer;

import static com.android.server.integrity.serializer.RuleIndexingDetails.APP_CERTIFICATE_INDEXED;
import static com.android.server.integrity.serializer.RuleIndexingDetails.NOT_INDEXED;
import static com.android.server.integrity.serializer.RuleIndexingDetails.PACKAGE_NAME_INDEXED;

import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Formula;
import android.content.integrity.Rule;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** A helper class for identifying the indexing type and key of a given rule. */
class RuleIndexingDetailsIdentifier {

    private static final String DEFAULT_RULE_KEY = "N/A";

    /**
     * Splits a given rule list into three indexing categories and returns a sorted list of rules
     * per each index.
     *
     * The sorting guarantees an order based on the key but the rules that have the same key
     * can be in arbitrary order. For example, given the rules of [package_name_a_rule_1,
     * package_name_a_rule_2, package_name_b_rule_3, package_name_b_rule_4], the  method will
     * guarantee that package_name_b rules (i.e., 3 and 4) will never come before package_name_a
     * rules (i.e., 1 and 2). However, we do not care about the ordering between rule 1 and 2.
     * We also do not care about the ordering between rule 3 and 4.
     */
    public static Map<Integer, List<Rule>> splitRulesIntoIndexBuckets(List<Rule> rules) {
        if (rules == null) {
            throw new IllegalArgumentException(
                    "Index buckets cannot be created for null rule list.");
        }

        Map<Integer, Map<String, List<Rule>>> typeOrganizedRuleMap = new HashMap();
        typeOrganizedRuleMap.put(NOT_INDEXED, new TreeMap());
        typeOrganizedRuleMap.put(PACKAGE_NAME_INDEXED, new TreeMap());
        typeOrganizedRuleMap.put(APP_CERTIFICATE_INDEXED, new TreeMap());

        // Split the rules into the appropriate indexed pattern. The Tree Maps help us to keep the
        // entries sorted by their index key.
        for (Rule rule : rules) {
            RuleIndexingDetails indexingDetails;
            try {
                indexingDetails = getIndexingDetails(rule.getFormula());
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        String.format("Malformed rule identified. [%s]", rule.toString()));
            }

            String ruleKey =
                    indexingDetails.getIndexType() != NOT_INDEXED
                            ? indexingDetails.getRuleKey()
                            : DEFAULT_RULE_KEY;

            if (!typeOrganizedRuleMap.get(indexingDetails.getIndexType()).containsKey(ruleKey)) {
                typeOrganizedRuleMap
                        .get(indexingDetails.getIndexType())
                        .put(ruleKey, new ArrayList());
            }

            typeOrganizedRuleMap
                    .get(indexingDetails.getIndexType())
                    .get(ruleKey)
                    .add(rule);
        }

        // Per indexing type, create the sorted rule set based on their key.
        Map<Integer, List<Rule>> orderedListPerIndexingType = new HashMap<>();

        for (Integer indexingKey : typeOrganizedRuleMap.keySet()) {
            List<Rule> sortedRules = new ArrayList();
            for (Map.Entry<String, List<Rule>> entry :
                    typeOrganizedRuleMap.get(indexingKey).entrySet()) {
                sortedRules.addAll(entry.getValue());
            }
            orderedListPerIndexingType.put(indexingKey, sortedRules);
        }

        return orderedListPerIndexingType;
    }

    private static RuleIndexingDetails getIndexingDetails(Formula formula) {
        switch (formula.getTag()) {
            case Formula.COMPOUND_FORMULA_TAG:
                return getIndexingDetailsForCompoundFormula((CompoundFormula) formula);
            case Formula.STRING_ATOMIC_FORMULA_TAG:
                return getIndexingDetailsForStringAtomicFormula(
                        (AtomicFormula.StringAtomicFormula) formula);
            case Formula.INT_ATOMIC_FORMULA_TAG:
            case Formula.BOOLEAN_ATOMIC_FORMULA_TAG:
                // Package name and app certificate related formulas are string atomic formulas.
                return new RuleIndexingDetails(NOT_INDEXED);
            default:
                throw new IllegalArgumentException(
                        String.format("Invalid formula tag type: %s", formula.getTag()));
        }
    }

    private static RuleIndexingDetails getIndexingDetailsForCompoundFormula(
            CompoundFormula compoundFormula) {
        int connector = compoundFormula.getConnector();
        List<Formula> formulas = compoundFormula.getFormulas();

        switch (connector) {
            case CompoundFormula.AND:
            case CompoundFormula.OR:
                // If there is a package name related atomic rule, return package name indexed.
                Optional<RuleIndexingDetails> packageNameRule =
                        formulas.stream()
                                .map(formula -> getIndexingDetails(formula))
                                .filter(ruleIndexingDetails -> ruleIndexingDetails.getIndexType()
                                        == PACKAGE_NAME_INDEXED)
                                .findAny();
                if (packageNameRule.isPresent()) {
                    return packageNameRule.get();
                }

                // If there is an app certificate related atomic rule but no package name related
                // atomic rule, return app certificate indexed.
                Optional<RuleIndexingDetails> appCertificateRule =
                        formulas.stream()
                                .map(formula -> getIndexingDetails(formula))
                                .filter(ruleIndexingDetails -> ruleIndexingDetails.getIndexType()
                                        == APP_CERTIFICATE_INDEXED)
                                .findAny();
                if (appCertificateRule.isPresent()) {
                    return appCertificateRule.get();
                }

                // Do not index when there is not package name or app certificate indexing.
                return new RuleIndexingDetails(NOT_INDEXED);
            default:
                // Having a NOT operator in the indexing messes up the indexing; e.g., deny
                // installation if app certificate is NOT X (should not be indexed with app cert
                // X). We will not keep these rules indexed.
                // Also any other type of unknown operators will not be indexed.
                return new RuleIndexingDetails(NOT_INDEXED);
        }
    }

    private static RuleIndexingDetails getIndexingDetailsForStringAtomicFormula(
            AtomicFormula.StringAtomicFormula atomicFormula) {
        switch (atomicFormula.getKey()) {
            case AtomicFormula.PACKAGE_NAME:
                return new RuleIndexingDetails(PACKAGE_NAME_INDEXED, atomicFormula.getValue());
            case AtomicFormula.APP_CERTIFICATE:
                return new RuleIndexingDetails(APP_CERTIFICATE_INDEXED, atomicFormula.getValue());
            default:
                return new RuleIndexingDetails(NOT_INDEXED);
        }
    }
}

