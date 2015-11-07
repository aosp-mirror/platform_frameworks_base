package android.security.net.config;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.util.ArraySet;
import android.util.Base64;
import android.util.Pair;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@link ConfigSource} based on an XML configuration file.
 *
 * @hide
 */
public class XmlConfigSource implements ConfigSource {
    private final Object mLock = new Object();
    private final int mResourceId;

    private boolean mInitialized;
    private NetworkSecurityConfig mDefaultConfig;
    private Set<Pair<Domain, NetworkSecurityConfig>> mDomainMap;
    private Context mContext;

    public XmlConfigSource(Context context, int resourceId) {
        mResourceId = resourceId;
        mContext = context;
    }

    public Set<Pair<Domain, NetworkSecurityConfig>> getPerDomainConfigs() {
        ensureInitialized();
        return mDomainMap;
    }

    public NetworkSecurityConfig getDefaultConfig() {
        ensureInitialized();
        return mDefaultConfig;
    }

    private void ensureInitialized() {
        synchronized (mLock) {
            if (mInitialized) {
                return;
            }
            try (XmlResourceParser parser = mContext.getResources().getXml(mResourceId)) {
                parseNetworkSecurityConfig(parser);
                mContext = null;
                mInitialized = true;
            } catch (Resources.NotFoundException | XmlPullParserException | IOException
                    | ParserException e) {
                throw new RuntimeException("Failed to parse XML configuration from "
                        + mContext.getResources().getResourceEntryName(mResourceId), e);
            }
        }
    }

    private Pin parsePin(XmlResourceParser parser)
            throws IOException, XmlPullParserException, ParserException {
        String digestAlgorithm = parser.getAttributeValue(null, "digest");
        if (!Pin.isSupportedDigestAlgorithm(digestAlgorithm)) {
            throw new ParserException(parser, "Unsupported pin digest algorithm: "
                    + digestAlgorithm);
        }
        if (parser.next() != XmlPullParser.TEXT) {
            throw new ParserException(parser, "Missing pin digest");
        }
        String digest = parser.getText();
        byte[] decodedDigest = null;
        try {
            decodedDigest = Base64.decode(digest, 0);
        } catch (IllegalArgumentException e) {
            throw new ParserException(parser, "Invalid pin digest", e);
        }
        int expectedLength = Pin.getDigestLength(digestAlgorithm);
        if (decodedDigest.length != expectedLength) {
            throw new ParserException(parser, "digest length " + decodedDigest.length
                    + " does not match expected length for " + digestAlgorithm + " of "
                    + expectedLength);
        }
        if (parser.next() != XmlPullParser.END_TAG) {
            throw new ParserException(parser, "pin contains additional elements");
        }
        return new Pin(digestAlgorithm, decodedDigest);
    }

