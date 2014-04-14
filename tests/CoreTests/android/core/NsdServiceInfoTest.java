package android.core;

import android.test.AndroidTestCase;

import android.os.Bundle;
import android.os.Parcel;
import android.os.StrictMode;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.net.InetAddress;
import java.net.UnknownHostException;


public class NsdServiceInfoTest extends AndroidTestCase {

    public final static InetAddress LOCALHOST;
    static {
        // Because test.
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        InetAddress _host = null;
        try {
            _host = InetAddress.getLocalHost();
        } catch (UnknownHostException e) { }
        LOCALHOST = _host;
    }

    public void testLimits() throws Exception {
        NsdServiceInfo info = new NsdServiceInfo();

        // Non-ASCII keys.
        boolean exceptionThrown = false;
        try {
            info.setAttribute("猫", "meow");
        } catch (IllegalArgumentException e) {
            exceptionThrown = true;
        }
        assertTrue(exceptionThrown);
        assertEmptyServiceInfo(info);

        // ASCII keys with '=' character.
        exceptionThrown = false;
        try {
            info.setAttribute("kitten=", "meow");
        } catch (IllegalArgumentException e) {
            exceptionThrown = true;
        }
        assertTrue(exceptionThrown);
        assertEmptyServiceInfo(info);

        // Single key + value length too long.
        exceptionThrown = false;
        try {
            String longValue = "loooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooo" +
                    "oooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooo" +
                    "oooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooo" +
                    "ooooooooooooooooooooooooooooong";  // 248 characters.
            info.setAttribute("longcat", longValue);  // Key + value == 255 characters.
        } catch (IllegalArgumentException e) {
            exceptionThrown = true;
        }
        assertTrue(exceptionThrown);
        assertEmptyServiceInfo(info);

        // Total TXT record length too long.
        exceptionThrown = false;
        int recordsAdded = 0;
        try {
            for (int i = 100; i < 300; ++i) {
                // 6 char key + 5 char value + 2 bytes overhead = 13 byte record length.
                String key = String.format("key%d", i);
                info.setAttribute(key, "12345");
                recordsAdded++;
            }
        } catch (IllegalArgumentException e) {
            exceptionThrown = true;
        }
        assertTrue(exceptionThrown);
        assertTrue(100 == recordsAdded);
        assertTrue(info.getTxtRecord().length == 1300);
    }

    public void testParcel() throws Exception {
        NsdServiceInfo emptyInfo = new NsdServiceInfo();
        checkParcelable(emptyInfo);

        NsdServiceInfo fullInfo = new NsdServiceInfo();
        fullInfo.setServiceName("kitten");
        fullInfo.setServiceType("_kitten._tcp");
        fullInfo.setPort(4242);
        fullInfo.setHost(LOCALHOST);
        checkParcelable(fullInfo);

        NsdServiceInfo noHostInfo = new NsdServiceInfo();
        noHostInfo.setServiceName("kitten");
        noHostInfo.setServiceType("_kitten._tcp");
        noHostInfo.setPort(4242);
        checkParcelable(noHostInfo);

        NsdServiceInfo attributedInfo = new NsdServiceInfo();
        attributedInfo.setServiceName("kitten");
        attributedInfo.setServiceType("_kitten._tcp");
        attributedInfo.setPort(4242);
        attributedInfo.setHost(LOCALHOST);
        attributedInfo.setAttribute("color", "pink");
        attributedInfo.setAttribute("sound", (new String("にゃあ")).getBytes("UTF-8"));
        attributedInfo.setAttribute("adorable", (String) null);
        attributedInfo.setAttribute("sticky", "yes");
        attributedInfo.setAttribute("siblings", new byte[] {});
        attributedInfo.setAttribute("edge cases", new byte[] {0, -1, 127, -128});
        attributedInfo.removeAttribute("sticky");
        checkParcelable(attributedInfo);

        // Sanity check that we actually wrote attributes to attributedInfo.
        assertTrue(attributedInfo.getAttributes().keySet().contains("adorable"));
        String sound = new String(attributedInfo.getAttributes().get("sound"), "UTF-8");
        assertTrue(sound.equals("にゃあ"));
        byte[] edgeCases = attributedInfo.getAttributes().get("edge cases");
        assertTrue(Arrays.equals(edgeCases, new byte[] {0, -1, 127, -128}));
        assertFalse(attributedInfo.getAttributes().keySet().contains("sticky"));
    }

    public void checkParcelable(NsdServiceInfo original) {
        // Write to parcel.
        Parcel p = Parcel.obtain();
        Bundle writer = new Bundle();
        writer.putParcelable("test_info", original);
        writer.writeToParcel(p, 0);

        // Extract from parcel.
        p.setDataPosition(0);
        Bundle reader = p.readBundle();
        reader.setClassLoader(NsdServiceInfo.class.getClassLoader());
        NsdServiceInfo result = reader.getParcelable("test_info");

        // Assert equality of base fields.
        assertEquality(original.getServiceName(), result.getServiceName());
        assertEquality(original.getServiceType(), result.getServiceType());
        assertEquality(original.getHost(), result.getHost());
        assertTrue(original.getPort() == result.getPort());

        // Assert equality of attribute map.
        Map<String, byte[]> originalMap = original.getAttributes();
        Map<String, byte[]> resultMap = result.getAttributes();
        assertEquality(originalMap.keySet(), resultMap.keySet());
        for (String key : originalMap.keySet()) {
            assertTrue(Arrays.equals(originalMap.get(key), resultMap.get(key)));
        }
    }

    public void assertEquality(Object expected, Object result) {
        assertTrue(expected == result || expected.equals(result));
    }

    public void assertEmptyServiceInfo(NsdServiceInfo shouldBeEmpty) {
        assertTrue(null == shouldBeEmpty.getTxtRecord());
    }
}
