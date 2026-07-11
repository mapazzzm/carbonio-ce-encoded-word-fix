# carbonio-ce-encoded-word-fix

[<sup>ru</sup> Русский](#русский) | [<sup>en</sup> English](#english)

Восстановление многобайтовых UTF‑8 символов, которые некорректные отправители режут между
двумя соседними **RFC 2047 encoded‑word**, из‑за чего в заголовках писем Carbonio появляются
«кракозябры» (`Имя‑отправителя`**`�`** вместо `Имя‑отправитель`).

Recovers multi‑octet UTF‑8 characters that non‑compliant senders split across two adjacent
**RFC 2047 encoded‑words**, which otherwise turns sender/recipient names (and subjects) into
mojibake (`Sender‑nam`**`�`** instead of `Sender‑name`) in Carbonio.

Протестировано на / Tested on **Carbonio CE 26.3** (`zm-common-4.25.2.jar`) и **26.6** (`zm-common-4.27.13.jar`), Ubuntu 22.04 / 24.04, JDK 21. Идемпотентно — безопасно перезапускать / idempotent, safe to re-run.

---

## Русский

### Симптом

В веб‑клиенте и в SOAP‑ответах имя отправителя/получателя или тема показываются с символом
`�` (U+FFFD), например `РТС-тенде�� <no-reply@example.org>`. Встречается у части массовых
рассылок (банковские/торговые/госплощадки и т. п.) — редко, но в любом ящике.

### Причина

Отправитель нарушает **RFC 2047 §5**: режет двухбайтовый UTF‑8 символ ровно на границе между
двумя соседними `encoded-word`. Пример сырого `From` (фолдинг = CRLF + пробел):

```
From: =?utf-8?Q?=D0=A0=D0=A2=D0=A1=2D=D1=82=D0=B5=D0=BD=D0=B4=D0=B5=D1?=
 =?utf-8?Q?=80?= <no-reply@example.org>
```

Первое слово заканчивается байтом `D1`, второе начинается с `80`; вместе `D1 80` = «р».
Декодер, обрабатывающий каждое `encoded-word` отдельно, получает невалидную половинку UTF‑8 →
`�` в каждом слове. **Исходные байты теряются**, поэтому чинить можно только на сервере в точке
парсинга — в UI/JS уже нечего восстанавливать.

> RFC 2047 §6.2 прямо допускает «лояльное» декодирование: соседние `encoded-word` одной кодировки
> склеиваются **на уровне байтов** до charset‑конверсии. Так делают Gmail, Thunderbird, Outlook.
> Carbonio так не делает — это и исправляет патч.

### Что чинит

| Где | Класс / механизм | Стоковое поведение | Решение |
|---|---|---|---|
| **Имена `From`/`To`/`Cc`** (поля `p`/`d` в SOAP) | `com.zimbra.common.mime.InternetAddress.parse` — свой inline‑декодер | разрезанный символ → `�` | октет‑склейка соседних `encoded-word` одной кодировки в имени |
| **Прочие заголовки через** `com.zimbra.common.mime.MimeHeader.decode` | общий декодер заголовков | то же | та же октет‑склейка |
| **Subject и др., декодируемые JavaMail** | `MimeUtility.decodeText` | то же | **не входит в JAR‑патч** — лечится штатным флагом JVM (см. ниже) |

> **Тема письма (Subject)** в Carbonio обычно декодируется через JavaMail, а не через эти классы.
> Для неё включите штатный флаг (отдельно от этого патча, переживает обновления):
> ```
> zmlocalconfig -e mailboxd_java_options="$(zmlocalconfig -m nokey mailboxd_java_options) -Dmail.mime.decodetext.strict=false"
> systemctl restart carbonio-appserver.target
> ```

### Как работает патч

Изменения минимальны и держатся в двух классах `zm-common`:

- **`MimeHeader.decode(byte[],int,int,Charset)`** — добавлены package‑private помощники
  `encodedWordCharset` / `encodedWordOctets` / `decodeRunOctets` (используют публичные
  `HeaderUtils.decodeB2047/decodeQ2047`). Октет‑склейка включается **только** для слов, которые
  и оригинальный `decodeWord` декодирует успешно → для одиночного слова результат **байт‑в‑байт**
  как в стоке.
- **`InternetAddress.parse(...)`** — у имени отправителя свой inline‑декодер; он получил ту же
  октет‑склейку. «Run» сбрасывается на кавычках/комментариях/`<`, чтобы не склеить через
  не‑пробельный контент.

`install.sh` **декомпилирует ваш собственный jar** (CFR 0.152), применяет патчи из `patches/`,
перекомпилирует затронутые классы и пересобирает jar. Исходный код Carbonio **не входит** в этот
репозиторий — декомпиляция происходит на вашей машине.

### Установка

```bash
git clone https://github.com/mapazzzm/carbonio-ce-encoded-word-fix.git
cd carbonio-ce-encoded-word-fix
sudo ./install.sh                                  # бэкап + патч jar (без рестарта)
systemctl restart carbonio-appserver.target
sleep 20
chown zextras:zextras /opt/zextras/data/tmp/nginx/client   # особенность Carbonio после рестарта
```

Требуется `patch`, `curl` и JDK из комплекта Carbonio (`/opt/zextras/common/lib/jvm/java`).

**Переустанавливайте после `apt upgrade carbonio-appserver`** — обновление перезаписывает
`zm-common-*.jar`.

### Проверка

```bash
su - zextras -c "zmsoap -z -m <ящик> GetMsgRequest/m/@id=<id>" | grep '<e '
#  ОК:    <e p="Имя-отправитель" ... t="f"/>
#  баг:   <e p="Имя-отправите��" ... t="f"/>
```

### Тесты

```bash
tests/run-tests.sh        # запускать на СТОКОВОМ jar, чтобы видеть «до/после»
```

- `Decode` — целевые кейсы для `MimeHeader.decode`;
- `Diff` — дифференциал 300k случайных входов: патч идентичен стоку везде, кроме починки `�`;
- `Addr` — дифференциал 150k + генеративный 40k (случайные имена, разрезанные по случайным
  байтовым границам): патч восстанавливает 100%, сток ломает ~60%.

### Откат

```bash
sudo ./uninstall.sh       # восстановит самый свежий zm-common-*.jar.bak.*
systemctl restart carbonio-appserver.target
```

### Безопасность

Патч обрабатывает недоверенные байты заголовков. Аллокации ограничены длиной заголовка,
исключения charset обёрнуты в `try/catch`, новых циклов нет; октет‑склейка включается только
для слов, уже принятых стоковым декодером. Худший случай — O(N²) на длине одного «run», но он
ограничен лимитом размера заголовков MTA (≈100 КБ → ~40 мс) и не является вектором DoS.

### Лицензия

Производная работа от **Carbonio Community Edition** (`zm-common`, © Zextras), под
**GNU AGPL‑3.0** — см. [LICENSE](LICENSE). Репозиторий содержит только наши изменения (`patches/`)
и инструменты; исходный код Carbonio не распространяется.

---

## English

### Symptom

In the web client and SOAP responses the sender/recipient display name or the subject shows a
`�` (U+FFFD), e.g. `RTS-tende�� <no-reply@example.org>`. Seen with some bulk senders (banking /
trading / government portals, etc.) — rare, but in any mailbox.

### Cause

The sender violates **RFC 2047 §5**: it splits a two‑byte UTF‑8 character exactly on the boundary
between two adjacent `encoded-word`s. Raw `From` (folding = CRLF + space):

```
From: =?utf-8?Q?=D0=A0=D0=A2=D0=A1=2D=D1=82=D0=B5=D0=BD=D0=B4=D0=B5=D1?=
 =?utf-8?Q?=80?= <no-reply@example.org>
```

The first word ends with byte `D1`, the second starts with `80`; together `D1 80` is one Cyrillic
letter. A decoder that decodes each `encoded-word` independently gets an invalid UTF‑8 half →
`�` per word. **The original bytes are lost**, so the only place to fix it is server‑side at parse
time — there is nothing left to recover in the UI/JS.

> RFC 2047 §6.2 explicitly allows lenient decoding: adjacent `encoded-word`s of the same charset
> are joined **at the byte level** before charset conversion. Gmail, Thunderbird and Outlook do
> this; Carbonio does not — which is what this patch fixes.

### What it fixes

| Where | Class / mechanism | Stock behaviour | Fix |
|---|---|---|---|
| **`From`/`To`/`Cc` names** (`p`/`d` in SOAP) | `com.zimbra.common.mime.InternetAddress.parse` — its own inline decoder | split char → `�` | byte‑level join of adjacent same‑charset `encoded-word`s in the name |
| **Other headers via** `com.zimbra.common.mime.MimeHeader.decode` | generic header decoder | same | same byte‑level join |
| **Subject etc. decoded by JavaMail** | `MimeUtility.decodeText` | same | **not in the JAR patch** — use the stock JVM flag below |

> The **Subject** in Carbonio is usually decoded via JavaMail, not via these classes. For it,
> enable the stock flag (independent of this patch, survives upgrades):
> ```
> zmlocalconfig -e mailboxd_java_options="$(zmlocalconfig -m nokey mailboxd_java_options) -Dmail.mime.decodetext.strict=false"
> systemctl restart carbonio-appserver.target
> ```

### How the patch works

Minimal changes contained in two `zm-common` classes:

- **`MimeHeader.decode(byte[],int,int,Charset)`** — adds package‑private helpers
  `encodedWordCharset` / `encodedWordOctets` / `decodeRunOctets` (built on the public
  `HeaderUtils.decodeB2047/decodeQ2047`). Byte‑joining kicks in **only** for words the original
  `decodeWord` already accepts, so a single word decodes **byte‑for‑byte** like upstream.
- **`InternetAddress.parse(...)`** — the display‑name path has its own inline decoder; it gets the
  same byte‑joining. The run is reset on quotes/comments/`<` so it never joins across non‑whitespace.

`install.sh` **decompiles your own jar** (CFR 0.152), applies the fix with a **semantic, idempotent
patcher** (`patches/apply_encoded_word_patches.py` — anchored string replacements, not line-numbered
context diffs), recompiles the touched classes and repackages the jar. No Carbonio source is shipped
in this repo — the decompilation happens on your machine.

The patcher is **safe to re-run**: an already-patched source is detected (by method marker) and
skipped, so running the installer twice, or over an older install without an `apt upgrade` in between,
is a no-op instead of an error. Anchors match code *content*, so they tolerate line/whitespace shifts
across Carbonio versions where this MIME code is unchanged (verified identical on `zm-common-4.25.2`
= CE 26.3 and `zm-common-4.27.13` = CE 26.6). If Carbonio ever changes the code, the installer prints
`NOTFOUND` and leaves your jar untouched rather than corrupting it. The legacy `patches/*.patch` files
are kept for reference only. Runs on Ubuntu 22.04 and 24.04.

### Install

```bash
git clone https://github.com/mapazzzm/carbonio-ce-encoded-word-fix.git
cd carbonio-ce-encoded-word-fix
sudo ./install.sh                                  # backup + patch jar (no restart)
systemctl restart carbonio-appserver.target
sleep 20
chown zextras:zextras /opt/zextras/data/tmp/nginx/client   # Carbonio quirk after restart
```

Needs `python3`, `curl` and the Carbonio‑bundled JDK (`/opt/zextras/common/lib/jvm/java`).

**Re‑run after `apt upgrade carbonio-appserver`** — the upgrade overwrites `zm-common-*.jar`.

### Verify

```bash
su - zextras -c "zmsoap -z -m <mailbox> GetMsgRequest/m/@id=<id>" | grep '<e '
#  OK:   <e p="Sender-name" ... t="f"/>
#  bug:  <e p="Sender-nam��" ... t="f"/>
```

### Tests

```bash
tests/run-tests.sh        # run on a STOCK jar to see the before/after
```

- `Decode` — targeted cases for `MimeHeader.decode`;
- `Diff` — 300k random inputs: the patch is identical to upstream except it repairs `�`;
- `Addr` — 150k differential + 40k generative (random names split at random byte boundaries):
  the patch reconstructs 100%, upstream mangles ~60%.

### Rollback

```bash
sudo ./uninstall.sh       # restores the most recent zm-common-*.jar.bak.*
systemctl restart carbonio-appserver.target
```

### Security

The patch processes untrusted header bytes. Allocations are bounded by the header length, charset
exceptions are wrapped in `try/catch`, no new loops are introduced, and byte‑joining only applies
to words already accepted by the stock decoder. Worst case is O(N²) in a single run length, but it
is bounded by the MTA header‑size limit (~100 KB → ~40 ms) and is not a DoS vector.

### License

Derivative work of **Carbonio Community Edition** (`zm-common`, © Zextras), under
**GNU AGPL‑3.0** — see [LICENSE](LICENSE). This repository ships only our changes (`patches/`) and
tooling; no Carbonio source code is redistributed.
