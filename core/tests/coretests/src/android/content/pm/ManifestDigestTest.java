package android.content.pm;

import android.os.Parcel;
import android.test.AndroidTestCase;
import android.util.Base64;

import java.util.jar.Attributes;

public class ManifestDigestTest extends AndroidTestCase {
    private static final byte[] DIGEST_1 = {
            (byte) 0x00, (byte) 0xAA, (byte) 0x55, (byte) 0xFF
    };

    private static final String DIGEST_1_STR = Base64.encodeToString(DIGEST_1, Base64.DEFAULT);

    private static final byte[] DIGEST_2 = {
            (byte) 0x0A, (byte) 0xA5, (byte) 0xF0, (byte) 0x5A
    };

    private static final String DIGEST_2_STR = Base64.encodeToString(DIGEST_2, Base64.DEFAULT);

    private static final Attributes.Name SHA1_DIGEST = new Attributes.Name("SHA1-Digest");

    private static final Attributes.Name MD5_DIGEST = new Attributes.Name("MD5-Digest");

    public void testManifestDigest_FromAttributes_Null() {
        assertNull("Attributes were null, so ManifestDigest.fromAttributes should return null",
                ManifestDigest.fromAttributes(null));
    }

    public void testManifestDigest_FromAttributes_NoAttributes() {
        Attributes a = new Attributes();

        assertNull("There were no attributes to extract, so ManifestDigest should be null",
                ManifestDigest.fromAttributes(a));
    }

    public void testManifestDigest_FromAttributes_SHA1PreferredOverMD5() {
        Attributes a = new Attributes();
        a.put(SHA1_DIGEST, DIGEST_1_STR);

        a.put(MD5_DIGEST, DIGEST_2_STR);

        ManifestDigest fromAttributes = ManifestDigest.fromAttributes(a);

        assertNotNull("A valid ManifestDigest should be returned", fromAttributes);

        ManifestDigest created = new ManifestDigest(DIGEST_1);

        assertEquals("SHA-1 should be preferred over MD5: " + created.toString() + " vs. "
                + fromAttributes.toString(), created, fromAttributes);

        assertEquals("Hash codes should be the same: " + created.toString() + " vs. "
                + fromAttributes.toString(), created.hashCode(), fromAttributes
                .hashCode());
    }

    public void testManifestDigest_Parcel() {
        Attributes a = new Attributes();
        a.put(SHA1_DIGEST, DIGEST_1_STR);

        ManifestDigest digest = ManifestDigest.fromAttributes(a);

        Parcel p = Parcel.obtain();
        digest.writeToParcel(p, 0);
        p.setDataPosition(0);

        ManifestDigest fromParcel = ManifestDigest.CREATOR.createFromParcel(p);

        assertEquals("ManifestDigest going through parceling should be the same as before: "
                + digest.toString() + " and " + fromParcel.toString(), digest,
                fromParcel);
    }
}
