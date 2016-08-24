package com.android.hotspot2;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;

import com.android.anqp.eap.EAP;
import com.android.hotspot2.omadm.MOTree;
import com.android.hotspot2.omadm.OMAConstants;
import com.android.hotspot2.omadm.OMAConstructed;
import com.android.hotspot2.osu.OSUManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.android.anqp.eap.NonEAPInnerAuth.NonEAPType;
import static com.android.anqp.eap.NonEAPInnerAuth.mapInnerType;

public class OMADMAdapter {
    private final Context mContext;
    private final String mImei;
    private final String mImsi;
    private final String mDevID;
    private final List<PathAccessor> mDevInfo;
    private final List<PathAccessor> mDevDetail;

    private static final int IMEI_Length = 14;

    private static final String[] ExtWiFiPath = {"DevDetail", "Ext", "org.wi-fi", "Wi-Fi"};

    private static final Map<String, String> RTProps = new HashMap<>();

    private MOTree mDevInfoTree;
    private MOTree mDevDetailTree;

    private static OMADMAdapter sInstance;

    static {
        RTProps.put(ExtWiFiPath[2], "urn:wfa:mo-ext:hotspot2dot0-devdetail-ext:1.0");
    }

    private static abstract class PathAccessor {
        private final String[] mPath;
        private final int mHashCode;

        protected PathAccessor(Object... path) {
            int length = 0;
            for (Object o : path) {
                if (o.getClass() == String[].class) {
                    length += ((String[]) o).length;
                } else {
                    length++;
                }
            }
            mPath = new String[length];
            int n = 0;
            for (Object o : path) {
                if (o.getClass() == String[].class) {
                    for (String element : (String[]) o) {
                        mPath[n++] = element;
                    }
                } else if (o.getClass() == Integer.class) {
                    mPath[n++] = "x" + o.toString();
                } else {
                    mPath[n++] = o.toString();
                }
            }
            mHashCode = Arrays.hashCode(mPath);
        }

        @Override
        public int hashCode() {
            return mHashCode;
        }

        @Override
        public boolean equals(Object thatObject) {
            return thatObject == this || (thatObject instanceof ConstPathAccessor &&
                    Arrays.equals(mPath, ((PathAccessor) thatObject).mPath));
        }

        private String[] getPath() {
            return mPath;
        }

        protected abstract Object getValue();
    }

    private static class ConstPathAccessor<T> extends PathAccessor {
        private final T mValue;

        protected ConstPathAccessor(T value, Object... path) {
            super(path);
            mValue = value;
        }

        protected Object getValue() {
            return mValue;
        }
    }

    public static OMADMAdapter getInstance(Context context) {
        synchronized (OMADMAdapter.class) {
            if (sInstance == null) {
                sInstance = new OMADMAdapter(context);
            }
            return sInstance;
        }
    }

