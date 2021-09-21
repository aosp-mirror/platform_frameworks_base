/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.annotation.IntDef;
import android.annotation.SystemApi;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Interface for classes whose instances can be written to
 * and restored from a {@link Parcel}.  Classes implementing the Parcelable
 * interface must also have a non-null static field called <code>CREATOR</code>
 * of a type that implements the {@link Parcelable.Creator} interface.
 *
 * <p>A typical implementation of Parcelable is:</p>
 *
 * <div>
 * <div class="ds-selector-tabs"><section><h3 id="kotlin">Kotlin</h3>
 * <pre class="prettyprint lang-kotlin">
 * class MyParcelable private constructor(`in`: Parcel) : Parcelable {
 *     private val mData: Int = `in`.readInt()
 *
 *     override fun describeContents(): Int {
 *         return 0
 *     }
 *
 *     override fun writeToParcel(out: Parcel, flags: Int) {
 *         out.writeInt(mData)
 *     }
 *
 *     companion object CREATOR: Parcelable.Creator&lt;MyParcelable?&gt; {
 *         override fun createFromParcel(`in`: Parcel): MyParcelable? {
 *             return MyParcelable(`in`)
 *         }
 *
 *         override fun newArray(size: Int): Array&lt;MyParcelable?&gt; {
 *             return arrayOfNulls(size)
 *         }
 *     }
 * }
 * </pre>
 * </section><section><h3 id="java">Java</h3>
 * <pre class="prettyprint lang-java">
 * public class MyParcelable implements Parcelable {
 *     private int mData;
 *
 *     public int describeContents() {
 *         return 0;
 *     }
 *
 *     public void writeToParcel(Parcel out, int flags) {
 *         out.writeInt(mData);
 *     }
 *
 *     public static final Parcelable.Creator&lt;MyParcelable&gt; CREATOR
 *             = new Parcelable.Creator&lt;MyParcelable&gt;() {
 *         public MyParcelable createFromParcel(Parcel in) {
 *             return new MyParcelable(in);
 *         }
 *
 *         public MyParcelable[] newArray(int size) {
 *             return new MyParcelable[size];
 *         }
 *     };
 *
 *     private MyParcelable(Parcel in) {
 *         mData = in.readInt();
 *     }
 * }</pre></section></div></div>
 */
