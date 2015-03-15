package android.net.wifi.hotspot2.pps;

import android.net.wifi.anqp.ANQPElement;
import android.net.wifi.anqp.DomainNameElement;
import android.net.wifi.anqp.HSConnectionCapabilityElement;
import android.net.wifi.anqp.HSWanMetricsElement;
import android.net.wifi.anqp.IPAddressTypeAvailabilityElement;
import android.net.wifi.anqp.NAIRealmData;
import android.net.wifi.anqp.NAIRealmElement;
import android.net.wifi.anqp.RoamingConsortiumElement;
import android.net.wifi.anqp.ThreeGPPNetworkElement;
import android.net.wifi.anqp.eap.EAP;
import android.net.wifi.anqp.eap.EAPMethod;
import android.net.wifi.hotspot2.NetworkInfo;
import android.net.wifi.hotspot2.PasspointMatch;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static android.net.wifi.anqp.Constants.ANQPElementType;

/**
 * Created by jannq on 1/20/15.
 */
public class HomeSP {
    private final Map<String, String> mSSIDs;        // SSID, HESSID, [0,N]
    private final DomainMatcher mDomainMatcher;
    private final Set<Long> mRoamingConsortiums;    // [0,N]
    private final Set<Long> mMatchAnyOIs;           // [0,N]
    private final List<Long> mMatchAllOIs;          // [0,N]

    private final Map<EAP.EAPMethodID, EAPMethod> mCredentials;

    // Informational:
    private final String mFriendlyName;             // [1]
    private final String mIconURL;                  // [0,1]

    public HomeSP(Map<String, String> ssidMap,
                   /*@NotNull*/ String fqdn,
                   /*@NotNull*/ Set<Long> roamingConsortiums,
                   /*@NotNull*/ Set<String> otherHomePartners,
                   /*@NotNull*/ Set<Long> matchAnyOIs,
                   /*@NotNull*/ List<Long> matchAllOIs,
                   String friendlyName,
                   String iconURL,
                   Map<EAP.EAPMethodID, EAPMethod> credentials) {

        mSSIDs = ssidMap;
        List<List<String>> otherPartners = new ArrayList<List<String>>(otherHomePartners.size());
        for (String otherPartner : otherHomePartners) {
            otherPartners.add(splitDomain(otherPartner));
        }
        mDomainMatcher = new DomainMatcher(splitDomain(fqdn), otherPartners);
        mRoamingConsortiums = roamingConsortiums;
        mMatchAnyOIs = matchAnyOIs;
        mMatchAllOIs = matchAllOIs;
        mFriendlyName = friendlyName;
        mIconURL = iconURL;
        mCredentials = credentials;
    }

