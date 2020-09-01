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
import static com.android.server.integrity.model.ComponentBitSize.INSTALLER_ALLOWED_BY_MANIFEST_START;
import static com.android.server.integrity.model.ComponentBitSize.KEY_BITS;
import static com.android.server.integrity.model.ComponentBitSize.OPERATOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.SEPARATOR_BITS;
import static com.android.server.integrity.model.ComponentBitSize.VALUE_SIZE_BITS;
import static com.android.server.integrity.model.IndexingFileConstants.END_INDEXING_KEY;
import static com.android.server.integrity.model.IndexingFileConstants.INDEXING_BLOCK_SIZE;
import static com.android.server.integrity.model.IndexingFileConstants.START_INDEXING_KEY;
import static com.android.server.integrity.serializer.RuleIndexingDetails.APP_CERTIFICATE_INDEXED;
import static com.android.server.integrity.serializer.RuleIndexingDetails.NOT_INDEXED;
import static com.android.server.integrity.serializer.RuleIndexingDetails.PACKAGE_NAME_INDEXED;

import android.content.integrity.AtomicFormula;
import android.content.integrity.CompoundFormula;
import android.content.integrity.InstallerAllowedByManifestFormula;
import android.content.integrity.IntegrityFormula;
import android.content.integrity.IntegrityUtils;
import android.content.integrity.Rule;

import com.android.internal.util.Preconditions;
import com.android.server.integrity.model.BitOutputStream;
import com.android.server.integrity.model.ByteTrackedOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** A helper class to serialize rules from the {@link Rule} model to Binary representation. */
public class RuleBinarySerializer implements RuleSerializer {
    static final int TOTAL_RULE_SIZE_LIMIT = 200000;
    static final int INDEXED_RULE_SIZE_LIMIT = 100000;
    static final int NONINDEXED_RULE_SIZE_LIMIT = 1000;

    // Get the byte representation for a list of rules.
    @Override
    public byte[] serialize(List<Rule> rules, Optional<Integer> formatVersion)
            throws RuleSerializeException {
        try {
            ByteArrayOutputStream rulesOutputStream = new ByteArrayOutputStream();
            serialize(rules, formatVersion, rulesOutputStream, new ByteArrayOutputStream());
            return rulesOutputStream.toByteArray();
        } catch (Exception e) {
            throw new RuleSerializeException(e.getMessage(), e);
        }
    }

    // Get the byte representation for a list of rules, and write them to an output stream.
    @Override
    public void serialize(
            List<Rule> rules,
            Optional<Integer> formatVersion,
            OutputStream rulesFileOutputStream,
            OutputStream indexingFileOutputStream)
            throws RuleSerializeException {
        try {
            if (rules == null) {
                throw new IllegalArgumentException("Null rules cannot be serialized.");
            }

            if (rules.size() > TOTAL_RULE_SIZE_LIMIT) {
                throw new IllegalArgumentException("Too many rules provided: " + rules.size());
            }

            // Determine the indexing groups and the order of the rules within each indexed group.
            Map<Integer, Map<String, List<Rule>>> indexedRules =
                    RuleIndexingDetailsIdentifier.splitRulesIntoIndexBuckets(rules);

            // Validate the rule blocks are not larger than expected limits.
            verifySize(indexedRules.get(PACKAGE_NAME_INDEXED), INDEXED_RULE_SIZE_LIMIT);
            verifySize(indexedRules.get(APP_CERTIFICATE_INDEXED), INDEXED_RULE_SIZE_LIMIT);
            verifySize(indexedRules.get(NOT_INDEXED), NONINDEXED_RULE_SIZE_LIMIT);

            // Serialize the rules.
            ByteTrackedOutputStream ruleFileByteTrackedOutputStream =
                    new ByteTrackedOutputStream(rulesFileOutputStream);
            serializeRuleFileMetadata(formatVersion, ruleFileByteTrackedOutputStream);
            LinkedHashMap<String, Integer> packageNameIndexes =
                    serializeRuleList(
                            indexedRules.get(PACKAGE_NAME_INDEXED),
                            ruleFileByteTrackedOutputStream);
            LinkedHashMap<String, Integer> appCertificateIndexes =
                    serializeRuleList(
                            indexedRules.get(APP_CERTIFICATE_INDEXED),
                            ruleFileByteTrackedOutputStream);
            LinkedHashMap<String, Integer> unindexedRulesIndexes =
                    serializeRuleList(
                            indexedRules.get(NOT_INDEXED), ruleFileByteTrackedOutputStream);

            // Serialize their indexes.
            BitOutputStream indexingBitOutputStream = new BitOutputStream(indexingFileOutputStream);
            serializeIndexGroup(packageNameIndexes, indexingBitOutputStream, /* isIndexed= */ true);
            serializeIndexGroup(
                    appCertificateIndexes, indexingBitOutputStream, /* isIndexed= */ true);
            serializeIndexGroup(
                    unindexedRulesIndexes, indexingBitOutputStream, /* isIndexed= */ false);
            indexingBitOutputStream.flush();
        } catch (Exception e) {
            throw new RuleSerializeException(e.getMessage(), e);
        }
    }