    private OMADMAdapter(Context context) {
        mContext = context;

        TelephonyManager tm = (TelephonyManager) context
                .getSystemService(Context.TELEPHONY_SERVICE);
        String simOperator = tm.getSimOperator();
        mImsi = tm.getSubscriberId();
        mImei = tm.getImei();
        String strDevId;

        /* Use MEID for sprint */
        if ("310120".equals(simOperator) || (mImsi != null && mImsi.startsWith("310120"))) {
                /* MEID is 14 digits. If IMEI is returned as DevId, MEID can be extracted by taking
                 * first 14 characters. This is not always true but should be the case for sprint */
            strDevId = tm.getDeviceId().toUpperCase(Locale.US);
            if (strDevId != null && strDevId.length() >= IMEI_Length) {
                strDevId = strDevId.substring(0, IMEI_Length);
            } else {
                Log.w(OSUManager.TAG, "MEID cannot be extracted from DeviceId " + strDevId);
            }
        } else {
            if (isPhoneTypeLTE()) {
                strDevId = mImei;
            } else {
                strDevId = tm.getDeviceId();
            }
            if (strDevId == null) {
                strDevId = "unknown";
            }
            strDevId = strDevId.toUpperCase(Locale.US);

            if (!isPhoneTypeLTE()) {
                strDevId = strDevId.substring(0, IMEI_Length);
            }
        }
        mDevID = strDevId;

        mDevInfo = new ArrayList<>();
        mDevInfo.add(new ConstPathAccessor<>(strDevId, "DevInfo", "DevID"));
        mDevInfo.add(new ConstPathAccessor<>(getProperty(context,
                "Man", "ro.product.manufacturer", "unknown"), "DevInfo", "Man"));
        mDevInfo.add(new ConstPathAccessor<>(getProperty(context,
                "Mod", "ro.product.model", "generic"), "DevInfo", "Mod"));
        mDevInfo.add(new ConstPathAccessor<>(getLocale(context), "DevInfo", "Lang"));
        mDevInfo.add(new ConstPathAccessor<>("1.2", "DevInfo", "DmV"));

        mDevDetail = new ArrayList<>();
        mDevDetail.add(new ConstPathAccessor<>(getDeviceType(), "DevDetail", "DevType"));
        mDevDetail.add(new ConstPathAccessor<>(SystemProperties.get("ro.product.brand"),
                "DevDetail", "OEM"));
        mDevDetail.add(new ConstPathAccessor<>(getVersion(context, false), "DevDetail", "FwV"));
        mDevDetail.add(new ConstPathAccessor<>(getVersion(context, true), "DevDetail", "SwV"));
        mDevDetail.add(new ConstPathAccessor<>(getHwV(), "DevDetail", "HwV"));
        mDevDetail.add(new ConstPathAccessor<>("TRUE", "DevDetail", "LrgObj"));

        mDevDetail.add(new ConstPathAccessor<>(32, "DevDetail", "URI", "MaxDepth"));
        mDevDetail.add(new ConstPathAccessor<>(2048, "DevDetail", "URI", "MaxTotLen"));
        mDevDetail.add(new ConstPathAccessor<>(64, "DevDetail", "URI", "MaxSegLen"));

        AtomicInteger index = new AtomicInteger(1);
        mDevDetail.add(new ConstPathAccessor<>(EAP.EAP_TTLS, ExtWiFiPath,
                "EAPMethodList", index, "EAPType"));
        mDevDetail.add(new ConstPathAccessor<>(mapInnerType(NonEAPType.MSCHAPv2), ExtWiFiPath,
                "EAPMethodList", index, "InnerMethod"));

        index.incrementAndGet();
        mDevDetail.add(new ConstPathAccessor<>(EAP.EAP_TTLS, ExtWiFiPath,
                "EAPMethodList", index, "EAPType"));
        mDevDetail.add(new ConstPathAccessor<>(mapInnerType(NonEAPType.PAP), ExtWiFiPath,
                "EAPMethodList", index, "InnerMethod"));

        index.incrementAndGet();
        mDevDetail.add(new ConstPathAccessor<>(EAP.EAP_TTLS, ExtWiFiPath,
                "EAPMethodList", index, "EAPType"));
        mDevDetail.add(new ConstPathAccessor<>(mapInnerType(NonEAPType.MSCHAP), ExtWiFiPath,
                "EAPMethodList", index, "InnerMethod"));

        index.incrementAndGet();
        mDevDetail.add(new ConstPathAccessor<>(EAP.EAP_TLS, ExtWiFiPath,
                "EAPMethodList", index, "EAPType"));
        index.incrementAndGet();
        mDevDetail.add(new ConstPathAccessor<>(EAP.EAP_AKA, ExtWiFiPath,
                "EAPMethodList", index, "EAPType"));
        index.incrementAndGet();
        mDevDetail.add(new ConstPathAccessor<>(EAP.EAP_AKAPrim, ExtWiFiPath,
                "EAPMethodList", index, "EAPType"));
        index.incrementAndGet();
        mDevDetail.add(new ConstPathAccessor<>(EAP.EAP_SIM, ExtWiFiPath,
                "EAPMethodList", index, "EAPType"));

        mDevDetail.add(new ConstPathAccessor<>("FALSE", ExtWiFiPath, "ManufacturingCertificate"));
        mDevDetail.add(new ConstPathAccessor<>(mImsi, ExtWiFiPath, "IMSI"));
        mDevDetail.add(new ConstPathAccessor<>(mImei, ExtWiFiPath, "IMEI_MEID"));
        mDevDetail.add(new PathAccessor(ExtWiFiPath, "Wi-FiMACAddress") {
            @Override
            protected String getValue() {
                return getMAC();
            }
        });
    }

