#!/bin/sh
# RSS を取って LocalLLM (llama-server の OpenAI 互換 API) で読み上げ用に要約し、テキストファイルに書く。
# 一度要約に使った記事 (guid / link) は SEEN_FILE に記録して、次回からは除外する。
# 環境変数で差し替え可。systemd の voicenews.timer から定期実行する想定。
set -eu

RSS_URLS=${RSS_URLS:-${RSS_URL:-https://www.nhk.or.jp/rss/news/cat0.xml}}   # 空白区切りで複数可
LLM_URL=${LLM_URL:-http://127.0.0.1:8080/v1/chat/completions}
LLM_MODEL=${LLM_MODEL:-gemma-4-12b-it-qat-imatrix}
LLM_TIMEOUT=${LLM_TIMEOUT:-300}
NEWS_COUNT=${NEWS_COUNT:-7}
NEWS_OUT=${NEWS_OUT:-$HOME/.local/share/voicenews/news.txt}
SEEN_FILE=${SEEN_FILE:-$HOME/.local/state/voicenews/seen.txt}
SEEN_KEEP=${SEEN_KEEP:-2000}
NO_NEWS_TEXT=${NO_NEWS_TEXT:-前回から新しいニュースはありません。}
PROMPT=${PROMPT:-'以下は日本のニュース見出しと概要の一覧です。音声で読み上げるために、全体を 3〜4 文の自然な日本語にまとめてください。固有名詞は残し、数字は算用数字で。箇条書きや記号、Markdown は使わず、文だけを出力してください。冒頭は「ニュースです。」で始めてください。

固有名詞など、漢字の読みを誤るおそれがある語だけは、必ず [[表記|カタカナの読み]] の形で注釈してください。例: [[石破茂|イシバシゲル]]。表記は入力にある文字をそのまま使い、読みは全角カタカナ（長音符・中黒は可）だけにしてください。注釈は必要な語だけにし、文や意味を書き換えないでください。'}

work=$(mktemp -d -t voicenews.XXXXXX)
trap 'rm -rf "$work"' EXIT
mkdir -p "$(dirname "$NEWS_OUT")" "$(dirname "$SEEN_FILE")"
touch "$SEEN_FILE"
: > "$work/items.txt"
: > "$work/ids.txt"

# 未使用の記事を先頭から NEWS_COUNT 件集める
picked=0
for url in $RSS_URLS; do
    [ "$picked" -lt "$NEWS_COUNT" ] || break
    curl -fsSL --max-time 30 -A 'Mozilla/5.0' "$url" -o "$work/feed.xml" || { echo "fetch failed: $url" >&2; continue; }
    n=$(xmllint --xpath 'count(//item)' "$work/feed.xml" 2>/dev/null || echo 0)
    i=1
    while [ "$i" -le "$n" ] && [ "$picked" -lt "$NEWS_COUNT" ]; do
        id=$(xmllint --xpath "string(//item[$i]/guid)" "$work/feed.xml")
        [ -n "$id" ] || id=$(xmllint --xpath "string(//item[$i]/link)" "$work/feed.xml")
        if [ -n "$id" ] && ! grep -qxF "$id" "$SEEN_FILE" "$work/ids.txt"; then
            title=$(xmllint --xpath "string(//item[$i]/title)" "$work/feed.xml")
            desc=$(xmllint --xpath "string(//item[$i]/description)" "$work/feed.xml")
            printf '%s\n%s\n\n' "$title" "$desc" >> "$work/items.txt"
            printf '%s\n' "$id" >> "$work/ids.txt"
            picked=$((picked + 1))
        fi
        i=$((i + 1))
    done
done

if [ "$picked" -eq 0 ]; then
    printf '%s\n' "$NO_NEWS_TEXT" > "$work/out.txt"
else
    jq -n --arg m "$LLM_MODEL" --arg sys "$PROMPT" --rawfile u "$work/items.txt" \
      '{model:$m, messages:[{role:"system",content:$sys},{role:"user",content:$u}],
        temperature:0.3, max_tokens:600, chat_template_kwargs:{enable_thinking:false}}' |
      curl -fsS --max-time "$LLM_TIMEOUT" "$LLM_URL" -H 'Content-Type: application/json' -d @- > "$work/resp.json"
    jq -r '.choices[0].message.content // ""' "$work/resp.json" | sed '/^[[:space:]]*$/d' > "$work/out.txt"
    [ -s "$work/out.txt" ] || { echo "empty summary" >&2; exit 1; }
    # 長さ制限で切れていたら、最後の「。」までに揃えて文の途中で終わらないようにする
    if [ "$(jq -r '.choices[0].finish_reason' "$work/resp.json")" = length ]; then
        text=$(cat "$work/out.txt")
        case $text in *。*) printf '%s。\n' "${text%。*}" > "$work/out.txt" ;; esac
        echo "summary truncated by length" >&2
    fi
fi

# 書き出しに成功してから既読にする
mv "$work/out.txt" "$NEWS_OUT"
cat "$work/ids.txt" >> "$SEEN_FILE"
tail -n "$SEEN_KEEP" "$SEEN_FILE" > "$work/seen.txt" && cat "$work/seen.txt" > "$SEEN_FILE"
echo "picked $picked item(s)" >&2
