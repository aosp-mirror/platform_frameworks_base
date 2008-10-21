Some classes that handle DDM traffic.

It's not necessary to put all DDM-related code in this package; this just
has the essentials.  Subclass org.apache.harmony.dalvik.ddmc.ChunkHandler and add a new
registration call in DdmRegister.java.