    private static void buildNode(PathAccessor pathAccessor, int depth, OMAConstructed parent)
            throws IOException {
        String[] path = pathAccessor.getPath();
        String name = path[depth];
        if (depth < path.length - 1) {
            OMAConstructed node = (OMAConstructed) parent.getChild(name);
            if (node == null) {
                node = (OMAConstructed) parent.addChild(name, RTProps.get(name),
                        null, null);
            }
            buildNode(pathAccessor, depth + 1, node);
        } else if (pathAccessor.getValue() != null) {
            parent.addChild(name, null, pathAccessor.getValue().toString(), null);
        }
    }

    public String getMAC() {
        WifiManager wifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        return wifiManager != null ?
                String.format("%012x",
                        Utils.parseMac(wifiManager.getConnectionInfo().getMacAddress())) :
                null;
    }

    public String getImei() {
        return mImei;
    }

    public byte[] getMeid() {
        return Arrays.copyOf(mImei.getBytes(StandardCharsets.ISO_8859_1), IMEI_Length);
    }

    public String getDevID() {
        return mDevID;
    }

    public MOTree getMO(String urn) {
        try {
            switch (urn) {
                case OMAConstants.DevInfoURN:
                    if (mDevInfoTree == null) {
                        OMAConstructed root = new OMAConstructed(null, "DevInfo", urn);
                        for (PathAccessor pathAccessor : mDevInfo) {
                            buildNode(pathAccessor, 1, root);
                        }
                        mDevInfoTree = MOTree.buildMgmtTree(OMAConstants.DevInfoURN,
                                OMAConstants.OMAVersion, root);
                    }
                    return mDevInfoTree;
                case OMAConstants.DevDetailURN:
                    if (mDevDetailTree == null) {
                        OMAConstructed root = new OMAConstructed(null, "DevDetail", urn);
                        for (PathAccessor pathAccessor : mDevDetail) {
                            buildNode(pathAccessor, 1, root);
                        }
                        mDevDetailTree = MOTree.buildMgmtTree(OMAConstants.DevDetailURN,
                                OMAConstants.OMAVersion, root);
                    }
                    return mDevDetailTree;
                default:
                    throw new IllegalArgumentException(urn);
            }
        } catch (IOException ioe) {
            Log.e(OSUManager.TAG, "Caught exception building OMA Tree: " + ioe, ioe);
            return null;
        }

        /*
        switch (urn) {
            case DevInfoURN: return DevInfo;
            case DevDetailURN: return DevDetail;
            default: throw new IllegalArgumentException(urn);
        }
        */
    }

    // TODO: For now, assume the device supports LTE.
    private static boolean isPhoneTypeLTE() {
        return true;
    }

    private static String getHwV() {
        try {
            return SystemProperties.get("ro.hardware", "Unknown")
                    + "." + SystemProperties.get("ro.revision", "Unknown");
        } catch (RuntimeException e) {
            return "Unknown";
        }
    }

    private static String getDeviceType() {
        String devicetype = SystemProperties.get("ro.build.characteristics");
        if ((((TextUtils.isEmpty(devicetype)) || (!devicetype.equals("tablet"))))) {
            devicetype = "phone";
        }
        return devicetype;
    }

