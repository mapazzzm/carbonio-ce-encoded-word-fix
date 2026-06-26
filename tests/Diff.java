import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Random;

// Differential test for MimeHeader: upstream (orig.jar) vs patched (out/).
// decode() and escape() must be identical for every input EXCEPT inputs the
// upstream decoder mangled into U+FFFD, which the patch is allowed to repair.
public class Diff {
    static Method mDecode(ClassLoader cl) throws Exception {
        return cl.loadClass("com.zimbra.common.mime.MimeHeader").getMethod("decode", String.class);
    }
    static Method mEscape(ClassLoader cl) throws Exception {
        return cl.loadClass("com.zimbra.common.mime.MimeHeader").getMethod("escape", String.class, Charset.class, boolean.class);
    }
    public static void main(String[] a) throws Exception {
        URLClassLoader clO = new URLClassLoader(new URL[]{ new File("orig.jar").toURI().toURL() }, null);
        URLClassLoader clP = new URLClassLoader(new URL[]{ new File("out").toURI().toURL(), new File("orig.jar").toURI().toURL() }, null);
        Method decO = mDecode(clO), decP = mDecode(clP);
        Method escO = mEscape(clO), escP = mEscape(clP);

        Random rnd = new Random(20260626L);
        String alpha = "=?utf-8?Bbq UTF KOI8-R abcABC012+/-_=  \t\r\n=?=?==РпрЯé中\"\\(),;:@<>[]";
        int diffs = 0, fixes = 0, total = 0;
        for (int i = 0; i < 200000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(40);
            for (int j = 0; j < len; j++) sb.append(alpha.charAt(rnd.nextInt(alpha.length())));
            String in = sb.toString();
            String ro = (String) decO.invoke(null, in), rp = (String) decP.invoke(null, in);
            total++;
            if (!java.util.Objects.equals(ro, rp)) {
                if (ro != null && ro.indexOf('�') >= 0) fixes++;
                else { diffs++; if (diffs <= 10) System.out.println("DECODE DIFF in=["+in.replace("\r","\\r").replace("\n","\\n").replace("\t","\\t")+"]\n  orig=["+ro+"]\n patch=["+rp+"]"); }
            }
        }
        System.out.println("decode: total="+total+" unexpectedDiffs="+diffs+" fixes(FFFD resolved)="+fixes);

        int diffsEsc = 0;
        Charset[] cs = { StandardCharsets.UTF_8, StandardCharsets.ISO_8859_1, Charset.forName("KOI8-R") };
        for (int i = 0; i < 100000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(30);
            for (int j = 0; j < len; j++) sb.append(alpha.charAt(rnd.nextInt(alpha.length())));
            String in = sb.toString();
            Charset c = cs[rnd.nextInt(cs.length)]; boolean b = rnd.nextBoolean();
            String ro, rp;
            try { ro = (String) escO.invoke(null, in, c, b); } catch (Exception e) { ro = "EX:"+e.getCause(); }
            try { rp = (String) escP.invoke(null, in, c, b); } catch (Exception e) { rp = "EX:"+e.getCause(); }
            if (!java.util.Objects.equals(ro, rp)) { diffsEsc++; if (diffsEsc <= 10) System.out.println("ESCAPE DIFF in=["+in+"] cs="+c+" b="+b+"\n  orig=["+ro+"]\n patch=["+rp+"]"); }
        }
        System.out.println("escape: unexpectedDiffs="+diffsEsc);
        System.out.println(diffs == 0 && diffsEsc == 0 ? "RESULT: OK" : "RESULT: UNEXPECTED DIFFERENCES");
    }
}
