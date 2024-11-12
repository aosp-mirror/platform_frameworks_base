/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.appop;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for migrating discrete ops from xml to sqlite
 */
public class DiscreteOpsMigrationHelper {
    /**
     * migrate discrete ops from xml to sqlite.
     */
    static void migrateDiscreteOpsToSqlite(DiscreteOpsXmlRegistry xmlRegistry,
            DiscreteOpsSqlRegistry sqlRegistry) {
        DiscreteOpsXmlRegistry.DiscreteOps xmlOps = xmlRegistry.getAllDiscreteOps();
        List<DiscreteOpsSqlRegistry.DiscreteOp> discreteOps = getSqlDiscreteOps(xmlOps);
        sqlRegistry.migrateXmlData(discreteOps, xmlOps.mChainIdOffset);
        xmlRegistry.deleteDiscreteOpsDir();
    }

    /**
     * rollback discrete ops from sqlite to xml.
     */
    static void migrateDiscreteOpsToXml(DiscreteOpsSqlRegistry sqlRegistry,
            DiscreteOpsXmlRegistry xmlRegistry) {
        List<DiscreteOpsSqlRegistry.DiscreteOp> sqlOps = sqlRegistry.getAllDiscreteOps();
        DiscreteOpsXmlRegistry.DiscreteOps xmlOps = getXmlDiscreteOps(sqlOps);
        xmlRegistry.migrateSqliteData(xmlOps);
        sqlRegistry.deleteDatabase();
    }

    /**
     * Convert sqlite flat rows to hierarchical data.
     */
    private static DiscreteOpsXmlRegistry.DiscreteOps getXmlDiscreteOps(
            List<DiscreteOpsSqlRegistry.DiscreteOp> discreteOps) {
        DiscreteOpsXmlRegistry.DiscreteOps xmlOps =
                new DiscreteOpsXmlRegistry.DiscreteOps(0);
        if (discreteOps.isEmpty()) {
            return xmlOps;
        }

        for (DiscreteOpsSqlRegistry.DiscreteOp discreteOp : discreteOps) {
            xmlOps.addDiscreteAccess(discreteOp.getOpCode(), discreteOp.getUid(),
                    discreteOp.getPackageName(), discreteOp.getDeviceId(),
                    discreteOp.getAttributionTag(), discreteOp.getOpFlags(),
                    discreteOp.getUidState(),
                    discreteOp.getAccessTime(), discreteOp.getDuration(),
                    discreteOp.getAttributionFlags(), (int) discreteOp.getChainId());
        }
        return xmlOps;
    }

    /**
     * Convert xml (hierarchical) data to flat row based data.
     */
    private static List<DiscreteOpsSqlRegistry.DiscreteOp> getSqlDiscreteOps(
            DiscreteOpsXmlRegistry.DiscreteOps discreteOps) {
        List<DiscreteOpsSqlRegistry.DiscreteOp> opEvents = new ArrayList<>();

        if (discreteOps.isEmpty()) {
            return opEvents;
        }

        discreteOps.mUids.forEach((uid, discreteUidOps) -> {
            discreteUidOps.mPackages.forEach((packageName, packageOps) -> {
                packageOps.mPackageOps.forEach((opcode, ops) -> {
                    ops.mDeviceAttributedOps.forEach((deviceId, deviceOps) -> {
                        deviceOps.mAttributedOps.forEach((tag, attributedOps) -> {
                            for (DiscreteOpsXmlRegistry.DiscreteOpEvent attributedOp :
                                    attributedOps) {
                                DiscreteOpsSqlRegistry.DiscreteOp
                                        opModel = new DiscreteOpsSqlRegistry.DiscreteOp(uid,
                                        packageName, tag,
                                        deviceId, opcode, attributedOp.mOpFlag,
                                        attributedOp.mAttributionFlags,
                                        attributedOp.mUidState, attributedOp.mAttributionChainId,
                                        attributedOp.mNoteTime,
                                        attributedOp.mNoteDuration);
                                opEvents.add(opModel);
                            }
                        });
                    });
                });
            });
        });

        return opEvents;
    }
}
