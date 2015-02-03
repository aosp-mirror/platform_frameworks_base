package android.renderscript;

import android.util.Log;
import android.util.Pair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**

******************************
You have tried to change the API from what has been previously approved.

To make these errors go away, you have two choices:
   1) You can add "@hide" javadoc comments to the methods, etc. listed in the
      errors above.

   2) You can update current.txt by executing the following command:
         make update-api

To submit the revised current.txt to the main Android repository,
you will need approval.
******************************

   @hide Pending Android public API approval.
 */
public class ScriptGroup2 extends BaseObj {

  public static class Closure extends BaseObj {
    private Allocation mReturnValue;
    private Map<Script.FieldID, Object> mBindings;

    private Future mReturnFuture;
    private Map<Script.FieldID, Future> mGlobalFuture;

    private static final String TAG = "Closure";

    public Closure(long id, RenderScript rs) {
      super(id, rs);
    }

    public Closure(RenderScript rs, Script.KernelID kernelID, Type returnType,
        Object[] args, Map<Script.FieldID, Object> globals) {
      super(0, rs);

      mReturnValue = Allocation.createTyped(rs, returnType);
      mBindings = new HashMap<Script.FieldID, Object>();
      mGlobalFuture = new HashMap<Script.FieldID, Future>();

      int numValues = args.length + globals.size();

      long[] fieldIDs = new long[numValues];
      long[] values = new long[numValues];
      int[] sizes = new int[numValues];
      long[] depClosures = new long[numValues];
      long[] depFieldIDs = new long[numValues];

      int i;
      for (i = 0; i < args.length; i++) {
        Object obj = args[i];
        fieldIDs[i] = 0;
        if (obj instanceof UnboundValue) {
          UnboundValue unbound = (UnboundValue)obj;
          unbound.addReference(this, i);
        } else {
          retrieveValueAndDependenceInfo(rs, i, args[i], values, sizes,
              depClosures, depFieldIDs);
        }
      }

      for (Map.Entry<Script.FieldID, Object> entry : globals.entrySet()) {
        Object obj = entry.getValue();
        Script.FieldID fieldID = entry.getKey();
        fieldIDs[i] = fieldID.getID(rs);
        if (obj instanceof UnboundValue) {
          UnboundValue unbound = (UnboundValue)obj;
          unbound.addReference(this, fieldID);
        } else {
          retrieveValueAndDependenceInfo(rs, i, obj, values,
              sizes, depClosures, depFieldIDs);
        }
        i++;
      }

      long id = rs.nClosureCreate(kernelID.getID(rs), mReturnValue.getID(rs),
          fieldIDs, values, sizes, depClosures, depFieldIDs);

      setID(id);
    }

    private static void retrieveValueAndDependenceInfo(RenderScript rs,
        int index, Object obj, long[] values, int[] sizes, long[] depClosures,
        long[] depFieldIDs) {

      if (obj instanceof Future) {
        Future f = (Future)obj;
        obj = f.getValue();
        depClosures[index] = f.getClosure().getID(rs);
        Script.FieldID fieldID = f.getFieldID();
        depFieldIDs[index] = fieldID != null ? fieldID.getID(rs) : 0;
      } else {
        depClosures[index] = 0;
        depFieldIDs[index] = 0;
      }

      ValueAndSize vs = new ValueAndSize(rs, obj);
      values[index] = vs.value;
      sizes[index] = vs.size;
    }

    public Future getReturn() {
      if (mReturnFuture == null) {
        mReturnFuture = new Future(this, null, mReturnValue);
      }

      return mReturnFuture;
    }

    public Future getGlobal(Script.FieldID field) {
      Future f = mGlobalFuture.get(field);

      if (f == null) {
        f = new Future(this, field, mBindings.get(field));
        mGlobalFuture.put(field, f);
      }

      return f;
    }

    void setArg(int index, Object obj) {
      ValueAndSize vs = new ValueAndSize(mRS, obj);
      mRS.nClosureSetArg(getID(mRS), index, vs.value, vs.size);
    }

    void setGlobal(Script.FieldID fieldID, Object obj) {
      ValueAndSize vs = new ValueAndSize(mRS, obj);
      mRS.nClosureSetGlobal(getID(mRS), fieldID.getID(mRS), vs.value, vs.size);
    }

    private static final class ValueAndSize {
      public ValueAndSize(RenderScript rs, Object obj) {
        if (obj instanceof Allocation) {
          value = ((Allocation)obj).getID(rs);
          size = -1;
        } else if (obj instanceof Boolean) {
          value = ((Boolean)obj).booleanValue() ? 1 : 0;
          size = 4;
        } else if (obj instanceof Integer) {
          value = ((Integer)obj).longValue();
          size = 4;
        } else if (obj instanceof Long) {
          value = ((Long)obj).longValue();
          size = 8;
        } else if (obj instanceof Float) {
          value = ((Float)obj).longValue();
          size = 4;
        } else if (obj instanceof Double) {
          value = ((Double)obj).longValue();
          size = 8;
        }
      }

