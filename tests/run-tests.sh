#!/bin/bash
#
# Build the patched classes from the LOCAL jar (decompile + patch + compile, no deploy)
# and run the validation suite (targeted + differential + generative) against
# upstream vs patched. Run this on a STOCK jar to see the before/after clearly.
#
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
JVM="${CARBONIO_JVM:-/opt/zextras/common/lib/jvm/java}"
JARDIR="${CARBONIO_JARDIR:-/opt/zextras/mailbox/jars}"
# directory holding the *other* Carbonio jars (dom4j/guava/...) needed at test time;
# usually the same as JARDIR, override only when testing a jar copied elsewhere.
DEPSDIR="${CARBONIO_DEPSDIR:-/opt/zextras/mailbox/jars}"
CFR_URL="https://repo1.maven.org/maven2/org/benf/cfr/0.152/cfr-0.152.jar"
CFR_SHA256="f686e8f3ded377d7bc87d216a90e9e9512df4156e75b06c655a16648ae8765b2"

LIVE="$(ls "$JARDIR"/zm-common-*.jar 2>/dev/null | grep -v '\.bak\.' | head -1)"
[ -n "$LIVE" ] || { echo "zm-common jar not found in $JARDIR"; exit 1; }

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
cp "$LIVE" "$WORK/orig.jar"

CFR="$REPO/cfr-0.152.jar"
if ! { [ -f "$CFR" ] && echo "$CFR_SHA256  $CFR" | sha256sum -c --status; }; then
    curl -fsSL -o "$CFR" "$CFR_URL"; echo "$CFR_SHA256  $CFR" | sha256sum -c --status
fi

echo ">> decompile + patch + compile"
"$JVM/bin/java" -jar "$CFR" "$WORK/orig.jar" com.zimbra.common.mime.MimeHeader     --outputdir "$WORK/src" >/dev/null 2>&1
"$JVM/bin/java" -jar "$CFR" "$WORK/orig.jar" com.zimbra.common.mime.InternetAddress --outputdir "$WORK/src" >/dev/null 2>&1
patch -p1 -s -d "$WORK/src" < "$REPO/patches/MimeHeader.decode.patch"
patch -p1 -s -d "$WORK/src" < "$REPO/patches/InternetAddress.parse.patch"
mkdir -p "$WORK/out"
"$JVM/bin/javac" -cp "$WORK/orig.jar" -d "$WORK/out" \
    "$WORK/src/com/zimbra/common/mime/MimeHeader.java" \
    "$WORK/src/com/zimbra/common/mime/InternetAddress.java"
"$JVM/bin/javac" -cp "$WORK/orig.jar" -d "$WORK" "$HERE"/Decode.java "$HERE"/Diff.java "$HERE"/Addr.java

cd "$WORK"
echo; echo "=== Decode (targeted, patched) ==="
"$JVM/bin/java" -Dfile.encoding=UTF-8 -cp "out:orig.jar:." Decode
echo; echo "=== Diff (MimeHeader: upstream vs patched, 300k random) ==="
"$JVM/bin/java" -Dfile.encoding=UTF-8 -cp "out:orig.jar:." Diff
echo; echo "=== Addr (InternetAddress: 150k differential + 40k split names) ==="
"$JVM/bin/java" -Dfile.encoding=UTF-8 -Ddeps.dir="$DEPSDIR" -cp "out:orig.jar:." Addr
