package android.net.wifi.hotspot2.pps;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Created by jannq on 1/21/15.
 */
public class DomainMatcher {

    public enum Match { None, Primary, Secondary }

    private final Label mRoot;

    private static class Label {
        private final Map<String,Label> mSubDomains;
        private final Match mMatch;

        private Label( Match match ) {
            mMatch = match;
            mSubDomains = match == Match.None ? null : new HashMap<String,Label>();
        }

        private void addDomain( Iterator<String> labels, Match match ) {
            String labelName = labels.next();
            if ( labels.hasNext() ) {
                Label subLabel = new Label( Match.None );
                mSubDomains.put( labelName, subLabel );
                subLabel.addDomain( labels, match );
            }
            else {
                mSubDomains.put( labelName, new Label( match ) );
            }
        }

        private Label getSubLabel( String labelString ) {
            return mSubDomains.get( labelString );
        }

        public Match getMatch() {
            return mMatch;
        }
    }

    public DomainMatcher( List<String> primary, List<List<String>> secondary ) {
        mRoot = new Label( Match.None );
        for ( List<String> secondaryLabel : secondary ) {
            mRoot.addDomain( secondaryLabel.iterator(), Match.Secondary );
        }
        // Primary overwrites secondary.
        mRoot.addDomain( primary.iterator(), Match.Primary );
    }

    public Match isSubDomain( List<String> domain ) {

        Label label = mRoot;
        for ( String labelString : domain ) {
            label = label.getSubLabel( labelString );
            if ( label == null ) {
                return Match.None;
            }
            else if ( label.getMatch() != Match.None ) {
                return label.getMatch();
            }
        }
        return Match.None;  // Domain is a super domain
    }
}
