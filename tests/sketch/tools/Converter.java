import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.BufferedInputStream;

/**
 * Converts text-based letter stores to binary-based stores.
 */
public class Converter {
    private final File mFile;
    private static final short VERSION_NUMBER = 1;

    Converter(File file) {
        mFile = file;
    }

    private void convert() {
        boolean read = false;

        String[] classes = null;
        int iCount = 0;
        int hCount = 0;
        int oCount = 0;
        float[][] iWeights = null;
        float[][] oWeights = null;
        
        BufferedReader reader = null;

        try {
            reader = new BufferedReader(new FileReader(mFile));

            long start = System.nanoTime();

            String line = reader.readLine();
            int startIndex = 0;
            int endIndex;
            endIndex = line.indexOf(" ", startIndex);
            iCount = Integer.parseInt(line.substring(startIndex, endIndex));

            startIndex = endIndex + 1;
            endIndex = line.indexOf(" ", startIndex);
            hCount = Integer.parseInt(line.substring(startIndex, endIndex));

            startIndex = endIndex + 1;
            endIndex = line.length();
            oCount = Integer.parseInt(line.substring(startIndex, endIndex));

            classes = new String[oCount];
            line = reader.readLine();
            startIndex = 0;

            for (int i = 0; i < oCount; i++) {
                endIndex = line.indexOf(" ", startIndex);
                classes[i] = line.substring(startIndex, endIndex);
                startIndex = endIndex + 1;
            }

            iWeights = new float[hCount][];
            for (int i = 0; i < hCount; i++) {
                iWeights[i] = new float[iCount + 1];
                line = reader.readLine();
                startIndex = 0;
                for (int j = 0; j <= iCount; j++) {
                    endIndex = line.indexOf(" ", startIndex);
                    iWeights[i][j] = Float.parseFloat(line.substring(startIndex, endIndex));
                    startIndex = endIndex + 1;
                }
            }

            oWeights = new float[oCount][];
            for (int i = 0; i < oCount; i++) {
                oWeights[i] = new float[hCount + 1];
                line = reader.readLine();
                startIndex = 0;
                for (int j = 0; j <= hCount; j++) {
                    endIndex = line.indexOf(" ", startIndex);
                    oWeights[i][j] = Float.parseFloat(line.substring(startIndex, endIndex));
                    startIndex = endIndex + 1;
                }
            }

            long end = System.nanoTime();
            System.out.println("time to read text file = " +
                    ((end - start) / 1000.0f / 1000.0f) + " ms");

            read = true;
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            close(reader);
        }

        if (read) {
            boolean wrote = false;
            DataOutputStream out = null;

            try {
                out = new DataOutputStream(new FileOutputStream(mFile));

                out.writeShort(VERSION_NUMBER);
                out.writeInt(iCount);
                out.writeInt(hCount);
                out.writeInt(oCount);

                for (String aClass : classes) {
                    out.writeUTF(aClass);
                }

                for (float[] weights : iWeights) {
                    for (float weight : weights) {
                        out.writeFloat(weight);
                    }
                }

                for (float[] weights : oWeights) {
                    for (float weight : weights) {
                        out.writeFloat(weight);
                    }
                }

                out.flush();

                wrote = true;
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                close(out);
            }

            if (wrote) {
                DataInputStream in = null;

                try {
                    in = new DataInputStream(new BufferedInputStream(new FileInputStream(mFile)));

                    long start = System.nanoTime();

                    iCount = in.readInt();
                    hCount = in.readInt();
                    oCount = in.readInt();

                    classes = new String[oCount];
                    for (int i = 0; i < classes.length; i++) {
                        classes[i] = in.readUTF();
                    }

                    iWeights = new float[hCount][];
                    for (int i = 0; i < iWeights.length; i++) {
                        iWeights[i] = new float[iCount];
                        for (int j = 0; j < iCount; j++) {
                            iWeights[i][j] = in.readFloat();
                        }
                    }

                    oWeights = new float[oCount][];
                    for (int i = 0; i < oWeights.length; i++) {
                        oWeights[i] = new float[hCount];
                        for (int j = 0; j < hCount; j++) {
                            oWeights[i][j] = in.readFloat();
                        }
                    }

                    long end = System.nanoTime();
                    System.out.println("time to read binary file = " +
                            ((end - start) / 1000.0f / 1000.0f) + " ms");

                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    close(in);
                }
            }
        }
    }

    private static void close(Closeable reader) {
        if (reader != null) {
            try {
                reader.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        String fileName = args[0];
        if (fileName != null) {
            File file = new File(fileName);
            if (!file.exists()) {
                printHelp(fileName);
            } else {
                new Converter(file).convert();
            }
        } else {
            printHelp(null);
        }
    }

    private static void printHelp(String name) {
        if (name == null) {
            System.out.println("You must specify the name of the file to convert:");
        } else {
            System.out.println("The specified file does not exist: " + name);
        }
        System.out.println("java Converter [filename]");
        System.out.println("");
        System.out.println("\t[filename]\tPath to the file to convert. The file is replaced by "
                + "the conversion result.");
    }
}