    private static String getVersion(Context context, boolean swv) {
        String version;
        try {
            if (!isSprint(context) && swv) {
                return "Android " + SystemProperties.get("ro.build.version.release");
            } else {
                version = SystemProperties.get("ro.build.version.full");
                if (null == version || version.equals("")) {
                    return SystemProperties.get("ro.build.id", null) + "~"
                            + SystemProperties.get("ro.build.config.version", null) + "~"
                            + SystemProperties.get("gsm.version.baseband", null) + "~"
                            + SystemProperties.get("ro.gsm.flexversion", null);
                }
            }
        } catch (RuntimeException e) {
            return "Unknown";
        }
        return version;
    }

    private static boolean isSprint(Context context) {
        TelephonyManager tm = (TelephonyManager) context
                .getSystemService(Context.TELEPHONY_SERVICE);
        String simOperator = tm.getSimOperator();
        String imsi = tm.getSubscriberId();
        /* Use MEID for sprint */
        if ("310120".equals(simOperator) || (imsi != null && imsi.startsWith("310120"))) {
            return true;
        } else {
            return false;
        }
    }

    private static String getLocale(Context context) {
        String strLang = readValueFromFile(context, "Lang");
        if (strLang == null) {
            strLang = Locale.getDefault().toString();
        }
        return strLang;
    }

    private static String getProperty(Context context, String key, String propKey, String dflt) {
        String strMan = readValueFromFile(context, key);
        if (strMan == null) {
            strMan = SystemProperties.get(propKey, dflt);
        }
        return strMan;
    }

    private static String readValueFromFile(Context context, String propName) {
        String ret = null;
        // use preference instead of the system property
        SharedPreferences prefs = context.getSharedPreferences("dmconfig", 0);
        if (prefs.contains(propName)) {
            ret = prefs.getString(propName, "");
            if (ret.length() == 0) {
                ret = null;
            }
        }
        return ret;
    }

