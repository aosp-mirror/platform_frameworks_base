import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.Iterator;

/**
 * Prints HTML reports that can be attached to bugs.
 */
public class PrintBugReports {

    private static final String DIR = "out/preload";
    private static boolean PRINT_MEMORY_USAGE = false;

    private static final Comparator<LoadedClass> DEFAULT_ORDER
            = new Comparator<LoadedClass>() {
        public int compare(LoadedClass a, LoadedClass b) {
            // Longest load time first.
            int diff = b.medianTimeMicros() - a.medianTimeMicros();
            if (diff != 0) {
                return diff;
            }

            return a.name.compareTo(b.name);
        }
    };

    public static void main(String[] args)
            throws IOException, ClassNotFoundException {
        Root root = Root.fromFile(args[0]);
        String baseUrl = "";
        if (args.length > 1) {
            baseUrl = args[1];
        }

        new File(DIR).mkdirs();

        Map<String, List<Proc>> procsByName = new HashMap<String, List<Proc>>();
        for (Proc proc : root.processes.values()) {
            if (proc.fromZygote()) {
                List<Proc> procs = procsByName.get(proc.name);
                if (procs == null) {
                    procs = new ArrayList<Proc>();
                    procsByName.put(proc.name, procs);
                }
                procs.add(proc);
            }
        }

        Set<LoadedClass> coreClasses = new TreeSet<LoadedClass>(DEFAULT_ORDER);
        Set<LoadedClass> frameworkClasses = new TreeSet<LoadedClass>(DEFAULT_ORDER);

        for (List<Proc> procs : procsByName.values()) {
            Proc first = procs.get(0);
            Set<LoadedClass> classes = new TreeSet<LoadedClass>(DEFAULT_ORDER);
            Set<LoadedClass> sharedClasses
                    = new TreeSet<LoadedClass>(DEFAULT_ORDER);
            for (Proc proc : procs) {
                for (Operation operation : proc.operations) {
                    LoadedClass clazz = operation.loadedClass;
                    if (clazz.isSharable() && clazz.systemClass) {
                        if (clazz.name.startsWith("dalvik")
                                || clazz.name.startsWith("org")
                                || clazz.name.startsWith("java")) {
                            coreClasses.add(clazz);
                        } else {
                            frameworkClasses.add(clazz);
                        }
                        sharedClasses.add(clazz);
                    } else {
                        classes.add(clazz);
                    }
                }
            }
            printApplicationHtml(first.name, root.baseline, classes,
                    sharedClasses);
        }

        printHtml("core", root.baseline, coreClasses);
        printHtml("framework", root.baseline, frameworkClasses);

        PrintStream out = new PrintStream(DIR + "/toc.html");
        out.println("<html><body>");
        out.println("<a href='" + baseUrl
                + "/core.html'>core</a><br/>");
        out.println("<a href='" + baseUrl
                + "/framework.html'>framework</a><br/>");

        for (String s : new TreeSet<String>(procsByName.keySet())) {
            out.println("<a href='" + baseUrl + "/"
                    + s + ".html'>" + s + "</a><br/>");
        }
        out.println("</body></html>");
        out.close();
    }

    static void printApplicationHtml(String name, MemoryUsage baseline,
            Iterable<LoadedClass> classes, Iterable<LoadedClass> sharedClasses)
            throws IOException {
        PrintStream out = new PrintStream(DIR + "/" + name + ".html");

        printHeader(name, out);
        out.println("<body>");
        out.println("<h1><tt>" + name + "</tt></h1>");
        out.println("<p><i>Click a column header to sort by that column.</i></p>");

        out.println("<p><a href=\"#shared\">Shared Classes</a></p>");

        out.println("<h3>Application-Specific Classes</h3>");

        out.println("<p>These classes were loaded only by " + name + ". If"
                + " the value of the <i>Preloaded</i> column is <i>yes</i> or "
                + " <i>no</i>, the class is in the boot classpath; if it's not"
                + " part of the published API, consider"
                + " moving it into the APK.</p>");

        printTable(out, baseline, classes, false);

        out.println("<p><a href=\"#\">Top</a></p>");

        out.println("<a name=\"shared\"/><h3>Shared Classes</h3>");

        out.println("<p>These classes are in the boot classpath. They are used"
                + " by " + name + " as well as others.");

        printTable(out, baseline, sharedClasses, true);

        out.println("</body></html>");
        out.close();
    }

