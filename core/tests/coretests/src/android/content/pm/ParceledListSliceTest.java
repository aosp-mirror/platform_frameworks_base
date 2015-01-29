package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;
import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

public class ParceledListSliceTest extends TestCase {

    public void testSmallList() throws Exception {
        final int objectCount = 100;
        List<SmallObject> list = new ArrayList<SmallObject>();
        for (int i = 0; i < objectCount; i++) {
            list.add(new SmallObject(i * 2, (i * 2) + 1));
        }

        ParceledListSlice<SmallObject> slice;

        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeParcelable(new ParceledListSlice<SmallObject>(list), 0);
            parcel.setDataPosition(0);
            slice = parcel.readParcelable(getClass().getClassLoader());
        } finally {
            parcel.recycle();
        }

        assertNotNull(slice);
        assertNotNull(slice.getList());
        assertEquals(objectCount, slice.getList().size());

        for (int i = 0; i < objectCount; i++) {
            assertEquals(i * 2, slice.getList().get(i).mFieldA);
            assertEquals((i * 2) + 1, slice.getList().get(i).mFieldB);
        }
    }

    private static int measureLargeObject() {
        Parcel p = Parcel.obtain();
        try {
            new LargeObject(0, 0, 0, 0, 0).writeToParcel(p, 0);
            return p.dataPosition();
        } finally {
            p.recycle();
        }
    }

    /**
     * Test that when the list is large, the data is successfully parceled
     * and unparceled (the implementation will send pieces of the list in
     * separate round-trips to avoid the IPC limit).
     */
    public void testLargeList() throws Exception {
        final int thresholdBytes = 256 * 1024;
        final int objectCount = thresholdBytes / measureLargeObject();

        List<LargeObject> list = new ArrayList<LargeObject>();
        for (int i = 0; i < objectCount; i++) {
            list.add(new LargeObject(
                    i * 5,
                    (i * 5) + 1,
                    (i * 5) + 2,
                    (i * 5) + 3,
                    (i * 5) + 4
            ));
        }

        ParceledListSlice<LargeObject> slice;

        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeParcelable(new ParceledListSlice<LargeObject>(list), 0);
            parcel.setDataPosition(0);
            slice = parcel.readParcelable(getClass().getClassLoader());
        } finally {
            parcel.recycle();
        }

        assertNotNull(slice);
        assertNotNull(slice.getList());
        assertEquals(objectCount, slice.getList().size());

        for (int i = 0; i < objectCount; i++) {
            assertEquals(i * 5, slice.getList().get(i).mFieldA);
            assertEquals((i * 5) + 1, slice.getList().get(i).mFieldB);
            assertEquals((i * 5) + 2, slice.getList().get(i).mFieldC);
            assertEquals((i * 5) + 3, slice.getList().get(i).mFieldD);
            assertEquals((i * 5) + 4, slice.getList().get(i).mFieldE);
        }
    }

    /**
     * Test that only homogeneous elements may be unparceled.
     */
    public void testHomogeneousElements() throws Exception {
        List<BaseObject> list = new ArrayList<BaseObject>();
        list.add(new LargeObject(0, 1, 2, 3, 4));
        list.add(new SmallObject(5, 6));
        list.add(new SmallObject(7, 8));

        Parcel parcel = Parcel.obtain();
        try {
            writeEvilParceledListSlice(parcel, list);
            parcel.setDataPosition(0);
            try {
                ParceledListSlice.CREATOR.createFromParcel(parcel, getClass().getClassLoader());
                assertTrue("Unparceled heterogeneous ParceledListSlice", false);
            } catch (IllegalArgumentException e) {
                // Success, we're not allowed to process heterogeneous
                // elements in a ParceledListSlice.
            }
        } finally {
            parcel.recycle();
        }
    }

    /**
     * Write a ParcelableListSlice that uses the BaseObject base class as the Creator.
     * This is dangerous, as it may affect how the data is unparceled, then later parceled
     * by the system, leading to a self-modifying data security vulnerability.
     */
    private static <T extends BaseObject> void writeEvilParceledListSlice(Parcel dest, List<T> list) {
        final int listCount = list.size();

        // Number of items.
        dest.writeInt(listCount);

        // The type/creator to use when unparceling. Here we use the base class
        // to simulate an attack on ParceledListSlice.
        dest.writeString(BaseObject.class.getName());

        for (int i = 0; i < listCount; i++) {
            // 1 means the item is present.
            dest.writeInt(1);
            list.get(i).writeToParcel(dest, 0);
        }
    }

    public abstract static class BaseObject implements Parcelable {
        protected static final int TYPE_SMALL = 0;
        protected static final int TYPE_LARGE = 1;

        protected void writeToParcel(Parcel dest, int flags, int type) {
            dest.writeInt(type);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        /**
         * This is *REALLY* bad, but we're doing it in the test to ensure that we handle
         * the possible exploit when unparceling an object with the BaseObject written as
         * Creator.
         */
        public static final Creator<BaseObject> CREATOR = new Creator<BaseObject>() {
            @Override
            public BaseObject createFromParcel(Parcel source) {
                switch (source.readInt()) {
                    case TYPE_SMALL:
                        return SmallObject.createFromParcelBody(source);
                    case TYPE_LARGE:
                        return LargeObject.createFromParcelBody(source);
                    default:
                        throw new IllegalArgumentException("Unknown type");
                }
            }

            @Override
            public BaseObject[] newArray(int size) {
                return new BaseObject[size];
            }
        };
    }

    public static class SmallObject extends BaseObject {
        public int mFieldA;
        public int mFieldB;

        public SmallObject(int a, int b) {
            mFieldA = a;
            mFieldB = b;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags, TYPE_SMALL);
            dest.writeInt(mFieldA);
            dest.writeInt(mFieldB);
        }

        public static SmallObject createFromParcelBody(Parcel source) {
            return new SmallObject(source.readInt(), source.readInt());
        }

        public static final Creator<SmallObject> CREATOR = new Creator<SmallObject>() {
            @Override
            public SmallObject createFromParcel(Parcel source) {
                // Consume the type (as it is always written out).
                source.readInt();
                return createFromParcelBody(source);
            }

            @Override
            public SmallObject[] newArray(int size) {
                return new SmallObject[size];
            }
        };
    }

    public static class LargeObject extends BaseObject {
        public int mFieldA;
        public int mFieldB;
        public int mFieldC;
        public int mFieldD;
        public int mFieldE;

        public LargeObject(int a, int b, int c, int d, int e) {
            mFieldA = a;
            mFieldB = b;
            mFieldC = c;
            mFieldD = d;
            mFieldE = e;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags, TYPE_LARGE);
            dest.writeInt(mFieldA);
            dest.writeInt(mFieldB);
            dest.writeInt(mFieldC);
            dest.writeInt(mFieldD);
            dest.writeInt(mFieldE);
        }

        public static LargeObject createFromParcelBody(Parcel source) {
            return new LargeObject(
                    source.readInt(),
                    source.readInt(),
                    source.readInt(),
                    source.readInt(),
                    source.readInt()
            );
        }

        public static final Creator<LargeObject> CREATOR = new Creator<LargeObject>() {
            @Override
            public LargeObject createFromParcel(Parcel source) {
                // Consume the type (as it is always written out).
                source.readInt();
                return createFromParcelBody(source);
            }

            @Override
            public LargeObject[] newArray(int size) {
                return new LargeObject[size];
            }
        };
    }
}