public interface Parcelable {
    /** @hide */
    @IntDef(flag = true, prefix = { "PARCELABLE_" }, value = {
            PARCELABLE_WRITE_RETURN_VALUE,
            PARCELABLE_ELIDE_DUPLICATES,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface WriteFlags {}

    /**
     * Flag for use with {@link #writeToParcel}: the object being written
     * is a return value, that is the result of a function such as
     * "<code>Parcelable someFunction()</code>",
     * "<code>void someFunction(out Parcelable)</code>", or
     * "<code>void someFunction(inout Parcelable)</code>".  Some implementations
     * may want to release resources at this point.
     */
    public static final int PARCELABLE_WRITE_RETURN_VALUE = 0x0001;

    /**
     * Flag for use with {@link #writeToParcel}: a parent object will take
     * care of managing duplicate state/data that is nominally replicated
     * across its inner data members.  This flag instructs the inner data
     * types to omit that data during marshaling.  Exact behavior may vary
     * on a case by case basis.
     * @hide
     */
    public static final int PARCELABLE_ELIDE_DUPLICATES = 0x0002;

    /*
     * Bit masks for use with {@link #describeContents}: each bit represents a
     * kind of object considered to have potential special significance when
     * marshalled.
     */

    /** @hide */
    @IntDef(flag = true, prefix = { "CONTENTS_" }, value = {
            CONTENTS_FILE_DESCRIPTOR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ContentsFlags {}

    /** @hide */
    @IntDef(flag = true, prefix = { "PARCELABLE_STABILITY_" }, value = {
            PARCELABLE_STABILITY_LOCAL,
            PARCELABLE_STABILITY_VINTF,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Stability {}

    /**
     * Something that is not meant to cross compilation boundaries.
     *
     * Note: unlike binder/Stability.h which uses bitsets to detect stability,
     * since we don't currently have a notion of different local locations,
     * higher stability levels are formed at higher levels.
     *
     * For instance, contained entirely within system partitions.
     * @see #getStability()
     * @see ParcelableHolder
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final int PARCELABLE_STABILITY_LOCAL = 0x0000;
    /**
     * Something that is meant to be used between system and vendor.
     * @see #getStability()
     * @see ParcelableHolder
     * @hide
     */
    @SystemApi(client = SystemApi.Client.PRIVILEGED_APPS)
    public static final int PARCELABLE_STABILITY_VINTF = 0x0001;

    /**
     * Descriptor bit used with {@link #describeContents()}: indicates that
     * the Parcelable object's flattened representation includes a file descriptor.
     *
     * @see #describeContents()
     */
    public static final int CONTENTS_FILE_DESCRIPTOR = 0x0001;
    
    /**
     * Describe the kinds of special objects contained in this Parcelable
     * instance's marshaled representation. For example, if the object will
     * include a file descriptor in the output of {@link #writeToParcel(Parcel, int)},
     * the return value of this method must include the
     * {@link #CONTENTS_FILE_DESCRIPTOR} bit.
     *  
     * @return a bitmask indicating the set of special object types marshaled
     * by this Parcelable object instance.
     */
    public @ContentsFlags int describeContents();

    /**
     * 'Stable' means this parcelable is guaranteed to be stable for multiple years.
     * It must be guaranteed by setting stability field in aidl_interface,
     * OR explicitly override this method from @JavaOnlyStableParcelable marked Parcelable.
     * WARNING: isStable() is only expected to be overridden by auto-generated code,
     * OR @JavaOnlyStableParcelable marked Parcelable only if there is guaranteed to
     * be only once copy of the parcelable on the system.
     * @return true if this parcelable is stable.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    default @Stability int getStability() {
        return PARCELABLE_STABILITY_LOCAL;
    }

    /**
     * Flatten this object in to a Parcel.
     * 
     * @param dest The Parcel in which the object should be written.
     * @param flags Additional flags about how the object should be written.
     * May be 0 or {@link #PARCELABLE_WRITE_RETURN_VALUE}.
     */
    public void writeToParcel(Parcel dest, @WriteFlags int flags);

    /**
     * Interface that must be implemented and provided as a public CREATOR
     * field that generates instances of your Parcelable class from a Parcel.
     */
    public interface Creator<T> {
        /**
         * Create a new instance of the Parcelable class, instantiating it
         * from the given Parcel whose data had previously been written by
         * {@link Parcelable#writeToParcel Parcelable.writeToParcel()}.
         * 
         * @param source The Parcel to read the object's data from.
         * @return Returns a new instance of the Parcelable class.
         */
        public T createFromParcel(Parcel source);
        
        /**
         * Create a new array of the Parcelable class.
         * 
         * @param size Size of the array.
         * @return Returns an array of the Parcelable class, with every entry
         * initialized to null.
         */
        public T[] newArray(int size);
    }

    /**
     * Specialization of {@link Creator} that allows you to receive the
     * ClassLoader the object is being created in.
     */
    public interface ClassLoaderCreator<T> extends Creator<T> {
        /**
         * Create a new instance of the Parcelable class, instantiating it
         * from the given Parcel whose data had previously been written by
         * {@link Parcelable#writeToParcel Parcelable.writeToParcel()} and
         * using the given ClassLoader.
         *
         * @param source The Parcel to read the object's data from.
         * @param loader The ClassLoader that this object is being created in.
         * @return Returns a new instance of the Parcelable class.
         */
        public T createFromParcel(Parcel source, ClassLoader loader);
    }
}