    static void printHtml(String name, MemoryUsage baseline,
            Iterable<LoadedClass> classes)
            throws IOException {
        PrintStream out = new PrintStream(DIR + "/" + name + ".html");

        printHeader(name, out);
        out.println("<body>");
        out.println("<h1><tt>" + name + "</tt></h1>");
        out.println("<p><i>Click a column header to sort by that column.</i></p>");

        printTable(out, baseline, classes, true);

        out.println("</body></html>");
        out.close();
    }

    private static void printHeader(String name, PrintStream out)
            throws IOException {
        out.println("<html><head>");
        out.println("<title>" + name + "</title>");
        out.println("<style>");
        out.println("a, th, td, h1, h3, p { font-family: arial }");
        out.println("th, td { font-size: small }");
        out.println("</style>");
        out.println("<script language=\"javascript\">");
        out.write(SCRIPT);
        out.println("</script>");
        out.println("</head>");
    }

    static void printTable(PrintStream out, MemoryUsage baseline,
            Iterable<LoadedClass> classes, boolean showProcNames) {
        out.println("<p><table border=\"1\" cellpadding=\"5\""
                + " class=\"sortable\" cellspacing=\"0\">");

        out.println("<thead bgcolor=\"#eeeeee\"><tr>");
        out.println("<th>Name</th>");
        out.println("<th>Preloaded</th>");
        out.println("<th>Total Time (us)</th>");
        out.println("<th>Load Time (us)</th>");
        out.println("<th>Init Time (us)</th>");
        if (PRINT_MEMORY_USAGE) {
            out.println("<th>Total Heap (B)</th>");
            out.println("<th>Dalvik Heap (B)</th>");
            out.println("<th>Native Heap (B)</th>");
            out.println("<th>Total Pages (kB)</th>");
            out.println("<th>Dalvik Pages (kB)</th>");
            out.println("<th>Native Pages (kB)</th>");
            out.println("<th>Other Pages (kB)</th>");
        }
        if (showProcNames) {
            out.println("<th>Loaded by</th>");
        }
        out.println("</tr></thead>");

        for (LoadedClass clazz : classes) {
            out.println("<tr>");
            out.println("<td>" + clazz.name + "</td>");

            out.println("<td>" + ((clazz.systemClass)
                    ? ((clazz.preloaded) ? "yes" : "no") : "n/a") + "</td>");

            out.println("<td>" + clazz.medianTimeMicros() + "</td>");
            out.println("<td>" + clazz.medianLoadTimeMicros() + "</td>");
            out.println("<td>" + clazz.medianInitTimeMicros() + "</td>");

            if (PRINT_MEMORY_USAGE) {
                if (clazz.memoryUsage.isAvailable()) {
                    MemoryUsage subtracted
                            = clazz.memoryUsage.subtract(baseline);

                    long totalHeap = subtracted.javaHeapSize()
                            + subtracted.nativeHeapSize;
                    out.println("<td>" + totalHeap + "</td>");
                    out.println("<td>" + subtracted.javaHeapSize() + "</td>");
                    out.println("<td>" + subtracted.nativeHeapSize + "</td>");

                    out.println("<td>" + subtracted.totalPages() + "</td>");
                    out.println("<td>" + subtracted.javaPagesInK() + "</td>");
                    out.println("<td>" + subtracted.nativePagesInK() + "</td>");
                    out.println("<td>" + subtracted.otherPagesInK() + "</td>");
                } else {
                    for (int i = 0; i < 7; i++) {
                        out.println("<td>&nbsp;</td>");
                    }
                }
            }

            if (showProcNames) {
                out.println("<td>");
                Set<String> procNames = new TreeSet<String>();
                for (Operation op : clazz.loads) {
                    procNames.add(op.process.name);
                }
                for (Operation op : clazz.initializations) {
                    procNames.add(op.process.name);
                }
                if (procNames.size() <= 3) {
                    for (String name : procNames) {
                        out.print(name + "<br/>");
                    }
                } else {
                    Iterator<String> i = procNames.iterator();
                    out.print(i.next() + "<br/>");
                    out.print(i.next() + "<br/>");
                    out.print("...and " + (procNames.size() - 2)
                            + " others.");
                }
                out.println("</td>");
            }

            out.println("</tr>");
        }

        out.println("</table></p>");
    }

    static byte[] SCRIPT;
    static {
        try {
            File script = new File(
                    "frameworks/base/tools/preload/sorttable.js");
            int length = (int) script.length();
            SCRIPT = new byte[length];
            DataInputStream in = new DataInputStream(
                    new FileInputStream(script));
            in.readFully(SCRIPT);
            in.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
