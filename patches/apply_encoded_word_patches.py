#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
carbonio-ce-encoded-word-fix — semantic, idempotent JAR-source patcher
======================================================================
RU: Накладывает правку RFC 2047 «byte-joining» на ДЕКОМПИЛИРОВАННЫЙ (CFR)
    исходник zm-common (классы MimeHeader и InternetAddress), склеивая
    content-октеты соседних одинаково-charset encoded-word и декодируя их вместе,
    чтобы многооктетный символ, разрезанный между двумя encoded-word, не
    превращался в U+FFFD (mojibake «Ртс-тенде<U+FFFD>» в именах From/To/Subject).

    Вместо контекстных .patch-диффов (привязка к номерам строк, падают при
    повторе и при смене версии) — АНКОРНЫЕ строковые замены + идемпотентность:
      • уже пропатченный файл распознаётся по маркеру и ПРОПУСКАЕТСЯ (повторный
        запуск безопасен, в т.ч. поверх старой установки);
      • анкоры — по содержимому, а не по номерам строк (переносимы между версиями
        Carbonio, у которых этот участок кода не менялся; проверено на zm-common
        4.25.2 (CE 26.3) и 4.27.13 (CE 26.6) — исходники идентичны).

EN: Applies the RFC 2047 byte-joining fix to the DECOMPILED (CFR) zm-common
    source (MimeHeader, InternetAddress). Content-octet anchored replacements
    instead of context .patch diffs: idempotent (already-patched file is detected
    and skipped — safe to re-run, incl. over an older install) and tolerant to
    line-number/whitespace shifts across Carbonio versions where this code is
    unchanged (verified on zm-common 4.25.2 / 4.27.13 — identical sources).

Usage: apply_encoded_word_patches.py <decompiled-src-dir>
Exit:  0 = every file applied or already-patched;
       3 = an anchor was not found (Carbonio changed this code — re-derive hunks);
       2 = bad args / IO / data error.
Prints one "STATUS:<APPLIED|ALREADY|NOTFOUND> <file>" line per class.

Данные замен — patches/encoded_word_hunks.json (сгенерированы из локальной
декомпиляции; никакой исходный код Carbonio в них не «изобретён» — это ваши же
байты, приведённые к целевому виду). / The hunk data lives in the sidecar JSON.
"""
import sys, os, json

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, "encoded_word_hunks.json")


def main():
    if len(sys.argv) != 2:
        print("usage: apply_encoded_word_patches.py <src-dir>", file=sys.stderr)
        sys.exit(2)
    src = sys.argv[1]
    try:
        with open(DATA, encoding="utf-8") as fh:
            spec = json.load(fh)
    except OSError as e:
        print("STATUS:ERROR cannot read %s (%s)" % (DATA, e), file=sys.stderr)
        sys.exit(2)

    overall = 0
    for key, entry in spec.items():
        rel = entry["rel"]
        marker = entry["marker"]
        hunks = entry["hunks"]
        path = os.path.join(src, rel)
        try:
            with open(path, encoding="utf-8") as fh:
                c = fh.read()
        except OSError as e:
            print("STATUS:NOTFOUND %s (%s)" % (rel, e)); overall = 3; continue

        # Idempotency: our marker already present -> nothing to do.
        if marker in c:
            print("STATUS:ALREADY %s" % rel); continue

        # Pre-flight: every anchor must be present exactly once, or we refuse to
        # touch the file (a partial patch would corrupt it).
        problem = None
        for h in hunks:
            n = c.count(h["old"])
            if n != 1:
                problem = n
                break
        if problem is not None:
            print("STATUS:NOTFOUND %s (anchor count=%d — Carbonio changed this code)" % (rel, problem))
            overall = 3
            continue

        for h in hunks:
            c = c.replace(h["old"], h["new"], 1)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(c)
        print("STATUS:APPLIED %s" % rel)

    sys.exit(overall)


if __name__ == "__main__":
    main()