      public long value;
      public int size;
    }
  }

  public static class Future {
    Closure mClosure;
    Script.FieldID mFieldID;
    Object mValue;

    Future(Closure closure, Script.FieldID fieldID, Object value) {
      mClosure = closure;
      mFieldID = fieldID;
      mValue = value;
    }

    Closure getClosure() { return mClosure; }
    Script.FieldID getFieldID() { return mFieldID; }
    Object getValue() { return mValue; }
  }

  public static class UnboundValue {
    // Either mFieldID or mArgIndex should be set but not both.
    List<Pair<Closure, Script.FieldID>> mFieldID;
    // -1 means unset. Legal values are 0 .. n-1, where n is the number of
    // arguments for the referencing closure.
    List<Pair<Closure, Integer>> mArgIndex;

    UnboundValue() {
      mFieldID = new ArrayList<Pair<Closure, Script.FieldID>>();
      mArgIndex = new ArrayList<Pair<Closure, Integer>>();
    }

    void addReference(Closure closure, int index) {
      mArgIndex.add(Pair.create(closure, Integer.valueOf(index)));
    }

    void addReference(Closure closure, Script.FieldID fieldID) {
      mFieldID.add(Pair.create(closure, fieldID));
    }

    void set(Object value) {
      for (Pair<Closure, Integer> p : mArgIndex) {
        Closure closure = p.first;
        int index = p.second.intValue();
        closure.setArg(index, value);
      }
      for (Pair<Closure, Script.FieldID> p : mFieldID) {
        Closure closure = p.first;
        Script.FieldID fieldID = p.second;
        closure.setGlobal(fieldID, value);
      }
    }
  }

  List<Closure> mClosures;
  List<UnboundValue> mInputs;
  Future[] mOutputs;

  private static final String TAG = "ScriptGroup2";

  public ScriptGroup2(long id, RenderScript rs) {
    super(id, rs);
  }

  ScriptGroup2(RenderScript rs, List<Closure> closures,
      List<UnboundValue> inputs, Future[] outputs) {
    super(0, rs);
    mClosures = closures;
    mInputs = inputs;
    mOutputs = outputs;

    long[] closureIDs = new long[closures.size()];
    for (int i = 0; i < closureIDs.length; i++) {
      closureIDs[i] = closures.get(i).getID(rs);
    }
    long id = rs.nScriptGroup2Create(ScriptC.mCachePath, closureIDs);
    setID(id);
  }

  public Object[] execute(Object... inputs) {
    if (inputs.length < mInputs.size()) {
      Log.e(TAG, this.toString() + " receives " + inputs.length + " inputs, " +
          "less than expected " + mInputs.size());
      return null;
    }

    if (inputs.length > mInputs.size()) {
      Log.i(TAG, this.toString() + " receives " + inputs.length + " inputs, " +
          "more than expected " + mInputs.size());
    }

    for (int i = 0; i < mInputs.size(); i++) {
      Object obj = inputs[i];
      if (obj instanceof Future || obj instanceof UnboundValue) {
        Log.e(TAG, this.toString() + ": input " + i +
            " is a future or unbound value");
        return null;
      }
      UnboundValue unbound = mInputs.get(i);
      unbound.set(obj);
    }

    mRS.nScriptGroup2Execute(getID(mRS));

    Object[] outputObjs = new Object[mOutputs.length];
    int i = 0;
    for (Future f : mOutputs) {
      outputObjs[i++] = f.getValue();
    }
    return outputObjs;
  }

  /**
     @hide Pending Android public API approval.
   */
  public static final class Builder {
    RenderScript mRS;
    List<Closure> mClosures;
    List<UnboundValue> mInputs;

    private static final String TAG = "ScriptGroup2.Builder";

    public Builder(RenderScript rs) {
      mRS = rs;
      mClosures = new ArrayList<Closure>();
      mInputs = new ArrayList<UnboundValue>();
    }

    public Closure addKernel(Script.KernelID k, Type returnType, Object[] args,
        Map<Script.FieldID, Object> globalBindings) {
      Closure c = new Closure(mRS, k, returnType, args, globalBindings);
      mClosures.add(c);
      return c;
    }

    public UnboundValue addInput() {
      UnboundValue unbound = new UnboundValue();
      mInputs.add(unbound);
      return unbound;
    }

    public ScriptGroup2 create(Future... outputs) {
      ScriptGroup2 ret = new ScriptGroup2(mRS, mClosures, mInputs, outputs);
      return ret;
    }

  }
}