    private void verifySize(Map<String, List<Rule>> ruleListMap, int ruleSizeLimit) {
        int totalRuleCount =
                ruleListMap.values().stream()
                        .map(list -> list.size())
                        .collect(Collectors.summingInt(Integer::intValue));
        if (totalRuleCount > ruleSizeLimit) {
            throw new IllegalArgumentException(
                    "Too many rules provided in the indexing group. Provided "
                            + totalRuleCount
                            + " limit "
                            + ruleSizeLimit);
        }
    }

    private void serializeRuleFileMetadata(
            Optional<Integer> formatVersion, ByteTrackedOutputStream outputStream)
            throws IOException {
        int formatVersionValue = formatVersion.orElse(DEFAULT_FORMAT_VERSION);

        BitOutputStream bitOutputStream = new BitOutputStream(outputStream);
        bitOutputStream.setNext(FORMAT_VERSION_BITS, formatVersionValue);
        bitOutputStream.flush();
    }

    private LinkedHashMap<String, Integer> serializeRuleList(
            Map<String, List<Rule>> rulesMap, ByteTrackedOutputStream outputStream)
            throws IOException {
        Preconditions.checkArgument(
                rulesMap != null, "serializeRuleList should never be called with null rule list.");

        BitOutputStream bitOutputStream = new BitOutputStream(outputStream);
        LinkedHashMap<String, Integer> indexMapping = new LinkedHashMap();
        indexMapping.put(START_INDEXING_KEY, outputStream.getWrittenBytesCount());

        List<String> sortedKeys = rulesMap.keySet().stream().sorted().collect(Collectors.toList());
        int indexTracker = 0;
        for (String key : sortedKeys) {
            if (indexTracker >= INDEXING_BLOCK_SIZE) {
                indexMapping.put(key, outputStream.getWrittenBytesCount());
                indexTracker = 0;
            }

            for (Rule rule : rulesMap.get(key)) {
                serializeRule(rule, bitOutputStream);
                bitOutputStream.flush();
                indexTracker++;
            }
        }
        indexMapping.put(END_INDEXING_KEY, outputStream.getWrittenBytesCount());

        return indexMapping;
    }