    private static final String DevDetail =
            "<MgmtTree>" +
                    "<VerDTD>1.2</VerDTD>" +
                    "<Node>" +
                    "<NodeName>DevDetail</NodeName>" +
                    "<RTProperties>" +
                    "<Type>" +
                    "<DDFName>urn:oma:mo:oma-dm-devdetail:1.0</DDFName>" +
                    "</Type>" +
                    "</RTProperties>" +
                    "<Node>" +
                    "<NodeName>Ext</NodeName>" +
                    "<Node>" +
                    "<NodeName>org.wi-fi</NodeName>" +
                    "<RTProperties>" +
                    "<Type>" +
                    "<DDFName>" +
                    "urn:wfa:mo-ext:hotspot2dot0-devdetail-ext :1.0" +
                    "</DDFName>" +
                    "</Type>" +
                    "</RTProperties>" +
                    "<Node>" +
                    "<NodeName>Wi-Fi</NodeName>" +
                    "<Node>" +
                    "<NodeName>EAPMethodList</NodeName>" +
                    "<Node>" +
                    "<NodeName>Method01</NodeName>" +
                    "<!-- EAP-TTLS/MS-CHAPv2 -->" +
                    "<Node>" +
                    "<NodeName>EAPType</NodeName>" +
                    "<Value>21</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>InnerMethod</NodeName>" +
                    "<Value>MS-CHAP-V2</Value>" +
                    "</Node>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>Method02</NodeName>" +
                    "<!-- EAP-TLS -->" +
                    "<Node>" +
                    "<NodeName>EAPType</NodeName>" +
                    "<Value>13</Value>" +
                    "</Node>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>Method03</NodeName>" +
                    "<!-- EAP-SIM -->" +
                    "<Node>" +
                    "<NodeName>EAPType</NodeName>" +
                    "<Value>18</Value>" +
                    "</Node>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>Method04</NodeName>" +
                    "<!-- EAP-AKA -->" +
                    "<Node>" +
                    "<NodeName>EAPType</NodeName>" +
                    "<Value>23</Value>" +
                    "</Node>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>Method05</NodeName>" +
                    "<!-- EAP-AKA' -->" +
                    "<Node>" +
                    "<NodeName>EAPType</NodeName>" +
                    "<Value>50</Value>" +
                    "</Node>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>Method06</NodeName>" +
                    "<!-- Supported method (EAP-TTLS/PAP) not mandated by Hotspot2.0-->" +
                    "<Node>" +
                    "<NodeName>EAPType</NodeName>" +
                    "<Value>21</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>InnerMethod</NodeName>" +
                    "<Value>PAP</Value>" +
                    "</Node>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>Method07</NodeName>" +
                    "<!-- Supported method (PEAP/EAP-GTC) not mandated by Hotspot 2.0-->" +
                    "<Node>" +
                    "<NodeName>EAPType</NodeName>" +
                    "<Value>25</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>InnerEAPType</NodeName>" +
                    "<Value>6</Value>" +
                    "</Node>" +
                    "</Node>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>SPCertificate</NodeName>" +
                    "<Node>" +
                    "<NodeName>Cert01</NodeName>" +
                    "<Node>" +
                    "<NodeName>CertificateIssuerName</NodeName>" +
                    "<Value>CN=RuckusCA</Value>" +
                    "</Node>" +
                    "</Node>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>ManufacturingCertificate</NodeName>" +
                    "<Value>FALSE</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>Wi-FiMACAddress</NodeName>" +
                    "<Value>001d2e112233</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>ClientTriggerRedirectURI</NodeName>" +
                    "<Value>http://127.0.0.1:12345/index.htm</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>Ops</NodeName>" +
                    "<Node>" +
                    "<NodeName>launchBrowserToURI</NodeName>" +
                    "<Value></Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>negotiateClientCertTLS</NodeName>" +
                    "<Value></Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>getCertificate</NodeName>" +
                    "<Value></Value>" +
                    "</Node>" +
                    "</Node>" +
                    "</Node>" +
                    "<!-- End of Wi-Fi node -->" +
                    "</Node>" +
                    "<!-- End of org.wi-fi node -->" +
                    "</Node>" +
                    "<!-- End of Ext node -->" +
                    "<Node>" +
                    "<NodeName>URI</NodeName>" +
                    "<Node>" +
                    "<NodeName>MaxDepth</NodeName>" +
                    "<Value>32</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>MaxTotLen</NodeName>" +
                    "<Value>2048</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>MaxSegLen</NodeName>" +
                    "<Value>64</Value>" +
                    "</Node>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>DevType</NodeName>" +
                    "<Value>Smartphone</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>OEM</NodeName>" +
                    "<Value>ACME</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>FwV</NodeName>" +
                    "<Value>1.2.100.5</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>SwV</NodeName>" +
                    "<Value>9.11.130</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>HwV</NodeName>" +
                    "<Value>1.0</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>LrgObj</NodeName>" +
                    "<Value>TRUE</Value>" +
                    "</Node>" +
                    "</Node>" +
                    "</MgmtTree>";


    private static final String DevInfo =
            "<MgmtTree>" +
                    "<VerDTD>1.2</VerDTD>" +
                    "<Node>" +
                    "<NodeName>DevInfo</NodeName>" +
                    "<RTProperties>" +
                    "<Type>" +
                    "<DDFName>urn:oma:mo:oma-dm-devinfo:1.0" +
                    "</DDFName>" +
                    "</Type>" +
                    "</RTProperties>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>DevID</NodeName>" +
                    "<Path>DevInfo</Path>" +
                    "<Value>urn:acme:00-11-22-33-44-55</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>Man</NodeName>" +
                    "<Path>DevInfo</Path>" +
                    "<Value>ACME</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>Mod</NodeName>" +
                    "<Path>DevInfo</Path>" +
                    "<Value>HS2.0-01</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>DmV</NodeName>" +
                    "<Path>DevInfo</Path>" +
                    "<Value>1.2</Value>" +
                    "</Node>" +
                    "<Node>" +
                    "<NodeName>Lang</NodeName>" +
                    "<Path>DevInfo</Path>" +
                    "<Value>en-US</Value>" +
                    "</Node>" +
                    "</MgmtTree>";
}
