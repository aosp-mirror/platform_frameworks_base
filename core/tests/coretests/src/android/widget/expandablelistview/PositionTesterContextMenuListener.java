package android.widget.expandablelistview;

import android.view.ContextMenu;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnCreateContextMenuListener;
import android.widget.ExpandableListView;
import android.widget.AdapterView.AdapterContextMenuInfo;

import junit.framework.Assert;

public class PositionTesterContextMenuListener implements OnCreateContextMenuListener {

    private int groupPosition, childPosition;

    // Fake constant to store in testType a test type specific to headers and footers
    private static final int ADAPTER_TYPE = -1;
    private int testType; // as returned by getPackedPositionType

    public void expectGroupContextMenu(int groupPosition) {
        this.groupPosition = groupPosition;
        testType = ExpandableListView.PACKED_POSITION_TYPE_GROUP;
    }

    public void expectChildContextMenu(int groupPosition, int childPosition) {
        this.groupPosition = groupPosition;
        this.childPosition = childPosition;
        testType = ExpandableListView.PACKED_POSITION_TYPE_CHILD;
    }

    public void expectAdapterContextMenu(int flatPosition) {
        this.groupPosition = flatPosition;
        testType = ADAPTER_TYPE;
    }

    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        if (testType == ADAPTER_TYPE) {
            Assert.assertTrue("MenuInfo is not an AdapterContextMenuInfo",
                    menuInfo instanceof AdapterContextMenuInfo);
            AdapterContextMenuInfo adapterContextMenuInfo = (AdapterContextMenuInfo) menuInfo;
            Assert.assertEquals("Wrong flat position",
                    groupPosition,
                    adapterContextMenuInfo.position);
        } else {
            Assert.assertTrue("MenuInfo is not an ExpandableListContextMenuInfo",
                    menuInfo instanceof ExpandableListView.ExpandableListContextMenuInfo);
            ExpandableListView.ExpandableListContextMenuInfo elvMenuInfo =
                (ExpandableListView.ExpandableListContextMenuInfo) menuInfo;
            long packedPosition = elvMenuInfo.packedPosition;

            int packedPositionType = ExpandableListView.getPackedPositionType(packedPosition);
            Assert.assertEquals("Wrong packed position type", testType, packedPositionType);

            int packedPositionGroup = ExpandableListView.getPackedPositionGroup(packedPosition);
            Assert.assertEquals("Wrong group position", groupPosition, packedPositionGroup);

            if (testType == ExpandableListView.PACKED_POSITION_TYPE_CHILD) {
                int packedPosChild = ExpandableListView.getPackedPositionChild(packedPosition);
                Assert.assertEquals("Wrong child position", childPosition, packedPosChild);
            }
        }
    }
}
