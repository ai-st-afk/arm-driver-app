#!/bin/bash
# Сборка и публикация новой версии приложения для самообновления телефонов.
#
#   tools/release-apk.sh              — собрать, подписать и выложить на шлюз
#   tools/release-apk.sh --no-upload  — только собрать и подписать (проверка)
#
# Выкладка: ARM_DRIVER_SERVER=user@host (куда ходим по ssh), необязательно
# ARM_DRIVER_SSH_PASS_FILE=файл-с-паролем (если вход по паролю, нужен sshpass).
# На сервере APK и latest.json кладутся в ~/apps/arm-driver-app/releases —
# эта папка смонтирована в шлюз (docker-compose.yml), перезапуск не нужен.
#
# Подпись — ключами из ~/.arm-driver (release.jks, release.pass,
# debug.keystore, signing.lineage). Без этой папки опубликовать обновление,
# которое встанет поверх уже установленного приложения, невозможно —
# её резервная копия хранится отдельно от этого Mac.
set -euo pipefail

UPLOAD=1
[ "${1:-}" = "--no-upload" ] && UPLOAD=0

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KEYS="$HOME/.arm-driver"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK"/build-tools/*/ | sort -V | tail -1)"
OUT="$ROOT/android/app/build/publish"
REMOTE_DIR="apps/arm-driver-app/releases"

for f in release.jks release.pass debug.keystore signing.lineage; do
  [ -f "$KEYS/$f" ] || { echo "нет $KEYS/$f — подписать обновление нечем" >&2; exit 1; }
done

GRADLE="${GRADLE:-$(command -v gradle || ls "$HOME"/.gradle/wrapper/dists/gradle-8.9-bin/*/gradle-8.9/bin/gradle | head -1)}"
(cd "$ROOT/android" && "$GRADLE" :app:assembleRelease -q)

rm -rf "$OUT" && mkdir -p "$OUT"
"$BT"zipalign -p -f 4 "$ROOT/android/app/build/outputs/apk/release/app-release-unsigned.apk" "$OUT/aligned.apk"

# Первой подписью — прежний ключ, второй — новый с цепочкой доверия между
# ними: телефоны, где стоит версия на прежнем ключе, принимают обновление.
"$BT"apksigner sign \
  --ks "$KEYS/debug.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android \
  --next-signer --ks "$KEYS/release.jks" --ks-key-alias armdriver --ks-pass file:"$KEYS/release.pass" \
  --lineage "$KEYS/signing.lineage" \
  --out "$OUT/signed.apk" "$OUT/aligned.apk"
"$BT"apksigner verify "$OUT/signed.apk"

BADGING="$("$BT"aapt2 dump badging "$OUT/signed.apk")"
BADGING="${BADGING%%$'\n'*}"
VERSION_CODE="$(sed -E "s/.*versionCode='([0-9]+)'.*/\1/" <<<"$BADGING")"
VERSION_NAME="$(sed -E "s/.*versionName='([^']+)'.*/\1/" <<<"$BADGING")"
APK="arm-driver-$VERSION_NAME.apk"
mv "$OUT/signed.apk" "$OUT/$APK"
rm -f "$OUT/aligned.apk" "$OUT/signed.apk.idsig"

SHA256="$(shasum -a 256 "$OUT/$APK" | cut -d' ' -f1)"
SIZE="$(stat -f%z "$OUT/$APK")"
cat >"$OUT/latest.json" <<EOF
{"version_code":$VERSION_CODE,"version_name":"$VERSION_NAME","file":"$APK","sha256":"$SHA256","size":$SIZE}
EOF

echo "собрано: $OUT/$APK ($VERSION_NAME, код $VERSION_CODE, $((SIZE / 1024 / 1024)) МБ)"

[ "$UPLOAD" = 1 ] || exit 0
: "${ARM_DRIVER_SERVER:?укажите ARM_DRIVER_SERVER=user@host}"

SSH=(ssh)
SCP=(scp -q)
# scp, не rsync: под sshpass rsync падает на setpgid.
if [ -n "${ARM_DRIVER_SSH_PASS_FILE:-}" ]; then
  SSH=(sshpass -f "$ARM_DRIVER_SSH_PASS_FILE" ssh)
  SCP=(sshpass -f "$ARM_DRIVER_SSH_PASS_FILE" scp -q)
fi

"${SSH[@]}" "$ARM_DRIVER_SERVER" "mkdir -p ~/$REMOTE_DIR"
"${SCP[@]}" "$OUT/$APK" "$ARM_DRIVER_SERVER:$REMOTE_DIR/.$APK.tmp"
"${SCP[@]}" "$OUT/latest.json" "$ARM_DRIVER_SERVER:$REMOTE_DIR/.latest.json.tmp"
# Залитое под временными именами подменяется переименованием — атомарно.
# Сначала файл, потом манифест: телефон не должен увидеть версию раньше,
# чем её можно скачать. Три последние версии остаются — на случай отката.
"${SSH[@]}" "$ARM_DRIVER_SERVER" "cd ~/$REMOTE_DIR && chmod 644 .$APK.tmp .latest.json.tmp \
  && mv .$APK.tmp $APK && mv .latest.json.tmp latest.json \
  && ls -t arm-driver-*.apk | tail -n +4 | xargs -r rm -f"
# Тот же файл — для ручной установки на новый телефон.
mkdir -p "$HOME/Desktop/arm-driver-apk"
cp "$OUT/$APK" "$HOME/Desktop/arm-driver-apk/arm-driver-v$VERSION_NAME.apk"
echo "опубликовано: $VERSION_NAME — телефоны заберут при следующем открытии приложения"
