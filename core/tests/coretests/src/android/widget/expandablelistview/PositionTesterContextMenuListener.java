package android.widget.expandablelistview;

import android.view.ContextMenu;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnCreateContextMenuListener;
import android.widget.ExpandableListView;

import junit.framework.Assert;

public class PositionTesterContextMenuListener implements OnCreateContextMenuListener {

    private int groupPosition, childPosition;

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

    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        ExpandableListView.ExpandableListContextMenuInfo elvMenuInfo = (ExpandableListView.ExpandableListContextMenuInfo) menuInfo;
        long packedPosition = elvMenuInfo.packedPosition;

        int packedPositionType = ExpandableListView.getPackedPositionType(packedPosition);
        Assert.assertEquals("Wrong packed position type", testType, packedPositionType);

        int packedPositionGroup = ExpandableListView.getPackedPositionGroup(packedPosition);
        Assert.assertEquals("Wrong group position", groupPosition, packedPositionGroup);

        if (testType == ExpandableListView.PACKED_POSITION_TYPE_CHILD) {
            int packedPositionChild = ExpandableListView.getPackedPositionChild(packedPosition);
            Assert.assertEquals("Wrong child position", childPosition, packedPositionChild);
        }
    }
}
