/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.firewall;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.ServiceManager;
import android.util.Slog;
import android.util.Xml;
import com.android.internal.util.XmlUtils;
import com.android.server.IntentResolver;
import com.android.server.pm.PackageManagerService;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class IntentFirewall {
    private static final String TAG = "IntentFirewall";

    private static final String RULES_FILENAME = "ifw.xml";

    private static final String TAG_RULES = "rules";
    private static final String TAG_ACTIVITY = "activity";
    private static final String TAG_SERVICE = "service";
    private static final String TAG_BROADCAST = "broadcast";

    private static final HashMap<String, FilterFactory> factoryMap;

    private final AMSInterface mAms;

    private final IntentResolver<FirewallIntentFilter, Rule> mActivityResolver =
            new FirewallIntentResolver();
    private final IntentResolver<FirewallIntentFilter, Rule> mServiceResolver =
            new FirewallIntentResolver();
    private final IntentResolver<FirewallIntentFilter, Rule> mBroadcastResolver =
            new FirewallIntentResolver();

    static {
        FilterFactory[] factories = new FilterFactory[] {
                AndFilter.FACTORY,
                OrFilter.FACTORY,
                NotFilter.FACTORY,

                StringFilter.ACTION,
                StringFilter.COMPONENT,
                StringFilter.COMPONENT_NAME,
                StringFilter.COMPONENT_PACKAGE,
                StringFilter.DATA,
                StringFilter.HOST,
                StringFilter.MIME_TYPE,
                StringFilter.PATH,
                StringFilter.SENDER_PACKAGE,
                StringFilter.SSP,

                CategoryFilter.FACTORY,
                SenderFilter.FACTORY,
                SenderPermissionFilter.FACTORY,
                PortFilter.FACTORY
        };

        // load factor ~= .75
        factoryMap = new HashMap<String, FilterFactory>(factories.length * 4 / 3);
        for (int i=0; i<factories.length; i++) {
            FilterFactory factory = factories[i];
            factoryMap.put(factory.getTagName(), factory);
        }
    }

    public IntentFirewall(AMSInterface ams) {
        mAms = ams;
        File dataSystemDir = new File(Environment.getDataDirectory(), "system");
        File rulesFile = new File(dataSystemDir, RULES_FILENAME);
        readRules(rulesFile);
    }

    public boolean checkStartActivity(Intent intent, ApplicationInfo callerApp,
            String callerPackage, int callerUid, int callerPid, String resolvedType,
            ActivityInfo resolvedActivity) {
        List<Rule> matchingRules = mActivityResolver.queryIntent(intent, resolvedType, false, 0);
        boolean log = false;
        boolean block = false;

        for (int i=0; i< matchingRules.size(); i++) {
            Rule rule = matchingRules.get(i);
            if (rule.matches(this, intent, callerApp, callerPackage, callerUid, callerPid,
                    resolvedType, resolvedActivity.applicationInfo)) {
                block |= rule.getBlock();
                log |= rule.getLog();

                // if we've already determined that we should both block and log, there's no need
                // to continue trying rules
                if (block && log) {
                    break;
                }
            }
        }

        if (log) {
            // TODO: log info about intent to event log
        }

        return !block;
    }

    private void readRules(File rulesFile) {
        FileInputStream fis;
        try {
            fis = new FileInputStream(rulesFile);
        } catch (FileNotFoundException ex) {
            // Nope, no rules. Nothing else to do!
            return;
        }

        try {
            XmlPullParser parser = Xml.newPullParser();

            parser.setInput(fis, null);

            XmlUtils.beginDocument(parser, TAG_RULES);

            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                IntentResolver<FirewallIntentFilter, Rule> resolver = null;
                String tagName = parser.getName();
                if (tagName.equals(TAG_ACTIVITY)) {
                    resolver = mActivityResolver;
                } else if (tagName.equals(TAG_SERVICE)) {
                    resolver = mServiceResolver;
                } else if (tagName.equals(TAG_BROADCAST)) {
                    resolver = mBroadcastResolver;
                }

                if (resolver != null) {
                    Rule rule = new Rule();

                    try {
                        rule.readFromXml(parser);
                    } catch (XmlPullParserException ex) {
                        Slog.e(TAG, "Error reading intent firewall rule", ex);
                        continue;
                    } catch (IOException ex) {
                        Slog.e(TAG, "Error reading intent firewall rule", ex);
                        continue;
                    }

                    for (int i=0; i<rule.getIntentFilterCount(); i++) {
                        resolver.addFilter(rule.getIntentFilter(i));
                    }
                }
            }
        } catch (XmlPullParserException ex) {
            Slog.e(TAG, "Error reading intent firewall rules", ex);
        } catch (IOException ex) {
            Slog.e(TAG, "Error reading intent firewall rules", ex);
        } finally {
            try {
                fis.close();
            } catch (IOException ex) {
                Slog.e(TAG, "Error while closing " + rulesFile, ex);
            }
        }
    }

    static Filter parseFilter(XmlPullParser parser) throws IOException, XmlPullParserException {
        String elementName = parser.getName();

        FilterFactory factory = factoryMap.get(elementName);

        if (factory == null) {
            throw new XmlPullParserException("Unknown element in filter list: " + elementName);
        }
        return factory.newFilter(parser);
    }

    private static class Rule extends AndFilter {
        private static final String TAG_INTENT_FILTER = "intent-filter";

        private static final String ATTR_BLOCK = "block";
        private static final String ATTR_LOG = "log";

        private final ArrayList<FirewallIntentFilter> mIntentFilters =
                new ArrayList<FirewallIntentFilter>(1);
        private boolean block;
        private boolean log;

        @Override
        public Rule readFromXml(XmlPullParser parser) throws IOException, XmlPullParserException {
            block = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_BLOCK));
            log = Boolean.parseBoolean(parser.getAttributeValue(null, ATTR_LOG));

            super.readFromXml(parser);
            return this;
        }

        @Override
        protected void readChild(XmlPullParser parser) throws IOException, XmlPullParserException {
            if (parser.getName().equals(TAG_INTENT_FILTER)) {
                FirewallIntentFilter intentFilter = new FirewallIntentFilter(this);
                intentFilter.readFromXml(parser);
                mIntentFilters.add(intentFilter);
            } else {
                super.readChild(parser);
            }
        }

        public int getIntentFilterCount() {
            return mIntentFilters.size();
        }

        public FirewallIntentFilter getIntentFilter(int index) {
            return mIntentFilters.get(index);
        }

        public boolean getBlock() {
            return block;
        }

        public boolean getLog() {
            return log;
        }
    }

    private static class FirewallIntentFilter extends IntentFilter {
        private final Rule rule;

        public FirewallIntentFilter(Rule rule) {
            this.rule = rule;
        }
    }

    private static class FirewallIntentResolver
            extends IntentResolver<FirewallIntentFilter, Rule> {
        @Override
        protected boolean allowFilterResult(FirewallIntentFilter filter, List<Rule> dest) {
            return !dest.contains(filter.rule);
        }

        @Override
        protected boolean isPackageForFilter(String packageName, FirewallIntentFilter filter) {
            return true;
        }

        @Override
        protected FirewallIntentFilter[] newArray(int size) {
            return new FirewallIntentFilter[size];
        }

        @Override
        protected Rule newResult(FirewallIntentFilter filter, int match, int userId) {
            return filter.rule;
        }

        @Override
        protected void sortResults(List<Rule> results) {
            // there's no need to sort the results
            return;
        }
    }

    /**
     * This interface contains the methods we need from ActivityManagerService. This allows AMS to
     * export these methods to us without making them public, and also makes it easier to test this
     * component.
     */
    public interface AMSInterface {
        int checkComponentPermission(String permission, int pid, int uid,
                int owningUid, boolean exported);
    }

    /**
     * Checks if the caller has access to a component
     *
     * @param permission If present, the caller must have this permission
     * @param pid The pid of the caller
     * @param uid The uid of the caller
     * @param owningUid The uid of the application that owns the component
     * @param exported Whether the component is exported
     * @return True if the caller can access the described component
     */
    boolean checkComponentPermission(String permission, int pid, int uid, int owningUid,
            boolean exported) {
        return mAms.checkComponentPermission(permission, pid, uid, owningUid, exported) ==
                PackageManager.PERMISSION_GRANTED;
    }

    boolean signaturesMatch(int uid1, int uid2) {
        PackageManagerService pm = (PackageManagerService)ServiceManager.getService("package");
        return pm.checkUidSignatures(uid1, uid2) == PackageManager.SIGNATURE_MATCH;
    }
}
