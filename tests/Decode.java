import com.zimbra.common.mime.MimeHeader;

// Targeted cases for MimeHeader.decode(): split multi-octet characters must be
// recovered; everything else must stay byte-for-byte identical to upstream.
// (Cyrillic strings are just example payloads, no real addresses involved.)
public class Decode {
    static String[] cases = new String[] {
        // 0: Q-encoding, one multibyte char split across two adjacent words (fold = CRLF SP)
        "=?utf-8?Q?=D0=A0=D0=A2=D0=A1=2D=D1=82=D0=B5=D0=BD=D0=B4=D0=B5=D1?=\r\n =?utf-8?Q?=80?=",
        // 1: Base64, several multibyte chars split across 5 words
        "=?utf-8?B?0KPQstC10LTQvtC80LvQtdC90LjQtSDQviDQv9C+0LTQv9C4?=\r\n"
        + " =?utf-8?B?0YHQsNC90LjQuCDQv9GA0L7RgtC+0LrQvtC70LAg0LLRgdC10LzQuCDR?=\r\n"
        + " =?utf-8?B?h9C70LXQvdCw0LzQuCDQutC+0LzQuNGB0YHQuNC4LiDQndC10L7QsdGF?=\r\n"
        + " =?utf-8?B?0L7QtNC40LzQviDQvtC/0YPQsdC70LjQutC+0LLQsNGC0Ywg0L/RgNC+?=\r\n"
        + " =?utf-8?B?0YLQvtC60L7Quy4=?=",
        "=?utf-8?Q?=D0=A0?=",                                  // 2: single Q word
        "=?utf-8?B?0J/RgNC40LLQtdGC?=",                        // 3: single B word "Привет"
        "=?utf-8?B?0J/RgNC40LLQtdGC?= =?utf-8?B?0JzQuNGA?=",   // 4: two whole same-charset words (wsp stripped)
        "=?utf-8?Q?=D0=9C?= =?koi8-r?Q?=C9?=",                 // 5: two different-charset words
        "Hello World",                                         // 6: plain ASCII
        "Hello =?utf-8?Q?=D0=9C?= world",                      // 7: literal + encoded word
        "=?utf-8?B?@@@@?=",                                    // 8: invalid -> left literal
        "=?utf-8?Q?=D0=90?=BC",                                // 9: encoded word then literal
    };
    public static void main(String[] a) {
        for (int i = 0; i < cases.length; i++)
            System.out.println(i + "\t[" + MimeHeader.decode(cases[i]) + "]");
    }
}
