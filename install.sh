#!/bin/bash
#
# carbonio-ce-encoded-word-fix — installer
#
# Recovers UTF-8 characters that non-compliant senders split across two adjacent
# RFC 2047 encoded-words (mojibake like "Sender-nam<U+FFFD>" in From/To/Subject).
#
# The fix lives in Carbonio's own MIME code, so it is applied by decompiling the
# locally installed zm-common jar, applying the bundled source patches, recompiling
# the touched classes and repackaging the jar. No Carbonio source is redistributed:
# your own jar is decompiled on your machine.
#
# Re-run after `apt upgrade carbonio-appserver` (it overwrites zm-common-*.jar).
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
JVM="${CARBONIO_JVM:-/opt/zextras/common/lib/jvm/java}"
JARDIR="${CARBONIO_JARDIR:-/opt/zextras/mailbox/jars}"
CFR_URL="https://repo1.maven.org/maven2/org/benf/cfr/0.152/cfr-0.152.jar"
CFR_SHA256="f686e8f3ded377d7bc87d216a90e9e9512df4156e75b06c655a16648ae8765b2"

for t in javac jar java; do
    [ -x "$JVM/bin/$t" ] || { echo "ERROR: $JVM/bin/$t not found (set CARBONIO_JVM)"; exit 1; }
done
command -v patch >/dev/null || { echo "ERROR: 'patch' not installed (apt-get install patch)"; exit 1; }

LIVE="$(ls "$JARDIR"/zm-common-*.jar 2>/dev/null | grep -v '\.bak\.' | head -1)"
[ -n "$LIVE" ] || { echo "ERROR: zm-common-*.jar not found in $JARDIR (set CARBONIO_JARDIR)"; exit 1; }
echo ">> Target jar: $LIVE"

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# 1. CFR decompiler (cached in repo dir, integrity-checked)
CFR="$HERE/cfr-0.152.jar"
if ! { [ -f "$CFR" ] && echo "$CFR_SHA256  $CFR" | sha256sum -c --status; }; then
    echo ">> Downloading CFR 0.152 ..."
    curl -fsSL -o "$CFR" "$CFR_URL"
    echo "$CFR_SHA256  $CFR" | sha256sum -c --status || { echo "ERROR: CFR checksum mismatch"; exit 1; }
fi

# 2. Decompile the two classes from the LOCAL jar
echo ">> Decompiling MimeHeader + InternetAddress ..."
"$JVM/bin/java" -jar "$CFR" "$LIVE" com.zimbra.common.mime.MimeHeader     --outputdir "$WORK/src" >/dev/null 2>&1
"$JVM/bin/java" -jar "$CFR" "$LIVE" com.zimbra.common.mime.InternetAddress --outputdir "$WORK/src" >/dev/null 2>&1
for f in com/zimbra/common/mime/MimeHeader.java com/zimbra/common/mime/InternetAddress.java; do
    [ -f "$WORK/src/$f" ] || { echo "ERROR: decompile failed for $f"; exit 1; }
done

# 3. Apply the source patches
echo ">> Applying patches ..."
patch -p1 -s -d "$WORK/src" < "$HERE/patches/MimeHeader.decode.patch" || {
    echo "ERROR: MimeHeader patch did not apply cleanly."
    echo "       Your Carbonio version likely differs from the one the patch targets."
    echo "       Adapt patches/MimeHeader.decode.patch to the decompiled source manually."
    exit 1; }
patch -p1 -s -d "$WORK/src" < "$HERE/patches/InternetAddress.parse.patch" || {
    echo "ERROR: InternetAddress patch did not apply cleanly (version mismatch)."
    echo "       Adapt patches/InternetAddress.parse.patch manually."
    exit 1; }

# 4. Compile the patched classes against the live jar
echo ">> Compiling ..."
mkdir -p "$WORK/out"
"$JVM/bin/javac" -cp "$LIVE" -d "$WORK/out" \
    "$WORK/src/com/zimbra/common/mime/MimeHeader.java" \
    "$WORK/src/com/zimbra/common/mime/InternetAddress.java"

# 5. Back up and repackage
TS="$(date +%Y%m%d_%H%M%S)"
BK="$LIVE.bak.$TS"
cp -p "$LIVE" "$BK"; echo ">> Backup: $BK"
cp "$LIVE" "$WORK/patched.jar"
( cd "$WORK/out" && "$JVM/bin/jar" uf "$WORK/patched.jar" \
    com/zimbra/common/mime/MimeHeader.class \
    'com/zimbra/common/mime/MimeHeader$HeaderInfo.class' \
    'com/zimbra/common/mime/MimeHeader$EncodedWord.class' \
    com/zimbra/common/mime/InternetAddress.class \
    'com/zimbra/common/mime/InternetAddress$Group.class' )

# preserve original owner/permissions
OWNER="$(stat -c '%U:%G' "$LIVE")"
cp "$WORK/patched.jar" "$LIVE"
chown "$OWNER" "$LIVE"; chmod 755 "$LIVE"
echo ">> Patched jar installed (owner $OWNER)."

cat <<EOF

Done. Restart the mailbox to load the patched classes:

    systemctl restart carbonio-appserver.target
    sleep 20
    chown zextras:zextras /opt/zextras/data/tmp/nginx/client   # Carbonio quirk after restart

Verify on a message from a non-compliant sender:

    su - zextras -c "zmsoap -z -m <mailbox> GetMsgRequest/m/@id=<id>" | grep '<e '
    # the From display name (p="...") should no longer contain U+FFFD ( <U+FFFD> )

Roll back any time with ./uninstall.sh
EOF
