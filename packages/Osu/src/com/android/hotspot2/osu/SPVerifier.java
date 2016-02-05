package com.android.hotspot2.osu;

import android.util.Log;

import com.android.anqp.HSIconFileElement;
import com.android.anqp.I18Name;
import com.android.anqp.IconInfo;
import com.android.hotspot2.Utils;
import com.android.hotspot2.asn1.Asn1Class;
import com.android.hotspot2.asn1.Asn1Constructed;
import com.android.hotspot2.asn1.Asn1Decoder;
import com.android.hotspot2.asn1.Asn1Integer;
import com.android.hotspot2.asn1.Asn1Object;
import com.android.hotspot2.asn1.Asn1Octets;
import com.android.hotspot2.asn1.Asn1Oid;
import com.android.hotspot2.asn1.Asn1String;
import com.android.hotspot2.asn1.OidMappings;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SPVerifier {
    public static final int OtherName = 0;
    public static final int DNSName = 2;

    private final OSUInfo mOSUInfo;

    public SPVerifier(OSUInfo osuInfo) {
        mOSUInfo = osuInfo;
    }

    /*
    SEQUENCE:
      [Context 0]:
        SEQUENCE:
          [Context 0]:                      -- LogotypeData
            SEQUENCE:
              SEQUENCE:
                SEQUENCE:
                  IA5String='image/png'
                  SEQUENCE:
                    SEQUENCE:
                      SEQUENCE:
                        OID=2.16.840.1.101.3.4.2.1
                        NULL
                      OCTET_STRING= cf aa 74 a8 ad af 85 82 06 c8 f5 b5 bf ee 45 72 8a ee ea bd 47 ab 50 d3 62 0c 92 c1 53 c3 4c 6b
                  SEQUENCE:
                    IA5String='http://www.r2-testbed.wi-fi.org/icon_orange_zxx.png'
                SEQUENCE:
                  INTEGER=4184
                  INTEGER=-128
                  INTEGER=61
                  [Context 4]= 7a 78 78
          [Context 0]:                      -- LogotypeData
            SEQUENCE:
              SEQUENCE:                     -- LogotypeImage
                SEQUENCE:                   -- LogoTypeDetails
                  IA5String='image/png'
                  SEQUENCE:
                    SEQUENCE:               -- HashAlgAndValue
                      SEQUENCE:
                        OID=2.16.840.1.101.3.4.2.1
                        NULL
                      OCTET_STRING= cb 35 5c ba 7a 21 59 df 8e 0a e1 d8 9f a4 81 9e 41 8f af 58 0c 08 d6 28 7f 66 22 98 13 57 95 8d
                  SEQUENCE:
                    IA5String='http://www.r2-testbed.wi-fi.org/icon_orange_eng.png'
                SEQUENCE:                   -- LogotypeImageInfo
                  INTEGER=11635
                  INTEGER=-96
                  INTEGER=76
                  [Context 4]= 65 6e 67
     */

    private static class LogoTypeImage {
        private final String mMimeType;
        private final List<HashAlgAndValue> mHashes = new ArrayList<>();
        private final List<String> mURIs = new ArrayList<>();
        private final int mFileSize;
        private final int mXsize;
        private final int mYsize;
        private final String mLanguage;

        private LogoTypeImage(Asn1Constructed sequence) throws IOException {
            Iterator<Asn1Object> children = sequence.getChildren().iterator();

            Iterator<Asn1Object> logoTypeDetails =
                    castObject(children.next(), Asn1Constructed.class).getChildren().iterator();
            mMimeType = castObject(logoTypeDetails.next(), Asn1String.class).getString();

            Asn1Constructed hashes = castObject(logoTypeDetails.next(), Asn1Constructed.class);
            for (Asn1Object hash : hashes.getChildren()) {
                mHashes.add(new HashAlgAndValue(castObject(hash, Asn1Constructed.class)));
            }
            Asn1Constructed urls = castObject(logoTypeDetails.next(), Asn1Constructed.class);
            for (Asn1Object url : urls.getChildren()) {
                mURIs.add(castObject(url, Asn1String.class).getString());
            }

            boolean imageInfoSet = false;
            int fileSize = -1;
            int xSize = -1;
            int ySize = -1;
            String language = null;

            if (children.hasNext()) {
                Iterator<Asn1Object> imageInfo =
                        castObject(children.next(), Asn1Constructed.class).getChildren().iterator();

                Asn1Object first = imageInfo.next();
                if (first.getTag() == 0) {
                    first = imageInfo.next();   // Ignore optional LogotypeImageType
                }

                fileSize = (int) castObject(first, Asn1Integer.class).getValue();
                xSize = (int) castObject(imageInfo.next(), Asn1Integer.class).getValue();
                ySize = (int) castObject(imageInfo.next(), Asn1Integer.class).getValue();
                imageInfoSet = true;

                if (imageInfo.hasNext()) {
                    Asn1Object next = imageInfo.next();
                    if (next.getTag() != 4) {
                        next = imageInfo.hasNext() ? imageInfo.next() : null;   // Skip resolution
                    }
                    if (next != null && next.getTag() == 4) {
                        language = new String(castObject(next, Asn1Octets.class).getOctets(),
                                StandardCharsets.US_ASCII);
                    }
                }
            }

            if (imageInfoSet) {
                mFileSize = complement(fileSize);
                mXsize = complement(xSize);
                mYsize = complement(ySize);
            } else {
                mFileSize = mXsize = mYsize = -1;
            }
            mLanguage = language;
        }

        private boolean verify(OSUInfo osuInfo) throws GeneralSecurityException, IOException {
            IconInfo iconInfo = osuInfo.getIconInfo();
            HSIconFileElement iconData = osuInfo.getIconFileElement();
            if (!iconInfo.getIconType().equals(mMimeType) ||
                    !iconInfo.getLanguage().equals(mLanguage) ||
                    iconData.getIconData().length != mFileSize) {
                return false;
            }
            for (HashAlgAndValue hash : mHashes) {
                if (hash.getJCEName() != null) {
                    MessageDigest digest = MessageDigest.getInstance(hash.getJCEName());
                    byte[] computed = digest.digest(iconData.getIconData());
                    if (!Arrays.equals(computed, hash.getHash())) {
                        throw new IOException("Icon hash mismatch");
                    } else {
                        Log.d(OSUManager.TAG, "Icon verified with " + hash.getJCEName());
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public String toString() {
            return "LogoTypeImage{" +
                    "MimeType='" + mMimeType + '\'' +
                    ", hashes=" + mHashes +
                    ", URIs=" + mURIs +
                    ", fileSize=" + mFileSize +
                    ", xSize=" + mXsize +
                    ", ySize=" + mYsize +
                    ", language='" + mLanguage + '\'' +
                    '}';
        }
    }

    private static class HashAlgAndValue {
        private final String mJCEName;
        private final byte[] mHash;

        private HashAlgAndValue(Asn1Constructed sequence) throws IOException {
            if (sequence.getChildren().size() != 2) {
                throw new IOException("Bad HashAlgAndValue");
            }
            Iterator<Asn1Object> children = sequence.getChildren().iterator();
            mJCEName = OidMappings.getJCEName(getFirstInner(children.next(), Asn1Oid.class));
            mHash = castObject(children.next(), Asn1Octets.class).getOctets();
        }

        public String getJCEName() {
            return mJCEName;
        }

        public byte[] getHash() {
            return mHash;
        }

        @Override
        public String toString() {
            return "HashAlgAndValue{" +
                    "JCEName='" + mJCEName + '\'' +
                    ", hash=" + Utils.toHex(mHash) +
                    '}';
        }
    }

    private static int complement(int value) {
        return value >= 0 ? value : (~value) + 1;
    }

    private static <T extends Asn1Object> T castObject(Asn1Object object, Class<T> klass)
            throws IOException {
        if (object.getClass() != klass) {
            throw new IOException("Object is an " + object.getClass().getSimpleName() +
                    " expected an " + klass.getSimpleName());
        }
        return klass.cast(object);
    }

    private static <T extends Asn1Object> T getFirstInner(Asn1Object container, Class<T> klass)
            throws IOException {
        if (container.getClass() != Asn1Constructed.class) {
            throw new IOException("Not a container");
        }
        Iterator<Asn1Object> children = container.getChildren().iterator();
        if (!children.hasNext()) {
            throw new IOException("No content");
        }
        return castObject(children.next(), klass);
    }

    public void verify(X509Certificate osuCert) throws IOException, GeneralSecurityException {
        if (osuCert == null) {
            throw new IOException("No OSU cert found");
        }

        checkName(castObject(getExtension(osuCert, OidMappings.IdCeSubjectAltName),
                Asn1Constructed.class));

        List<LogoTypeImage> logos = getImageData(getExtension(osuCert, OidMappings.IdPeLogotype));
        Log.d(OSUManager.TAG, "Logos: " + logos);
        for (LogoTypeImage logoTypeImage : logos) {
            if (logoTypeImage.verify(mOSUInfo)) {
                return;
            }
        }
        throw new IOException("Failed to match icon against any cert logo");
    }

    private static List<LogoTypeImage> getImageData(Asn1Object logoExtension) throws IOException {
        Asn1Constructed logo = castObject(logoExtension, Asn1Constructed.class);
        Asn1Constructed communityLogo = castObject(logo.getChildren().iterator().next(),
                Asn1Constructed.class);
        if (communityLogo.getTag() != 0) {
            throw new IOException("Expected tag [0] for communityLogos");
        }

        List<LogoTypeImage> images = new ArrayList<>();
        Asn1Constructed communityLogoSeq = castObject(communityLogo.getChildren().iterator().next(),
                Asn1Constructed.class);
        for (Asn1Object logoTypeData : communityLogoSeq.getChildren()) {
            if (logoTypeData.getTag() != 0) {
                throw new IOException("Expected tag [0] for LogotypeData");
            }
            for (Asn1Object logoTypeImage : castObject(logoTypeData.getChildren().iterator().next(),
                    Asn1Constructed.class).getChildren()) {
                // only read the image SEQUENCE and skip any audio [1] tags
                if (logoTypeImage.getAsn1Class() == Asn1Class.Universal) {
                    images.add(new LogoTypeImage(castObject(logoTypeImage, Asn1Constructed.class)));
                }
            }
        }
        return images;
    }

    private void checkName(Asn1Constructed altName) throws IOException {
        Map<String, I18Name> friendlyNames = new HashMap<>();
        for (Asn1Object name : altName.getChildren()) {
            if (name.getAsn1Class() == Asn1Class.Context && name.getTag() == OtherName) {
                Asn1Constructed otherName = (Asn1Constructed) name;
                Iterator<Asn1Object> children = otherName.getChildren().iterator();
                if (children.hasNext()) {
                    Asn1Object oidObject = children.next();
                    if (OidMappings.sIdWfaHotspotFriendlyName.equals(oidObject) &&
                            children.hasNext()) {
                        Asn1Constructed value = castObject(children.next(), Asn1Constructed.class);
                        String text = castObject(value.getChildren().iterator().next(),
                                Asn1String.class).getString();
                        I18Name friendlyName = new I18Name(text);
                        friendlyNames.put(friendlyName.getLanguage(), friendlyName);
                    }
                }
            }
        }
        Log.d(OSUManager.TAG, "Friendly names: " + friendlyNames.values());
        for (I18Name osuName : mOSUInfo.getOSUProvider().getNames()) {
            I18Name friendlyName = friendlyNames.get(osuName.getLanguage());
            if (!osuName.equals(friendlyName)) {
                throw new IOException("Friendly name '" + osuName + " not in certificate");
            }
        }
    }

    private static Asn1Object getExtension(X509Certificate certificate, String extension)
            throws GeneralSecurityException, IOException {
        byte[] data = certificate.getExtensionValue(extension);
        if (data == null) {
            return null;
        }
        Asn1Octets octetString = (Asn1Octets) Asn1Decoder.decode(ByteBuffer.wrap(data)).
                iterator().next();
        Asn1Constructed sequence = castObject(Asn1Decoder.decode(
                        ByteBuffer.wrap(octetString.getOctets())).iterator().next(),
                Asn1Constructed.class);
        Log.d(OSUManager.TAG, "Extension " + extension + ": " + sequence);
        return sequence;
    }
}
