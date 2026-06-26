import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.io.File;
import java.nio.charset.StandardCharsets;

// Differential + generative test for InternetAddress: upstream (orig.jar) vs patched (out/).
//  - differential: parseHeader() personal/address identical except U+FFFD repairs;
//  - generative:   random display names split-encoded at random byte boundaries are
//                  reconstructed exactly by the patched parser.
// Needs the other Carbonio jars on the parent loader (LC/dom4j/guava); set -Ddeps.dir=...
public class Addr {
    static String summary(ClassLoader cl, String s) throws Exception {
        Class<?> ia = cl.loadClass("com.zimbra.common.mime.InternetAddress");
        List<?> list = (List<?>) ia.getMethod("parseHeader", String.class).invoke(null, s);
        Method gp = ia.getMethod("getPersonal"), ga = ia.getMethod("getAddress");
        StringBuilder sb = new StringBuilder().append(list.size()).append("|");
        for (Object o : list) sb.append("p=").append(gp.invoke(o)).append(";a=").append(ga.invoke(o)).append("||");
        return sb.toString();
    }
    public static void main(String[] a) throws Exception {
        String depsDir = System.getProperty("deps.dir", "/opt/zextras/mailbox/jars");
        List<URL> deps = new ArrayList<>();
        for (File f : new File(depsDir).listFiles())
            if (f.getName().endsWith(".jar") && !f.getName().startsWith("zm-common")) deps.add(f.toURI().toURL());
        URLClassLoader dep = new URLClassLoader(deps.toArray(new URL[0]), null);
        URLClassLoader clO = new URLClassLoader(new URL[]{ new File("orig.jar").toURI().toURL() }, dep);
        URLClassLoader clP = new URLClassLoader(new URL[]{ new File("out").toURI().toURL(), new File("orig.jar").toURI().toURL() }, dep);

        Random rnd = new Random(99L);
        String alpha = "=?utf-8?BbQq UTF-8 KOI8-R \"\\(),;:@<>[]абвРп中 .+/_-0=?=?==aZ";
        int diffs = 0, fixes = 0;
        for (int i = 0; i < 150000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(45);
            for (int j = 0; j < len; j++) sb.append(alpha.charAt(rnd.nextInt(alpha.length())));
            String in = sb.toString();
            String ro = summary(clO, in), rp = summary(clP, in);
            if (!ro.equals(rp)) { if (ro.indexOf('�') >= 0) fixes++; else { diffs++; if (diffs <= 8) System.out.println("DIFF in=["+in.replace("\r","\\r").replace("\n","\\n")+"]\n  O="+ro+"\n  P="+rp); } }
        }
        System.out.println("differential: unexpectedDiffs="+diffs+" fixes(FFFD)="+fixes);

        String pool = "АБВГДabcабвгдеёжэюя Имя-фамилия-.0123";
        int n = 40000, patchFail = 0, origMangled = 0;
        Class<?> iaP = clP.loadClass("com.zimbra.common.mime.InternetAddress");
        Class<?> iaO = clO.loadClass("com.zimbra.common.mime.InternetAddress");
        Method phP = iaP.getMethod("parseHeader", String.class), gpP = iaP.getMethod("getPersonal");
        Method phO = iaO.getMethod("parseHeader", String.class), gpO = iaO.getMethod("getPersonal");
        for (int i = 0; i < n; i++) {
            int tl = 1 + rnd.nextInt(15);
            StringBuilder t = new StringBuilder();
            for (int j = 0; j < tl; j++) t.append(pool.charAt(rnd.nextInt(pool.length())));
            String name = t.toString().trim(); if (name.isEmpty()) name = "Имя";
            byte[] by = name.getBytes(StandardCharsets.UTF_8);
            int[] cut = new int[1 + rnd.nextInt(2)];
            for (int k = 0; k < cut.length; k++) cut[k] = 1 + rnd.nextInt(Math.max(1, by.length - 1));
            Arrays.sort(cut);
            boolean b64 = rnd.nextBoolean();
            StringBuilder h = new StringBuilder(); int prev = 0;
            for (int k = 0; k <= cut.length; k++) {
                int endi = (k == cut.length) ? by.length : cut[k]; if (endi < prev) endi = prev;
                byte[] frag = Arrays.copyOfRange(by, prev, endi); prev = endi;
                if (k > 0) h.append("\r\n ");
                if (b64) h.append("=?utf-8?B?").append(Base64.getEncoder().encodeToString(frag)).append("?=");
                else { h.append("=?utf-8?Q?"); for (byte x : frag) h.append(String.format("=%02X", x & 0xff)); h.append("?="); }
            }
            h.append(" <user@example.org>");
            String got  = (String) gpP.invoke(((List<?>) phP.invoke(null, h.toString())).get(0));
            String gotO = (String) gpO.invoke(((List<?>) phO.invoke(null, h.toString())).get(0));
            if (!name.equals(got))  { patchFail++; if (patchFail <= 8) System.out.println("FAIL name=["+name+"] got=["+got+"]"); }
            if (!name.equals(gotO)) origMangled++;
        }
        System.out.println("generative: samples="+n+" patchFailures="+patchFail+" origMangled(upstream)="+origMangled);
        System.out.println((diffs == 0 && patchFail == 0) ? "RESULT: OK" : "RESULT: PROBLEM");
    }
}
