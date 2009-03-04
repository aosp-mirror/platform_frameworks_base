
public class CType {

    String baseType;
    boolean isConst;
    boolean isPointer;

    public CType() {
    }

    public CType(String baseType) {
	setBaseType(baseType);
    }

    public CType(String baseType, boolean isConst, boolean isPointer) {
	setBaseType(baseType);
	setIsConst(isConst);
	setIsPointer(isPointer);
    }

    public String getDeclaration() {
	return baseType + (isPointer ? " *" : "");
    }
    
    public void setIsConst(boolean isConst) {
	this.isConst = isConst;
    }

    public boolean isConst() {
	return isConst;
    }

    public void setIsPointer(boolean isPointer) {
	this.isPointer = isPointer;
    }

    public boolean isPointer() {
	return isPointer;
    }

    boolean isVoid() {
	String baseType = getBaseType();
	return baseType.equals("GLvoid") ||
	    baseType.equals("void");
    }

    public boolean isTypedPointer() {
	return isPointer() && !isVoid();
    }

    public void setBaseType(String baseType) {
	this.baseType = baseType;
    }

    public String getBaseType() {
	return baseType;
    }

    public String toString() {
	String s = "";
	if (isConst()) {
	    s += "const ";
	}
	s += baseType;
	if (isPointer()) {
	    s += "*";
	}

	return s;
    }

    public int hashCode() {
	return baseType.hashCode() ^ (isPointer ? 2 : 0) ^ (isConst ? 1 : 0);
    }

    public boolean equals(Object o) {
	if (o != null && o instanceof CType) {
	    CType c = (CType)o;
	    return baseType.equals(c.baseType) &&
		isPointer() == c.isPointer() &&
		isConst() == c.isConst();
	}
	return false;
    }
}
