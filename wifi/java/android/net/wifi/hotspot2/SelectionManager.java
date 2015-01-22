package android.net.wifi.hotspot2;

import android.net.wifi.anqp.ANQPElement;
import android.net.wifi.hotspot2.pps.HomeSP;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Created by jannq on 1/20/15.
 */
public class SelectionManager {
    private final List<HomeSP> mHomeSPs;
    private final Map<NetworkKey,ANQPData> mANQPCache;
    private final Map<NetworkKey,NetworkInfo> mPendingANQP;
    private final List<ScoredNetwork> mScoredNetworks;

    private static class ScoredNetwork implements Comparable<ScoredNetwork> {
        private final PasspointMatch mMatch;
        private final NetworkInfo mNetworkInfo;

        private ScoredNetwork(PasspointMatch match, NetworkInfo networkInfo) {
            mMatch = match;
            mNetworkInfo = networkInfo;
            // !!! Further score on BSS Load, ANT, "Internet" and HSRelease
        }

        public PasspointMatch getMatch() {
            return mMatch;
        }

        public NetworkInfo getNetworkInfo() {
            return mNetworkInfo;
        }

        @Override
        public int compareTo( ScoredNetwork other ) {
            if ( getMatch() == other.getMatch() ) {
                return 0;
            }
            else {
                return getMatch().ordinal() > other.getMatch().ordinal() ? 1 : -1;
            }
        }
    }

    public SelectionManager( List<HomeSP> homeSPs ) {
        mHomeSPs = homeSPs;
        mANQPCache = new HashMap<NetworkKey,ANQPData>();
        mPendingANQP = new HashMap<NetworkKey, NetworkInfo>();
        mScoredNetworks = new ArrayList<ScoredNetwork>();
    }

    public NetworkInfo findNetwork( NetworkInfo networkInfo ) {

        NetworkKey networkKey = new NetworkKey( networkInfo.getSSID(), networkInfo.getBSSID(), networkInfo.getAnqpDomainID() );
        ANQPData anqpData = mANQPCache.get( networkKey );
        List<ANQPElement> anqpElements = anqpData != null ? anqpData.getANQPElements() : null;
        for ( HomeSP homeSP : mHomeSPs ) {
            PasspointMatch match = homeSP.match( networkInfo, anqpElements );
            if ( match == PasspointMatch.HomeProvider || match == PasspointMatch.RoamingProvider ) {
                mScoredNetworks.add( new ScoredNetwork( match, networkInfo ) );
            }
            else if ( match == PasspointMatch.Incomplete && networkInfo.getAnt() != null ) {
                mPendingANQP.put(networkKey, networkInfo);
            }
        }

        // !!! Should really return a score-sorted list.
        Collections.sort( mScoredNetworks );
        if ( ! mScoredNetworks.isEmpty() &&
                mScoredNetworks.get( 0 ).getMatch() == PasspointMatch.HomeProvider ) {
            return mScoredNetworks.get( 0 ).getNetworkInfo();
        }
        else {
            return null;
        }
    }

    public void notifyANQPResponse( NetworkInfo networkInfo, List<ANQPElement> anqpElements ) {
        NetworkKey networkKey = new NetworkKey( networkInfo.getSSID(), networkInfo.getBSSID(), networkInfo.getAnqpDomainID() );
        mPendingANQP.remove( networkKey );
        mANQPCache.put( networkKey, new ANQPData( anqpElements ) );

        for ( HomeSP homeSP : mHomeSPs ) {
            PasspointMatch match = homeSP.match( networkInfo, anqpElements );
            if ( match == PasspointMatch.HomeProvider || match == PasspointMatch.RoamingProvider ) {
                mScoredNetworks.add( new ScoredNetwork( match, networkInfo ) );
            }
            else if ( match == PasspointMatch.Declined ) {
                Iterator<ScoredNetwork> scoredNetworkIterator = mScoredNetworks.iterator();
                while ( scoredNetworkIterator.hasNext() ) {
                    ScoredNetwork scoredNetwork = scoredNetworkIterator.next();
                    if ( scoredNetwork.getNetworkInfo().getBSSID() == networkInfo.getBSSID() &&
                         scoredNetwork.getNetworkInfo().getSSID().equals( networkInfo.getSSID() ) ) {
                        scoredNetworkIterator.remove();
                        break;
                    }
                }
            }
        }
        Collections.sort( mScoredNetworks );
        if ( ! mScoredNetworks.isEmpty() &&
                mScoredNetworks.get( 0 ).getMatch() == PasspointMatch.HomeProvider ) {
            // Kill mPendingANQP?
            // Connect to mScoredNetworks.get( 0 ).getNetworkInfo()?
        }
    }

    private void sendANQPQuery( NetworkInfo network ) {
        if ( network.getHSRelease() != null ) {
            // Query for 802.11u + potential HS2.0 elements
        }
        else if ( network.getAnt() != null ) {
            // !!! Query for 802.11u
        }
    }
}
