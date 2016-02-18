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

package android.app.backup;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.test.AndroidTestCase;
import android.util.ArrayMap;
import android.util.ArraySet;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FullBackupTest extends AndroidTestCase {
    private XmlPullParserFactory mFactory;
    private XmlPullParser mXpp;
    private Context mContext;

    Map<String, Set<String>> includeMap;
    Set<String> excludesSet;

    @Override
    public void setUp() throws Exception {
        mFactory = XmlPullParserFactory.newInstance();
        mXpp = mFactory.newPullParser();
        mContext = getContext();

        includeMap = new ArrayMap();
        excludesSet = new ArraySet();
    }

    public void testparseBackupSchemeFromXml_onlyInclude() throws Exception {
        mXpp.setInput(new StringReader(
                "<full-backup-content>" +
                        "<include path=\"onlyInclude.txt\" domain=\"file\"/>" +
                "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Excluding files when there was no <exclude/> tag.", 0, excludesSet.size());
        assertEquals("Unexpected number of <include/>s", 1, includeMap.size());

        Set<String> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertEquals("Didn't find expected file domain include.", 1, fileDomainIncludes.size());
        assertEquals("Invalid path parsed for <include/>",
                new File(mContext.getFilesDir(), "onlyInclude.txt").getCanonicalPath(),
                fileDomainIncludes.iterator().next());
    }

    public void testparseBackupSchemeFromXml_onlyExclude() throws Exception {
        mXpp.setInput(new StringReader(
                "<full-backup-content>" +
                    "<exclude path=\"onlyExclude.txt\" domain=\"file\"/>" +
                "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Including files when there was no <include/> tag.", 0, includeMap.size());
        assertEquals("Unexpected number of <exclude/>s", 1, excludesSet.size());
        assertEquals("Invalid path parsed for <exclude/>",
                new File(mContext.getFilesDir(), "onlyExclude.txt").getCanonicalPath(),
                excludesSet.iterator().next());
    }

    public void testparseBackupSchemeFromXml_includeAndExclude() throws Exception {
        mXpp.setInput(new StringReader(
                "<full-backup-content>" +
                        "<exclude path=\"exclude.txt\" domain=\"file\"/>" +
                        "<include path=\"include.txt\" domain=\"file\"/>" +
                "</full-backup-content>"));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        Set<String> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertEquals("Didn't find expected file domain include.", 1, fileDomainIncludes.size());
        assertEquals("Invalid path parsed for <include/>",
                new File(mContext.getFilesDir(), "include.txt").getCanonicalPath(),
                fileDomainIncludes.iterator().next());

        assertEquals("Unexpected number of <exclude/>s", 1, excludesSet.size());
        assertEquals("Invalid path parsed for <exclude/>",
                new File(mContext.getFilesDir(), "exclude.txt").getCanonicalPath(),
                excludesSet.iterator().next());
    }

    public void testparseBackupSchemeFromXml_lotsOfIncludesAndExcludes() throws Exception {
        mXpp.setInput(new StringReader(
                "<full-backup-content>" +
                         "<exclude path=\"exclude1.txt\" domain=\"file\"/>" +
                        "<include path=\"include1.txt\" domain=\"file\"/>" +
                         "<exclude path=\"exclude2.txt\" domain=\"database\"/>" +
                        "<include path=\"include2.txt\" domain=\"database\"/>" +
                         "<exclude path=\"exclude3\" domain=\"sharedpref\"/>" +
                        "<include path=\"include3\" domain=\"sharedpref\"/>" +
                         "<exclude path=\"exclude4.xml\" domain=\"sharedpref\"/>" +
                        "<include path=\"include4.xml\" domain=\"sharedpref\"/>" +
                "</full-backup-content>"));


        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        Set<String> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertEquals("Didn't find expected file domain include.", 1, fileDomainIncludes.size());
        assertEquals("Invalid path parsed for <include/>",
                new File(mContext.getFilesDir(), "include1.txt").getCanonicalPath(),
                fileDomainIncludes.iterator().next());

        Set<String> databaseDomainIncludes = includeMap.get(FullBackup.DATABASE_TREE_TOKEN);
        // Three expected here because of "-journal" and "-wal" files
        assertEquals("Didn't find expected database domain include.",
                3, databaseDomainIncludes.size());
        assertTrue("Invalid path parsed for <include/>",
                databaseDomainIncludes.contains(
                        new File(mContext.getDatabasePath("foo").getParentFile(), "include2.txt")
                                .getCanonicalPath()));
        assertTrue("Invalid path parsed for <include/>",
                databaseDomainIncludes.contains(
                        new File(
                                mContext.getDatabasePath("foo").getParentFile(),
                                "include2.txt-journal")
                                .getCanonicalPath()));
        assertTrue("Invalid path parsed for <include/>",
                databaseDomainIncludes.contains(
                        new File(
                                mContext.getDatabasePath("foo").getParentFile(),
                                "include2.txt-wal")
                                .getCanonicalPath()));

        List<String> sharedPrefDomainIncludes = new ArrayList<String>(
                includeMap.get(FullBackup.SHAREDPREFS_TREE_TOKEN));
        Collections.sort(sharedPrefDomainIncludes);

        assertEquals("Didn't find expected sharedpref domain include.",
                3, sharedPrefDomainIncludes.size());
        assertEquals("Invalid path parsed for <include/>",
                new File(mContext.getSharedPrefsFile("foo").getParentFile(), "include3")
                        .getCanonicalPath(),
                sharedPrefDomainIncludes.get(0));
        assertEquals("Invalid path parsed for <include/>",
                new File(mContext.getSharedPrefsFile("foo").getParentFile(), "include3.xml")
                        .getCanonicalPath(),
                sharedPrefDomainIncludes.get(1));
        assertEquals("Invalid path parsed for <include/>",
                new File(mContext.getSharedPrefsFile("foo").getParentFile(), "include4.xml")
                        .getCanonicalPath(),
                sharedPrefDomainIncludes.get(2));


        assertEquals("Unexpected number of <exclude/>s", 7, excludesSet.size());
        // Sets are annoying to iterate over b/c order isn't enforced - convert to an array and
        // sort lexicographically.
        List<String> arrayedSet = new ArrayList<String>(excludesSet);
        Collections.sort(arrayedSet);

        assertEquals("Invalid path parsed for <exclude/>",
                new File(mContext.getDatabasePath("foo").getParentFile(), "exclude2.txt")
                        .getCanonicalPath(),
                arrayedSet.get(0));
        assertEquals("Invalid path parsed for <exclude/>",
                new File(mContext.getDatabasePath("foo").getParentFile(), "exclude2.txt-journal")
                        .getCanonicalPath(),
                arrayedSet.get(1));
        assertEquals("Invalid path parsed for <exclude/>",
                new File(mContext.getDatabasePath("foo").getParentFile(), "exclude2.txt-wal")
                        .getCanonicalPath(),
                arrayedSet.get(2));
        assertEquals("Invalid path parsed for <exclude/>",
                new File(mContext.getFilesDir(), "exclude1.txt").getCanonicalPath(),
                arrayedSet.get(3));
        assertEquals("Invalid path parsed for <exclude/>",
                new File(mContext.getSharedPrefsFile("foo").getParentFile(), "exclude3")
                        .getCanonicalPath(),
                arrayedSet.get(4));
        assertEquals("Invalid path parsed for <exclude/>",
                new File(mContext.getSharedPrefsFile("foo").getParentFile(), "exclude3.xml")
                        .getCanonicalPath(),
                arrayedSet.get(5));
        assertEquals("Invalid path parsed for <exclude/>",
                new File(mContext.getSharedPrefsFile("foo").getParentFile(), "exclude4.xml")
                        .getCanonicalPath(),
                arrayedSet.get(6));
    }

    public void testParseBackupSchemeFromXml_invalidXmlFails() throws Exception {
        // Invalid root tag.
        mXpp.setInput(new StringReader(
                "<full-weird-tag>" +
                        "<exclude path=\"invalidRootTag.txt\" domain=\"file\"/>" +
                        "</ffull-weird-tag>" ));

        try {
            FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
            bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);
            fail("Invalid root xml tag should throw an XmlPullParserException");
        } catch (XmlPullParserException expected) {}

        // Invalid exclude tag.
        mXpp.setInput(new StringReader(
                "<full-backup-content>" +
                        "<excluded path=\"invalidExcludeTag.txt\" domain=\"file\"/>" +
                "</full-backup-conten>t" ));
        try {
            FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
            bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);
            fail("Misspelled xml exclude tag should throw an XmlPullParserException");
        } catch (XmlPullParserException expected) {}

        // Just for good measure - invalid include tag.
        mXpp.setInput(new StringReader(
                "<full-backup-content>" +
                        "<yinclude path=\"invalidIncludeTag.txt\" domain=\"file\"/>" +
                        "</full-backup-conten>t" ));
        try {
            FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
            bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);
            fail("Misspelled xml exclude tag should throw an XmlPullParserException");
        } catch (XmlPullParserException expected) {}

    }

    public void testInvalidPath_doesNotBackup() throws Exception {
        mXpp.setInput(new StringReader(
                "<full-backup-content>" +
                        "<exclude path=\"..\" domain=\"file\"/>" +  // Invalid use of ".." dir.
                        "</full-backup-content>" ));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Didn't throw away invalid \"..\" path.", 0, includeMap.size());

        Set<String> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertNull("Didn't throw away invalid \"..\" path.", fileDomainIncludes);
    }
    public void testDoubleDotInPath_isIgnored() throws Exception {
        mXpp.setInput(new StringReader(
                "<full-backup-content>" +
                        "<include path=\"..\" domain=\"file\"/>" +  // Invalid use of ".." dir.
                        "</full-backup-content>" ));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Didn't throw away invalid \"..\" path.", 0, includeMap.size());

        Set<String> fileDomainIncludes = includeMap.get(FullBackup.FILES_TREE_TOKEN);
        assertNull("Didn't throw away invalid \"..\" path.", fileDomainIncludes);
    }

    public void testDoubleSlashInPath_isIgnored() throws Exception {
        mXpp.setInput(new StringReader(
                "<full-backup-content>" +
                        "<exclude path=\"//hello.txt\" domain=\"file\"/>" +  // Invalid use of "//"
                        "</full-backup-content>" ));

        FullBackup.BackupScheme bs = FullBackup.getBackupSchemeForTest(mContext);
        bs.parseBackupSchemeFromXmlLocked(mXpp, excludesSet, includeMap);

        assertEquals("Didn't throw away invalid path containing \"//\".", 0, excludesSet.size());
    }
}
