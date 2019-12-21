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

import static com.android.server.integrity.model.ComponentBitSize.ATOMIC_FORMULA_START;
import static com.android.server.integrity.model.ComponentBitSize.COMPOUND_FORMULA_END;
import static com.android.server.integrity.model.ComponentBitSize.COMPOUND_FORMULA_START;
import static com.android.server.integrity.model.ComponentBitSize.CONNECTOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.DEFAULT_FORMAT_VERSION;
import static com.android.server.integrity.model.ComponentBitSize.EFFECT_BITS;
import static com.android.server.integrity.model.ComponentBitSize.FORMAT_VERSION_BITS;
import static com.android.server.integrity.model.ComponentBitSize.KEY_BITS;
import static com.android.server.integrity.model.ComponentBitSize.OPERATOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.SEPARATOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.VALUE_SIZE_BITS;
import static com.android.server.integrity.serializer.RuleIndexingDetails.APP_CERTIFICATE_INDEXED;
import static com.android.server.integrity.serializer.RuleIndexingDetails.NOT_INDEXED;
import static com.android.server.integrity.serializer.RuleIndexingDetails.PACKAGE_NAME_INDEXED;

import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.Formula;
import android.content.integrity.Rule;

import com.android.server.integrity.model.BitOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A helper class to serialize rules from the {@link Rule} model to Binary representation. */
public class RuleBinarySerializer implements RuleSerializer {

    // Get the byte representation for a list of rules.
    @Override
    public byte[] serialize(List<Rule> rules, Optional<Integer> formatVersion)
            throws RuleSerializeException {
        try {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            serialize(rules, formatVersion, byteArrayOutputStream);
            return byteArrayOutputStream.toByteArray();
        } catch (Exception e) {
            throw new RuleSerializeException(e.getMessage(), e);
        }
    }

    // Get the byte representation for a list of rules, and write them to an output stream.
    @Override
    public void serialize(
            List<Rule> rules, Optional<Integer> formatVersion, OutputStream outputStream)
            throws RuleSerializeException {
        try {
            // Determine the indexing groups and the order of the rules within each indexed group.
            Map<Integer, List<Rule>> indexedRules =
                    RuleIndexingDetailsIdentifier.splitRulesIntoIndexBuckets(rules);

            serializeRuleFileMetadata(formatVersion, outputStream);

            serializeIndexedRules(indexedRules.get(PACKAGE_NAME_INDEXED), outputStream);
            serializeIndexedRules(indexedRules.get(APP_CERTIFICATE_INDEXED), outputStream);
            serializeIndexedRules(indexedRules.get(NOT_INDEXED), outputStream);
        } catch (Exception e) {
            throw new RuleSerializeException(e.getMessage(), e);
        }
    }

    private void serializeRuleFileMetadata(
            Optional<Integer> formatVersion, OutputStream outputStream) throws IOException {
        int formatVersionValue = formatVersion.orElse(DEFAULT_FORMAT_VERSION);

        BitOutputStream bitOutputStream = new BitOutputStream();
        bitOutputStream.setNext(FORMAT_VERSION_BITS, formatVersionValue);
        outputStream.write(bitOutputStream.toByteArray());
    }

    private void serializeIndexedRules(List<Rule> rules, OutputStream outputStream)
            throws IOException {
        BitOutputStream bitOutputStream = new BitOutputStream();
        for (Rule rule : rules) {
            bitOutputStream.clear();
            serializeRule(rule, bitOutputStream);
            outputStream.write(bitOutputStream.toByteArray());
        }
    }

    private void serializeRule(Rule rule, BitOutputStream bitOutputStream) {
        if (rule == null) {
            throw new IllegalArgumentException("Null rule can not be serialized");
        }

        // Start with a '1' bit to mark the start of a rule.
        bitOutputStream.setNext();

        serializeFormula(rule.getFormula(), bitOutputStream);
        bitOutputStream.setNext(EFFECT_BITS, rule.getEffect());

        // End with a '1' bit to mark the end of a rule.
        bitOutputStream.setNext();
    }

    private void serializeFormula(Formula formula, BitOutputStream bitOutputStream) {
        if (formula instanceof AtomicFormula) {
            serializeAtomicFormula((AtomicFormula) formula, bitOutputStream);
        } else if (formula instanceof CompoundFormula) {
            serializeCompoundFormula((CompoundFormula) formula, bitOutputStream);
        } else {
            throw new IllegalArgumentException(
                    String.format("Invalid formula type: %s", formula.getClass()));
        }
    }

    private void serializeCompoundFormula(
            CompoundFormula compoundFormula, BitOutputStream bitOutputStream) {
        if (compoundFormula == null) {
            throw new IllegalArgumentException("Null compound formula can not be serialized");
        }

        bitOutputStream.setNext(SEPARATOR_BITS, COMPOUND_FORMULA_START);
        bitOutputStream.setNext(CONNECTOR_BITS, compoundFormula.getConnector());
        for (Formula formula : compoundFormula.getFormulas()) {
            serializeFormula(formula, bitOutputStream);
        }
        bitOutputStream.setNext(SEPARATOR_BITS, COMPOUND_FORMULA_END);
    }

    private void serializeAtomicFormula(
            AtomicFormula atomicFormula, BitOutputStream bitOutputStream) {
        if (atomicFormula == null) {
            throw new IllegalArgumentException("Null atomic formula can not be serialized");
        }

        bitOutputStream.setNext(SEPARATOR_BITS, ATOMIC_FORMULA_START);
        bitOutputStream.setNext(KEY_BITS, atomicFormula.getKey());
        if (atomicFormula.getTag() == AtomicFormula.STRING_ATOMIC_FORMULA_TAG) {
            AtomicFormula.StringAtomicFormula stringAtomicFormula =
                    (AtomicFormula.StringAtomicFormula) atomicFormula;
            bitOutputStream.setNext(OPERATOR_BITS, AtomicFormula.EQ);
            serializeValue(
                    stringAtomicFormula.getValue(),
                    stringAtomicFormula.getIsHashedValue(),
                    bitOutputStream);
        } else if (atomicFormula.getTag() == AtomicFormula.INT_ATOMIC_FORMULA_TAG) {
            AtomicFormula.IntAtomicFormula intAtomicFormula =
                    (AtomicFormula.IntAtomicFormula) atomicFormula;
            bitOutputStream.setNext(OPERATOR_BITS, intAtomicFormula.getOperator());
            serializeValue(
                    String.valueOf(intAtomicFormula.getValue()),
                    /* isHashedValue= */ false,
                    bitOutputStream);
        } else if (atomicFormula.getTag() == AtomicFormula.BOOLEAN_ATOMIC_FORMULA_TAG) {
            AtomicFormula.BooleanAtomicFormula booleanAtomicFormula =
                    (AtomicFormula.BooleanAtomicFormula) atomicFormula;
            bitOutputStream.setNext(OPERATOR_BITS, AtomicFormula.EQ);
            serializeValue(
                    booleanAtomicFormula.getValue() ? "1" : "0",
                    /* isHashedValue= */ false,
                    bitOutputStream);
        } else {
            throw new IllegalArgumentException(
                    String.format("Invalid atomic formula type: %s", atomicFormula.getClass()));
        }
    }

    private void serializeValue(
            String value, boolean isHashedValue, BitOutputStream bitOutputStream) {
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);

        bitOutputStream.setNext(isHashedValue);
        bitOutputStream.setNext(VALUE_SIZE_BITS, valueBytes.length);
        for (byte valueByte : valueBytes) {
            bitOutputStream.setNext(/* numOfBits= */ 8, valueByte);
        }
    }
}