    private PinSet parsePinSet(XmlResourceParser parser)
            throws IOException, XmlPullParserException, ParserException {
        String expirationDate = parser.getAttributeValue(null, "expiration");
        long expirationTimestampMilis = Long.MAX_VALUE;
        if (expirationDate != null) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                sdf.setLenient(false);
                Date date = sdf.parse(expirationDate);
                if (date == null) {
                    throw new ParserException(parser, "Invalid expiration date in pin-set");
                }
                expirationTimestampMilis = date.getTime();
            } catch (ParseException e) {
                throw new ParserException(parser, "Invalid expiration date in pin-set", e);
            }
        }

        int outerDepth = parser.getDepth();
        Set<Pin> pins = new ArraySet<>();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            String tagName = parser.getName();
            if (tagName.equals("pin")) {
                pins.add(parsePin(parser));
            } else {
                XmlUtils.skipCurrentTag(parser);
            }
        }
        return new PinSet(pins, expirationTimestampMilis);
    }

    private Domain parseDomain(XmlResourceParser parser, Set<String> seenDomains)
            throws IOException, XmlPullParserException, ParserException {
        boolean includeSubdomains =
                parser.getAttributeBooleanValue(null, "includeSubdomains", false);
        if (parser.next() != XmlPullParser.TEXT) {
            throw new ParserException(parser, "Domain name missing");
        }
        String domain = parser.getText().toLowerCase(Locale.US);
        if (parser.next() != XmlPullParser.END_TAG) {
            throw new ParserException(parser, "domain contains additional elements");
        }
        // Domains are matched using a most specific match, so don't allow duplicates.
        // includeSubdomains isn't relevant here, both android.com + subdomains and android.com
        // match for android.com equally. Do not allow any duplicates period.
        if (!seenDomains.add(domain)) {
            throw new ParserException(parser, domain + " has already been specified");
        }
        return new Domain(domain, includeSubdomains);
    }

    private CertificatesEntryRef parseCertificatesEntry(XmlResourceParser parser)
            throws IOException, XmlPullParserException, ParserException {
        boolean overridePins = parser.getAttributeBooleanValue(null, "overridePins", false);
        int sourceId = parser.getAttributeResourceValue(null, "src", -1);
        String sourceString = parser.getAttributeValue(null, "src");
        CertificateSource source = null;
        if (sourceString == null) {
            throw new ParserException(parser, "certificates element missing src attribute");
        }
        if (sourceId != -1) {
            // TODO: Cache ResourceCertificateSources by sourceId
            source = new ResourceCertificateSource(sourceId, mContext);
        } else if ("system".equals(sourceString)) {
            source = SystemCertificateSource.getInstance();
        } else if ("user".equals(sourceString)) {
            source = UserCertificateSource.getInstance();
        } else {
            throw new ParserException(parser, "Unknown certificates src. "
                    + "Should be one of system|user|@resourceVal");
        }
        XmlUtils.skipCurrentTag(parser);
        return new CertificatesEntryRef(source, overridePins);
    }

    private Collection<CertificatesEntryRef> parseTrustAnchors(XmlResourceParser parser)
            throws IOException, XmlPullParserException, ParserException {
        int outerDepth = parser.getDepth();
        List<CertificatesEntryRef> anchors = new ArrayList<>();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            String tagName = parser.getName();
            if (tagName.equals("certificates")) {
                anchors.add(parseCertificatesEntry(parser));
            } else {
                XmlUtils.skipCurrentTag(parser);
            }
        }
        return anchors;
    }

    private List<Pair<NetworkSecurityConfig.Builder, Set<Domain>>> parseConfigEntry(
            XmlResourceParser parser, Set<String> seenDomains,
            NetworkSecurityConfig.Builder parentBuilder, boolean baseConfig)
            throws IOException, XmlPullParserException, ParserException {
        List<Pair<NetworkSecurityConfig.Builder, Set<Domain>>> builders = new ArrayList<>();
        NetworkSecurityConfig.Builder builder = new NetworkSecurityConfig.Builder();
        builder.setParent(parentBuilder);
        Set<Domain> domains = new ArraySet<>();
        boolean seenPinSet = false;
        boolean seenTrustAnchors = false;
        String configName = parser.getName();
        int outerDepth = parser.getDepth();
        // Add this builder now so that this builder occurs before any of its children. This
        // makes the final build pass easier.
        builders.add(new Pair<>(builder, domains));
        // Parse config attributes. Only set values that are present, config inheritence will
        // handle the rest.
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            String name = parser.getAttributeName(i);
            if ("hstsEnforced".equals(name)) {
                builder.setHstsEnforced(
                        parser.getAttributeBooleanValue(i,
                                NetworkSecurityConfig.DEFAULT_HSTS_ENFORCED));
            } else if ("cleartextTrafficPermitted".equals(name)) {
                builder.setCleartextTrafficPermitted(
                        parser.getAttributeBooleanValue(i,
                                NetworkSecurityConfig.DEFAULT_CLEARTEXT_TRAFFIC_PERMITTED));
            }
        }
        // Parse the config elements.
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            String tagName = parser.getName();
            if ("domain".equals(tagName)) {
                if (baseConfig) {
                    throw new ParserException(parser, "domain element not allowed in base-config");
                }
                Domain domain = parseDomain(parser, seenDomains);
                domains.add(domain);
            } else if ("trust-anchors".equals(tagName)) {
                if (seenTrustAnchors) {
                    throw new ParserException(parser,
                            "Multiple trust-anchor elements not allowed");
                }
                builder.addCertificatesEntryRefs(parseTrustAnchors(parser));
                seenTrustAnchors = true;
            } else if ("pin-set".equals(tagName)) {
                if (baseConfig) {
                    throw new ParserException(parser,
                            "pin-set element not allowed in base-config");
                }
                if (seenPinSet) {
                    throw new ParserException(parser, "Multiple pin-set elements not allowed");
                }
                builder.setPinSet(parsePinSet(parser));
                seenPinSet = true;
            } else if ("domain-config".equals(tagName)) {
                if (baseConfig) {
                    throw new ParserException(parser,
                            "Nested domain-config not allowed in base-config");
                }
                builders.addAll(parseConfigEntry(parser, seenDomains, builder, false));
            } else {
                XmlUtils.skipCurrentTag(parser);
            }
        }
        if (!baseConfig && domains.isEmpty()) {
            throw new ParserException(parser, "No domain elements in domain-config");
        }
        return builders;
    }

    private void parseNetworkSecurityConfig(XmlResourceParser parser)
            throws IOException, XmlPullParserException, ParserException {
        Set<String> seenDomains = new ArraySet<>();
        List<Pair<NetworkSecurityConfig.Builder, Set<Domain>>> builders = new ArrayList<>();
        NetworkSecurityConfig.Builder baseConfigBuilder = null;
        boolean seenDebugOverrides = false;
        boolean seenBaseConfig = false;

        XmlUtils.beginDocument(parser, "network-security-config");
        int outerDepth = parser.getDepth();
        while (XmlUtils.nextElementWithin(parser, outerDepth)) {
            // TODO: support debug-override.
            if ("base-config".equals(parser.getName())) {
                if (seenBaseConfig) {
                    throw new ParserException(parser, "Only one base-config allowed");
                }
                seenBaseConfig = true;
                baseConfigBuilder = parseConfigEntry(parser, seenDomains, null, true).get(0).first;
            } else if ("domain-config".equals(parser.getName())) {
                builders.addAll(parseConfigEntry(parser, seenDomains, baseConfigBuilder, false));
            } else {
                XmlUtils.skipCurrentTag(parser);
            }
        }

        // Use the platform default as the parent of the base config for any values not provided
        // there. If there is no base config use the platform default.
        NetworkSecurityConfig.Builder platformDefaultBuilder =
                NetworkSecurityConfig.getDefaultBuilder();
        if (baseConfigBuilder != null) {
            baseConfigBuilder.setParent(platformDefaultBuilder);
        } else {
            baseConfigBuilder = platformDefaultBuilder;
        }
        // Build the per-domain config mapping.
        Set<Pair<Domain, NetworkSecurityConfig>> configs = new ArraySet<>();

        for (Pair<NetworkSecurityConfig.Builder, Set<Domain>> entry : builders) {
            NetworkSecurityConfig.Builder builder = entry.first;
            Set<Domain> domains = entry.second;
            // Set the parent of configs that do not have a parent to the base-config. This can
            // happen if the base-config comes after a domain-config in the file.
            // Note that this is safe with regards to children because of the order that
            // parseConfigEntry returns builders, the parent is always before the children. The
            // children builders will not have build called until _after_ their parents have their
            // parent set so everything is consistent.
            if (builder.getParent() == null) {
                builder.setParent(baseConfigBuilder);
            }
            NetworkSecurityConfig config = builder.build();
            for (Domain domain : domains) {
                configs.add(new Pair<>(domain, config));
            }
        }
        mDefaultConfig = baseConfigBuilder.build();
        mDomainMap = configs;
    }

    public static class ParserException extends Exception {

        public ParserException(XmlPullParser parser, String message, Throwable cause) {
            super(message + " at: " + parser.getPositionDescription(), cause);
        }

        public ParserException(XmlPullParser parser, String message) {
            this(parser, message, null);
        }
    }
}
