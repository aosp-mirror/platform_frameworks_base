/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.printservice.recommendation.plugin.mdnsFilter;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.util.ArrayMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Preconditions;

import com.android.printservice.recommendation.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Vendor configuration as read from {@link R.xml#vendorconfigs vendorconfigs.xml}. Configuration
 * can be read via {@link #getConfig(Context, String)}.
 */
public class VendorConfig {
    /** Lock for {@link #sConfigs} */
    private static final Object sLock = new Object();

    /** Strings used as XML tags */
    private static final String VENDORS_TAG = "vendors";
    private static final String VENDOR_TAG = "vendor";
    private static final String NAME_TAG = "name";
    private static final String PACKAGE_TAG = "package";
    private static final String MDNSNAMES_TAG = "mdns-names";
    private static final String MDNSNAME_TAG = "mdns-name";

    /** Map from vendor name to config. Initialized on first {@link #getConfig use}. */
    private static @Nullable ArrayMap<String, VendorConfig> sConfigs;

    /** Localized vendor name */
    public final @NonNull String name;

    /** Package name containing the print service for this vendor */
    public final @NonNull String packageName;

    /** mDNS names used by this vendor */
    public final @NonNull List<String> mDNSNames;

    /**
     * Create an immutable configuration.
     */
    private VendorConfig(@NonNull String name, @NonNull String packageName,
            @NonNull List<String> mDNSNames) {
        this.name = Preconditions.checkStringNotEmpty(name);
        this.packageName = Preconditions.checkStringNotEmpty(packageName);
        this.mDNSNames = Preconditions.checkCollectionElementsNotNull(mDNSNames, "mDNSName");
    }

    /**
     * Get the configuration for a vendor.
     *
     * @param context Calling context
     * @param name    The name of the config to read
     *
     * @return the config for the vendor or null if not found
     *
     * @throws IOException
     * @throws XmlPullParserException
     */
    public static @Nullable VendorConfig getConfig(@NonNull Context context, @NonNull String name)
            throws IOException, XmlPullParserException {
        synchronized (sLock) {
            if (sConfigs == null) {
                sConfigs = readVendorConfigs(context);
            }

            return sConfigs.get(name);
        }
    }

    /**
     * Get all known vendor configurations.
     *
     * @param context Calling context
     *
     * @return The known configurations
     *
     * @throws IOException
     * @throws XmlPullParserException
     */
    public static @NonNull Collection<VendorConfig> getAllConfigs(@NonNull Context context)
            throws IOException, XmlPullParserException {
        synchronized (sLock) {
            if (sConfigs == null) {
                sConfigs = readVendorConfigs(context);
            }

            return sConfigs.values();
        }
    }

    /**
     * Read the text from a XML tag.
     *
     * @param parser XML parser to read from
     *
     * @return The text or "" if no text was found
     *
     * @throws IOException
     * @throws XmlPullParserException
     */
    private static @NonNull String readText(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        String result = "";

        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.getText();
            parser.nextTag();
        }

        return result;
    }

    /**
     * Read a tag with a text content from the parser.
     *
     * @param parser  XML parser to read from
     * @param tagName The name of the tag to read
     *
     * @return The text content of the tag
     *
     * @throws IOException
     * @throws XmlPullParserException
     */
    private static @NonNull String readSimpleTag(@NonNull Context context,
            @NonNull XmlPullParser parser, @NonNull String tagName, boolean resolveReferences)
            throws IOException, XmlPullParserException {
        parser.require(XmlPullParser.START_TAG, null, tagName);
        String text = readText(parser);
        parser.require(XmlPullParser.END_TAG, null, tagName);

        if (resolveReferences && text.startsWith("@")) {
            return context.getResources().getString(
                    context.getResources().getIdentifier(text, null, context.getPackageName()));
        } else {
            return text;
        }
    }

    /**
     * Read content of a list of tags.
     *
     * @param parser     XML parser to read from
     * @param tagName    The name of the list tag
     * @param subTagName The name of the list-element tags
     * @param tagReader  The {@link TagReader reader} to use to read the tag content
     * @param <T>        The type of the parsed tag content
     *
     * @return A list of {@link T}
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static @NonNull <T> ArrayList<T> readTagList(@NonNull XmlPullParser parser,
            @NonNull String tagName, @NonNull String subTagName, @NonNull TagReader<T> tagReader)
            throws XmlPullParserException, IOException {
        ArrayList<T> entries = new ArrayList<>();

        parser.require(XmlPullParser.START_TAG, null, tagName);
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            if (parser.getName().equals(subTagName)) {
                entries.add(tagReader.readTag(parser, subTagName));
            } else {
                throw new XmlPullParserException(
                        "Unexpected subtag of " + tagName + ": " + parser.getName());
            }
        }

        return entries;
    }

    /**
     * Read the vendor configuration file.
     *
     * @param context The content issuing the read
     *
     * @return An map pointing from vendor name to config
     *
     * @throws IOException
     * @throws XmlPullParserException
     */
    private static @NonNull ArrayMap<String, VendorConfig> readVendorConfigs(
            @NonNull final Context context) throws IOException, XmlPullParserException {
        try (XmlResourceParser parser = context.getResources().getXml(R.xml.vendorconfigs)) {
            // Skip header
            int parsingEvent;
            do {
                parsingEvent = parser.next();
            } while (parsingEvent != XmlResourceParser.START_TAG);

            ArrayList<VendorConfig> configs = readTagList(parser, VENDORS_TAG, VENDOR_TAG,
                    new TagReader<VendorConfig>() {
                        public VendorConfig readTag(XmlPullParser parser, String tagName)
                                throws XmlPullParserException, IOException {
                            return readVendorConfig(context, parser, tagName);
                        }
                    });

            ArrayMap<String, VendorConfig> configMap = new ArrayMap<>(configs.size());
            final int numConfigs = configs.size();
            for (int i = 0; i < numConfigs; i++) {
                VendorConfig config = configs.get(i);

                configMap.put(config.name, config);
            }

            return configMap;
        }
    }

    /**
     * Read a single vendor configuration.
     *
     * @param parser  XML parser to read from
     * @param tagName The vendor tag
     * @param context Calling context
     *
     * @return A config
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private static VendorConfig readVendorConfig(@NonNull final Context context,
            @NonNull XmlPullParser parser, @NonNull String tagName) throws XmlPullParserException,
            IOException {
        parser.require(XmlPullParser.START_TAG, null, tagName);

        String name = null;
        String packageName = null;
        List<String> mDNSNames = null;

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.getEventType() != XmlPullParser.START_TAG) {
                continue;
            }

            String subTagName = parser.getName();

            switch (subTagName) {
                case NAME_TAG:
                    name = readSimpleTag(context, parser, NAME_TAG, false);
                    break;
                case PACKAGE_TAG:
                    packageName = readSimpleTag(context, parser, PACKAGE_TAG, true);
                    break;
                case MDNSNAMES_TAG:
                    mDNSNames = readTagList(parser, MDNSNAMES_TAG, MDNSNAME_TAG,
                            new TagReader<String>() {
                                public String readTag(XmlPullParser parser, String tagName)
                                        throws XmlPullParserException, IOException {
                                    return readSimpleTag(context, parser, tagName, true);
                                }
                            }
                    );
                    break;
                default:
                    throw new XmlPullParserException("Unexpected subtag of " + tagName + ": "
                            + subTagName);

            }
        }

        if (name == null) {
            throw new XmlPullParserException("name is required");
        }

        if (packageName == null) {
            throw new XmlPullParserException("package is required");
        }

        if (mDNSNames == null) {
            mDNSNames = Collections.emptyList();
        }

        // A vendor config should be immutable
        mDNSNames = Collections.unmodifiableList(mDNSNames);

        return new VendorConfig(name, packageName, mDNSNames);
    }

    @Override
    public String toString() {
        return name + " -> " + packageName + ", " + mDNSNames;
    }

    /**
     * Used a a "function pointer" when reading a tag in {@link #readTagList(XmlPullParser, String,
     * String, TagReader)}.
     *
     * @param <T> The type of content to read
     */
    private interface TagReader<T> {
        T readTag(XmlPullParser parser, String tagName) throws XmlPullParserException, IOException;
    }
}