    private void serializeRule(Rule rule, BitOutputStream bitOutputStream) throws IOException {
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

    private void serializeFormula(IntegrityFormula formula, BitOutputStream bitOutputStream)
            throws IOException {
        if (formula instanceof AtomicFormula) {
            serializeAtomicFormula((AtomicFormula) formula, bitOutputStream);
        } else if (formula instanceof CompoundFormula) {
            serializeCompoundFormula((CompoundFormula) formula, bitOutputStream);
        } else if (formula instanceof InstallerAllowedByManifestFormula) {
            bitOutputStream.setNext(SEPARATOR_BITS, INSTALLER_ALLOWED_BY_MANIFEST_START);
        } else {
            throw new IllegalArgumentException(
                    String.format("Invalid formula type: %s", formula.getClass()));
        }
    }

    private void serializeCompoundFormula(
            CompoundFormula compoundFormula, BitOutputStream bitOutputStream) throws IOException {
        if (compoundFormula == null) {
            throw new IllegalArgumentException("Null compound formula can not be serialized");
        }

        bitOutputStream.setNext(SEPARATOR_BITS, COMPOUND_FORMULA_START);
        bitOutputStream.setNext(CONNECTOR_BITS, compoundFormula.getConnector());
        for (IntegrityFormula formula : compoundFormula.getFormulas()) {
            serializeFormula(formula, bitOutputStream);
        }
        bitOutputStream.setNext(SEPARATOR_BITS, COMPOUND_FORMULA_END);
    }

    private void serializeAtomicFormula(
            AtomicFormula atomicFormula, BitOutputStream bitOutputStream) throws IOException {
        if (atomicFormula == null) {
            throw new IllegalArgumentException("Null atomic formula can not be serialized");
        }

        bitOutputStream.setNext(SEPARATOR_BITS, ATOMIC_FORMULA_START);
        bitOutputStream.setNext(KEY_BITS, atomicFormula.getKey());
        if (atomicFormula.getTag() == AtomicFormula.STRING_ATOMIC_FORMULA_TAG) {
            AtomicFormula.StringAtomicFormula stringAtomicFormula =
                    (AtomicFormula.StringAtomicFormula) atomicFormula;
            bitOutputStream.setNext(OPERATOR_BITS, AtomicFormula.EQ);
            serializeStringValue(
                    stringAtomicFormula.getValue(),
                    stringAtomicFormula.getIsHashedValue(),
                    bitOutputStream);
        } else if (atomicFormula.getTag() == AtomicFormula.LONG_ATOMIC_FORMULA_TAG) {
            AtomicFormula.LongAtomicFormula longAtomicFormula =
                    (AtomicFormula.LongAtomicFormula) atomicFormula;
            bitOutputStream.setNext(OPERATOR_BITS, longAtomicFormula.getOperator());
            // TODO(b/147880712): Temporary hack until we support long values in bitOutputStream
            long value = longAtomicFormula.getValue();
            serializeIntValue((int) (value >>> 32), bitOutputStream);
            serializeIntValue((int) value, bitOutputStream);
        } else if (atomicFormula.getTag() == AtomicFormula.BOOLEAN_ATOMIC_FORMULA_TAG) {
            AtomicFormula.BooleanAtomicFormula booleanAtomicFormula =
                    (AtomicFormula.BooleanAtomicFormula) atomicFormula;
            bitOutputStream.setNext(OPERATOR_BITS, AtomicFormula.EQ);
            serializeBooleanValue(booleanAtomicFormula.getValue(), bitOutputStream);
        } else {
            throw new IllegalArgumentException(
                    String.format("Invalid atomic formula type: %s", atomicFormula.getClass()));
        }
    }

    private void serializeIndexGroup(
            LinkedHashMap<String, Integer> indexes,
            BitOutputStream bitOutputStream,
            boolean isIndexed)
            throws IOException {
        // Output the starting location of this indexing group.
        serializeStringValue(START_INDEXING_KEY, /* isHashedValue= */ false, bitOutputStream);
        serializeIntValue(indexes.get(START_INDEXING_KEY), bitOutputStream);

        // If the group is indexed, output the locations of the indexes.
        if (isIndexed) {
            for (Map.Entry<String, Integer> entry : indexes.entrySet()) {
                if (!entry.getKey().equals(START_INDEXING_KEY)
                        && !entry.getKey().equals(END_INDEXING_KEY)) {
                    serializeStringValue(
                            entry.getKey(), /* isHashedValue= */ false, bitOutputStream);
                    serializeIntValue(entry.getValue(), bitOutputStream);
                }
            }
        }

        // Output the end location of this indexing group.
        serializeStringValue(END_INDEXING_KEY, /*isHashedValue= */ false, bitOutputStream);
        serializeIntValue(indexes.get(END_INDEXING_KEY), bitOutputStream);
    }

    private void serializeStringValue(
            String value, boolean isHashedValue, BitOutputStream bitOutputStream)
            throws IOException {
        if (value == null) {
            throw new IllegalArgumentException("String value can not be null.");
        }
        byte[] valueBytes = getBytesForString(value, isHashedValue);

        bitOutputStream.setNext(isHashedValue);
        bitOutputStream.setNext(VALUE_SIZE_BITS, valueBytes.length);
        for (byte valueByte : valueBytes) {
            bitOutputStream.setNext(/* numOfBits= */ 8, valueByte);
        }
    }

    private void serializeIntValue(int value, BitOutputStream bitOutputStream) throws IOException {
        bitOutputStream.setNext(/* numOfBits= */ 32, value);
    }

    private void serializeBooleanValue(boolean value, BitOutputStream bitOutputStream)
            throws IOException {
        bitOutputStream.setNext(value);
    }

    // Get the byte array for a value.
    // If the value is not hashed, use its byte array form directly.
    // If the value is hashed, get the raw form decoding of the value. All hashed values are
    // hex-encoded. Serialized values are in raw form.
    private static byte[] getBytesForString(String value, boolean isHashedValue) {
        if (!isHashedValue) {
            return value.getBytes(StandardCharsets.UTF_8);
        }
        return IntegrityUtils.getBytesFromHexDigest(value);
    }
}
