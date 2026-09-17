#!/bin/sh
# MML をその場で鳴らす。最後の引数が MML、それ以外は gen_chime.py にそのまま渡る。
#   tools/play_chime.sh "t79 l16 o6 d8. d8 e16"
#   tools/play_chime.sh -i bell -t 1.2 "t150 l16 o6 c <g8.> e8. c2"
set -e
dir=$(cd "$(dirname "$0")" && pwd)
out=${TMPDIR:-/tmp}/play_chime_$$.wav
trap 'rm -f "$out"' EXIT INT TERM
[ $# -gt 0 ] || { echo "usage: $0 [-i musicbox|bell] [-t tail_sec] \"MML\"" >&2; exit 1; }

# 最後の引数を MML として切り出す
n=$#; i=1; mml=
for a; do
    if [ "$i" -eq "$n" ]; then mml=$a; else set -- "$@" "$a"; fi
    i=$((i + 1))
done
shift "$n"

python3 "$dir/gen_chime.py" "$@" -m "$mml" -o "$out" >/dev/null
mpv --really-quiet "$out"
