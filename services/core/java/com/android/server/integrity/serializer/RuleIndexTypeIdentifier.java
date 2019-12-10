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

import android.annotation.IntDef;
import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Formula;
import android.content.integrity.Rule;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** A helper class for identifying the indexing type of a given rule. */
public class RuleIndexTypeIdentifier {

    static final int NOT_INDEXED = 0;
    static final int PACKAGE_NAME_INDEXED = 1;
    static final int APP_CERTIFICATE_INDEXED = 2;

    /** Represents which indexed file the rule should be located. */
    @IntDef(
            value = {
                    NOT_INDEXED,
                    PACKAGE_NAME_INDEXED,
                    APP_CERTIFICATE_INDEXED
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface IndexType {
    }

    /** Determines the indexing file type that a given rule should be located at. */
    public static int getIndexType(Rule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("Indexing type cannot be determined for null rule.");
        }
        return getIndexType(rule.getFormula());
    }

    private static int getIndexType(Formula formula) {
        if (formula == null) {
            throw new IllegalArgumentException(
                    "Indexing type cannot be determined for null formula.");
        }

        switch (formula.getTag()) {
            case Formula.COMPOUND_FORMULA_TAG:
                return getIndexTypeForCompoundFormula((CompoundFormula) formula);
            case Formula.STRING_ATOMIC_FORMULA_TAG:
                return getIndexTypeForAtomicStringFormula((AtomicFormula) formula);
            case Formula.INT_ATOMIC_FORMULA_TAG:
            case Formula.BOOLEAN_ATOMIC_FORMULA_TAG:
                // Package name and app certificate related formulas are string atomic formulas.
                return NOT_INDEXED;
            default:
                throw new IllegalArgumentException(
                        String.format("Invalid formula tag type: %s", formula.getTag()));
        }
    }

    private static int getIndexTypeForCompoundFormula(CompoundFormula compoundFormula) {
        int connector = compoundFormula.getConnector();
        List<Formula> formulas = compoundFormula.getFormulas();

        switch (connector) {
            case CompoundFormula.NOT:
                // Having a NOT operator in the indexing messes up the indexing; e.g., deny
                // installation if app certificate is NOT X (should not be indexed with app cert
                // X). We will not keep these rules indexed.
                return NOT_INDEXED;
            case CompoundFormula.AND:
            case CompoundFormula.OR:
                Set<Integer> indexingTypesForAllFormulas =
                        formulas.stream()
                                .map(formula -> getIndexType(formula))
                                .collect(Collectors.toSet());
                if (indexingTypesForAllFormulas.contains(PACKAGE_NAME_INDEXED)) {
                    return PACKAGE_NAME_INDEXED;
                } else if (indexingTypesForAllFormulas.contains(APP_CERTIFICATE_INDEXED)) {
                    return APP_CERTIFICATE_INDEXED;
                } else {
                    return NOT_INDEXED;
                }
            default:
                return NOT_INDEXED;
        }
    }

    private static int getIndexTypeForAtomicStringFormula(AtomicFormula atomicFormula) {
        switch (atomicFormula.getKey()) {
            case AtomicFormula.PACKAGE_NAME:
                return PACKAGE_NAME_INDEXED;
            case AtomicFormula.APP_CERTIFICATE:
                return APP_CERTIFICATE_INDEXED;
            default:
                return NOT_INDEXED;
        }
    }
}

