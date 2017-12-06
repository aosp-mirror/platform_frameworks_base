package com.android.server.accessibility;

import static org.junit.Assert.fail;

import android.support.test.runner.AndroidJUnit4;
import android.util.ArraySet;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;

import com.android.internal.util.CollectionUtils;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class AccessibilityNodeInfoTest {

    @Test
    public void testStandardActions_serializationFlagIsValid() {
        AccessibilityAction brokenStandardAction = CollectionUtils.find(
                new ArrayList<>(AccessibilityAction.sStandardActions),
                action -> Integer.bitCount(action.mSerializationFlag) != 1);
        if (brokenStandardAction != null) {
            String message = "Invalid serialization flag(0x"
                    + Integer.toHexString(brokenStandardAction.mSerializationFlag)
                    + ") in " + brokenStandardAction;
            if (brokenStandardAction.mSerializationFlag == 0L) {
                message += "\nThis is likely due to an overflow";
            }
            fail(message);
        }

        brokenStandardAction = CollectionUtils.find(
                new ArrayList<>(AccessibilityAction.sStandardActions),
                action -> Integer.bitCount(action.getId()) == 1
                        && action.getId() <= AccessibilityNodeInfo.LAST_LEGACY_STANDARD_ACTION
                        && action.getId() != action.mSerializationFlag);
        if (brokenStandardAction != null) {
            fail("Serialization flag(0x"
                    + Integer.toHexString(brokenStandardAction.mSerializationFlag)
                    + ") is different from legacy action id(0x"
                    + Integer.toHexString(brokenStandardAction.getId())
                    + ") in " + brokenStandardAction);
        }
    }

    @Test
    public void testStandardActions_idsAreUnique() {
        ArraySet<AccessibilityAction> actions = AccessibilityAction.sStandardActions;
        for (int i = 0; i < actions.size(); i++) {
            for (int j = 0; j < i; j++) {
                int id = actions.valueAt(i).getId();
                if (id == actions.valueAt(j).getId()) {
                    fail("Id 0x" + Integer.toHexString(id)
                            + " is duplicated for standard actions #" + i + " and #" + j);
                }
            }
        }
    }

}
