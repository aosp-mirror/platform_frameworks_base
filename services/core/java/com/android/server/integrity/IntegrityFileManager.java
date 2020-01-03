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

package com.android.server.integrity;

import android.annotation.Nullable;
import android.content.integrity.AppInstallMetadata;
import android.content.integrity.Rule;
import android.os.Environment;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.integrity.model.RuleMetadata;
import com.android.server.integrity.parser.RuleBinaryParser;
import com.android.server.integrity.parser.RuleMetadataParser;
import com.android.server.integrity.parser.RuleParseException;
import com.android.server.integrity.parser.RuleParser;
import com.android.server.integrity.serializer.RuleBinarySerializer;
import com.android.server.integrity.serializer.RuleMetadataSerializer;
import com.android.server.integrity.serializer.RuleSerializeException;
import com.android.server.integrity.serializer.RuleSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Abstraction over the underlying storage of rules and other metadata. */
public class IntegrityFileManager {
    private static final String TAG = "IntegrityFileManager";

    // TODO: this is a prototype implementation of this class. Thus no tests are included.
    //  Implementing rule indexing will likely overhaul this class and more tests should be included
    //  then.

    private static final String METADATA_FILE = "metadata";
    private static final String RULES_FILE = "rules";
    private static final Object RULES_LOCK = new Object();

    private static IntegrityFileManager sInstance = null;

    private final RuleParser mRuleParser;
    private final RuleSerializer mRuleSerializer;

    private final File mDataDir;
    // mRulesDir contains data of the actual rules currently stored.
    private final File mRulesDir;
    // mStagingDir is used to store the temporary rules / metadata during updating, since we want to
    // update rules atomically.
    private final File mStagingDir;

    @Nullable private RuleMetadata mRuleMetadataCache;

    /** Get the singleton instance of this class. */
    public static synchronized IntegrityFileManager getInstance() {
        if (sInstance == null) {
            sInstance = new IntegrityFileManager();
        }
        return sInstance;
    }

    private IntegrityFileManager() {
        this(
                new RuleBinaryParser(),
                new RuleBinarySerializer(),
                Environment.getDataSystemDirectory());
    }

    @VisibleForTesting
    IntegrityFileManager(RuleParser ruleParser, RuleSerializer ruleSerializer, File dataDir) {
        mRuleParser = ruleParser;
        mRuleSerializer = ruleSerializer;
        mDataDir = dataDir;

        mRulesDir = new File(dataDir, "integrity_rules");
        mStagingDir = new File(dataDir, "integrity_staging");

        if (!mStagingDir.mkdirs() || !mRulesDir.mkdirs()) {
            Slog.e(TAG, "Error creating staging and rules directory");
            // TODO: maybe throw an exception?
        }

        File metadataFile = new File(mRulesDir, METADATA_FILE);
        if (metadataFile.exists()) {
            try (FileInputStream inputStream = new FileInputStream(metadataFile)) {
                mRuleMetadataCache = RuleMetadataParser.parse(inputStream);
            } catch (Exception e) {
                Slog.e(TAG, "Error reading metadata file.", e);
            }
        }
    }

    /**
     * Returns if the rules have been initialized.
     *
     * <p>Used to fail early if there are no rules (so we don't need to parse the apk at all).
     */
    public boolean initialized() {
        return new File(mRulesDir, RULES_FILE).exists()
                && new File(mRulesDir, METADATA_FILE).exists();
    }

    /** Write rules to persistent storage. */
    public void writeRules(String version, String ruleProvider, List<Rule> rules)
            throws IOException, RuleSerializeException {
        try {
            writeMetadata(mStagingDir, ruleProvider, version);
        } catch (IOException e) {
            Slog.e(TAG, "Error writing metadata.", e);
            // We don't consider this fatal so we continue execution.
        }

        try (FileOutputStream fileOutputStream =
                new FileOutputStream(new File(mStagingDir, RULES_FILE))) {
            mRuleSerializer.serialize(rules, Optional.empty(), fileOutputStream);
        }

        switchStagingRulesDir();
    }

    /**
     * Read rules from persistent storage.
     *
     * @param appInstallMetadata information about the install used to select rules to read
     */
    public List<Rule> readRules(AppInstallMetadata appInstallMetadata)
            throws IOException, RuleParseException {
        // TODO: select rules by index
        synchronized (RULES_LOCK) {
            try (FileInputStream inputStream =
                    new FileInputStream(new File(mRulesDir, RULES_FILE))) {
                List<Rule> rules = mRuleParser.parse(inputStream);
                return rules;
            }
        }
    }

    /** Read the metadata of the current rules in storage. */
    @Nullable
    public RuleMetadata readMetadata() {
        return mRuleMetadataCache;
    }

    private void switchStagingRulesDir() throws IOException {
        synchronized (RULES_LOCK) {
            File tmpDir = new File(mDataDir, "temp");

            if (!(mRulesDir.renameTo(tmpDir)
                    && mStagingDir.renameTo(mRulesDir)
                    && tmpDir.renameTo(mStagingDir))) {
                throw new IOException("Error switching staging/rules directory");
            }
        }
    }

    private void writeMetadata(File directory, String ruleProvider, String version)
            throws IOException {
        mRuleMetadataCache = new RuleMetadata(ruleProvider, version);

        File metadataFile = new File(directory, METADATA_FILE);

        try (FileOutputStream outputStream = new FileOutputStream(metadataFile)) {
            RuleMetadataSerializer.serialize(mRuleMetadataCache, outputStream);
        }
    }
}
