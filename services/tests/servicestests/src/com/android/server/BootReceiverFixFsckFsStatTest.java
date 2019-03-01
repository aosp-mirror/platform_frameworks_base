/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server;

import static junit.framework.Assert.*;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BootReceiverFixFsckFsStatTest {

    private static final String PARTITION = "userdata";

    @Test
    public void testTreeOptimization() {
        final String[] logs = {
                "e2fsck 1.43.3 (04-Sep-2016)",
                "Pass 1: Checking inodes, blocks, and sizes",
                "Inode 877141 extent tree (at level 1) could be shorter.  Fix? yes",
                " ",
                "Pass 1E: Optimizing extent trees",
                "Pass 2: Checking directory structure",
                "Pass 3: Checking directory connectivity",
                "Pass 4: Checking reference counts",
                "Pass 5: Checking group summary information",
                "[QUOTA WARNING] Usage inconsistent for ID 10038:actual (71667712, 1000) != expected (71671808, 1000)",
                "Update quota info for quota type 0? yes",
                " ",
                "[QUOTA WARNING] Usage inconsistent for ID 10038:actual (59555840, 953) != expected (59559936, 953)",
                "Update quota info for quota type 1? yes",
                " ",
                "/dev/block/platform/soc/624000.ufshc/by-name/userdata: ***** FILE SYSTEM WAS MODIFIED *****"
        };
        doTestFsckFsStat(logs, 0x405, 0x5, 0, logs.length);

        final String[] doubleLogs = new String[logs.length * 2];
        System.arraycopy(logs, 0, doubleLogs, 0, logs.length);
        System.arraycopy(logs, 0, doubleLogs, logs.length, logs.length);
        doTestFsckFsStat(doubleLogs, 0x401, 0x1, 0, logs.length);
        doTestFsckFsStat(doubleLogs, 0x402, 0x2, logs.length, logs.length * 2);
    }

    @Test
    public void testQuotaOnly() {
        final String[] logs = {
                "e2fsck 1.43.3 (04-Sep-2016)",
                "Pass 1: Checking inodes, blocks, and sizes",
                "Pass 1E: Optimizing extent trees",
                "Pass 2: Checking directory structure",
                "Pass 3: Checking directory connectivity",
                "Pass 4: Checking reference counts",
                "Pass 5: Checking group summary information",
                "[QUOTA WARNING] Usage inconsistent for ID 10038:actual (71667712, 1000) != expected (71671808, 1000)",
                "Update quota info for quota type 0? yes",
                " ",
                "[QUOTA WARNING] Usage inconsistent for ID 10038:actual (59555840, 953) != expected (59559936, 953)",
                "Update quota info for quota type 1? yes",
                " ",
                "/dev/block/platform/soc/624000.ufshc/by-name/userdata: ***** FILE SYSTEM WAS MODIFIED *****"
        };
        // quota fix without tree optimization is an error.
        doTestFsckFsStat(logs, 0x405, 0x405, 0, logs.length);
    }

    @Test
    public void testOrphaned() {
        final String[] logs = {
                "e2fsck 1.43.3 (04-Sep-2016)",
                "Pass 1: Checking inodes, blocks, and sizes",
                "Inodes that were part of a corrupted orphan linked list found.  Fix? yes",
                " ",
                "Inode 589877 was part of the orphaned inode list.  FIXED.",
                " ",
                "Inode 589878 was part of the orphaned inode list.  FIXED.",
                " ",
                "Pass 2: Checking directory structure",
                "Pass 3: Checking directory connectivity",
                "Pass 4: Checking reference counts",
                "Pass 5: Checking group summary information",
                " ",
                "/dev/block/platform/soc/624000.ufshc/by-name/userdata: ***** FILE SYSTEM WAS MODIFIED *****"
        };
        doTestFsckFsStat(logs, 0x405, 0x405, 0, logs.length);
    }

    @Test
    public void testTimestampAdjustment() {
        final String[] logs = {
                "e2fsck 1.43.3 (04-Sep-2016)",
                "Pass 1: Checking inodes, blocks, and sizes",
                "Timestamp(s) on inode 508580 beyond 2310-04-04 are likely pre-1970.",
                "Fix? yes",
                " ",
                "Pass 1E: Optimizing extent trees",
                "Pass 2: Checking directory structure",
                "Pass 3: Checking directory connectivity",
                "Pass 4: Checking reference counts",
                "Pass 5: Checking group summary information",
                " ",
                "/dev/block/platform/soc/624000.ufshc/by-name/userdata: ***** FILE SYSTEM WAS MODIFIED *****"
        };
        doTestFsckFsStat(logs, 0x405, 0x5, 0, logs.length);
    }

    @Test
    public void testTimestampAdjustmentNoFixLine() {
        final String[] logs = {
                "e2fsck 1.43.3 (04-Sep-2016)",
                "Pass 1: Checking inodes, blocks, and sizes",
                "Timestamp(s) on inode 508580 beyond 2310-04-04 are likely pre-1970.",
                " ",
                "Pass 1E: Optimizing extent trees",
                "Pass 2: Checking directory structure",
                "Pass 3: Checking directory connectivity",
                "Pass 4: Checking reference counts",
                "Pass 5: Checking group summary information",
                " ",
                "/dev/block/platform/soc/624000.ufshc/by-name/userdata: ***** FILE SYSTEM WAS MODIFIED *****"
        };
        doTestFsckFsStat(logs, 0x405, 0x5, 0, logs.length);
    }

    @Test
    public void testTimestampAdjustmentWithQuotaFix() {
        final String[] logs = {
                "e2fsck 1.43.3 (04-Sep-2016)",
                "Pass 1: Checking inodes, blocks, and sizes",
                "Timestamp(s) on inode 508580 beyond 2310-04-04 are likely pre-1970.",
                "Fix? yes",
                " ",
                "Pass 1E: Optimizing extent trees",
                "Pass 2: Checking directory structure",
                "Pass 3: Checking directory connectivity",
                "Pass 4: Checking reference counts",
                "Pass 5: Checking group summary information",
                "[QUOTA WARNING] Usage inconsistent for ID 10038:actual (71667712, 1000) != expected (71671808, 1000)",
                "Update quota info for quota type 0? yes",
                " ",
                "[QUOTA WARNING] Usage inconsistent for ID 10038:actual (59555840, 953) != expected (59559936, 953)",
                "Update quota info for quota type 1? yes",
                " ",
                "/dev/block/platform/soc/624000.ufshc/by-name/userdata: ***** FILE SYSTEM WAS MODIFIED *****"
        };
        doTestFsckFsStat(logs, 0x405, 0x405, 0, logs.length);
    }

    @Test
    public void testAllNonFixes() {
        final String[] logs = {
                "e2fsck 1.43.3 (04-Sep-2016)",
                "Pass 1: Checking inodes, blocks, and sizes",
                "Timestamp(s) on inode 508580 beyond 2310-04-04 are likely pre-1970.",
                "Fix? yes",
                "Inode 877141 extent tree (at level 1) could be shorter.  Fix? yes",
                " ",
                "Pass 1E: Optimizing extent trees",
                "Pass 2: Checking directory structure",
                "Pass 3: Checking directory connectivity",
                "Pass 4: Checking reference counts",
                "Pass 5: Checking group summary information",
                "[QUOTA WARNING] Usage inconsistent for ID 10038:actual (71667712, 1000) != expected (71671808, 1000)",
                "Update quota info for quota type 0? yes",
                " ",
                "[QUOTA WARNING] Usage inconsistent for ID 10038:actual (59555840, 953) != expected (59559936, 953)",
                "Update quota info for quota type 1? yes",
                " ",
                "/dev/block/platform/soc/624000.ufshc/by-name/userdata: ***** FILE SYSTEM WAS MODIFIED *****"
        };
        doTestFsckFsStat(logs, 0x405, 0x5, 0, logs.length);
    }

    private void doTestFsckFsStat(String[] lines, int statOrg, int statUpdated, int startLineNumber,
            int endLineNumber) {
        assertEquals(statUpdated, BootReceiver.fixFsckFsStat(PARTITION, statOrg, lines,
                startLineNumber, endLineNumber));
    }
}