    public PasspointMatch match(NetworkInfo networkInfo, List<ANQPElement> anqpElements) {

        if (mSSIDs.containsKey(networkInfo.getSSID())) {
            String hessid = mSSIDs.get(networkInfo.getSSID());
            if (hessid == null || networkInfo.getHESSID().equals(hessid)) {
                return PasspointMatch.HomeProvider;
            }
        }

        List<Long> allOIs = null;

        if (networkInfo.getRoamingConsortiums() != null) {
            allOIs = new ArrayList<Long>();
            for (long oi : networkInfo.getRoamingConsortiums()) {
                allOIs.add(oi);
            }
        }

        Map<ANQPElementType, ANQPElement> anqpElementMap = null;

        if (anqpElements != null) {
            anqpElementMap = new EnumMap<ANQPElementType, ANQPElement>(ANQPElementType.class);
            for (ANQPElement element : anqpElements) {
                anqpElementMap.put(element.getID(), element);
                if (element.getID() == ANQPElementType.ANQPRoamingConsortium) {
                    RoamingConsortiumElement rcElement = (RoamingConsortiumElement) element;
                    if (!rcElement.getOIs().isEmpty()) {
                        if (allOIs == null) {
                            allOIs = new ArrayList<Long>(rcElement.getOIs());
                        } else {
                            allOIs.addAll(rcElement.getOIs());
                        }
                    }
                }
            }
        }

        if (allOIs != null) {
            if (!mRoamingConsortiums.isEmpty()) {
                for (long oi : allOIs) {
                    if (mRoamingConsortiums.contains(oi)) {
                        return PasspointMatch.HomeProvider;
                    }
                }
            }
            if (!mMatchAnyOIs.isEmpty() || !mMatchAllOIs.isEmpty()) {
                for (long anOI : allOIs) {

                    boolean oneMatchesAll = true;

                    for (long spOI : mMatchAllOIs) {
                        if (spOI != anOI) {
                            oneMatchesAll = false;
                            break;
                        }
                    }

                    if (oneMatchesAll) {
                        return PasspointMatch.HomeProvider;
                    }

                    if (mMatchAnyOIs.contains(anOI)) {
                        return PasspointMatch.HomeProvider;
                    }
                }
            }
        }

        if (anqpElementMap == null) {
            return PasspointMatch.Incomplete;
        }

        DomainNameElement domainNameElement =
                (DomainNameElement) anqpElementMap.get(ANQPElementType.ANQPDomName);
        NAIRealmElement naiRealmElement =
                (NAIRealmElement) anqpElementMap.get(ANQPElementType.ANQPNAIRealm);
        ThreeGPPNetworkElement threeGPPNetworkElement =
                (ThreeGPPNetworkElement) anqpElementMap.get(ANQPElementType.ANQP3GPPNetwork);

        // For future policy decisions:
        IPAddressTypeAvailabilityElement ipAddressAvailabilityElement =
                (IPAddressTypeAvailabilityElement) anqpElementMap.get(
                        ANQPElementType.ANQPIPAddrAvailability);
        HSConnectionCapabilityElement hsConnCapElement =
                (HSConnectionCapabilityElement) anqpElementMap.get(
                        ANQPElementType.HSConnCapability);
        HSWanMetricsElement hsWanMetricsElement =
                (HSWanMetricsElement) anqpElementMap.get(ANQPElementType.HSWANMetrics);

        if (domainNameElement != null) {
            for (String domain : domainNameElement.getDomains()) {
                DomainMatcher.Match match = mDomainMatcher.isSubDomain(splitDomain(domain));
                if (match != DomainMatcher.Match.None) {
                    return PasspointMatch.HomeProvider;
                }
            }
        }

        /*
        if ( threeGPPNetworkElement != null ) {
            !!! Insert matching based on 3GPP credentials here
        }
        */

        if (naiRealmElement != null) {

            for (NAIRealmData naiRealmData : naiRealmElement.getRealmData()) {

                DomainMatcher.Match match = DomainMatcher.Match.None;
                for (String anRealm : naiRealmData.getRealms()) {
                    match = mDomainMatcher.isSubDomain(splitDomain(anRealm));
                    if (match != DomainMatcher.Match.None) {
                        break;
                    }
                }
                if (match != DomainMatcher.Match.None) {
                    if (mCredentials == null) {
                        return PasspointMatch.RoamingProvider;
                    } else {
                        for (EAPMethod anMethod : naiRealmData.getEAPMethods()) {
                            EAPMethod spMethod = mCredentials.get(anMethod.getEAPMethodID());
                            if (spMethod.matchesAuthParams(anMethod)) {
                                return PasspointMatch.RoamingProvider;
                            }
                        }
                    }
                }
            }
        }
        return PasspointMatch.None;
    }

    private static List<String> splitDomain(String domain) {

        if (domain.endsWith("."))
            domain = domain.substring(0, domain.length() - 1);
        int at = domain.indexOf('@');
        if (at >= 0)
            domain = domain.substring(at + 1);

        String[] labels = domain.split("\\.");
        LinkedList<String> labelList = new LinkedList<String>();
        for (String label : labels) {
            labelList.addFirst(label);
        }

        return labelList;
    }
}
