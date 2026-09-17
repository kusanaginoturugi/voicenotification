#!/bin/sh
# RSS を取って LocalLLM (llama-server の OpenAI 互換 API) で読み上げ用に要約し、テキストファイルに書く。
# 環境変数で差し替え可。systemd の voicenews.timer から定期実行する想定。
set -eu

RSS_URL=${RSS_URL:-https://www.nhk.or.jp/rss/news/cat0.xml}
LLM_URL=${LLM_URL:-http://127.0.0.1:8080/v1/chat/completions}
LLM_MODEL=${LLM_MODEL:-gemma-4-12B-it-qat}
NEWS_COUNT=${NEWS_COUNT:-7}
NEWS_OUT=${NEWS_OUT:-$HOME/.local/share/voicenews/news.txt}
PROMPT=${PROMPT:-'以下は日本のニュース見出しと概要の一覧です。音声で読み上げるために、全体を 3〜4 文の自然な日本語にまとめてください。固有名詞は残し、数字は算用数字で。箇条書きや記号、Markdown は使わず、文だけを出力してください。冒頭は「ニュースです。」で始めてください。'}

tmp=$(mktemp -t voicenews.XXXXXX)
trap 'rm -f "$tmp" "$tmp.xml"' EXIT

curl -fsSL -A 'Mozilla/5.0' "$RSS_URL" -o "$tmp.xml"
items=$(xmllint --xpath "//item[position()<=$NEWS_COUNT]" "$tmp.xml" | sed 's/<[^>]*>//g' | tr -s ' \n' | head -c 6000)
[ -n "$items" ] || { echo "no items in $RSS_URL" >&2; exit 1; }

jq -n --arg m "$LLM_MODEL" --arg sys "$PROMPT" --arg u "$items" \
  '{model:$m, messages:[{role:"system",content:$sys},{role:"user",content:$u}],
    temperature:0.3, max_tokens:600, chat_template_kwargs:{enable_thinking:false}}' |
  curl -fsS "$LLM_URL" -H 'Content-Type: application/json' -d @- |
  jq -r '.choices[0].message.content' | sed '/^[[:space:]]*$/d' > "$tmp"

[ -s "$tmp" ] || { echo "empty summary" >&2; exit 1; }
mkdir -p "$(dirname "$NEWS_OUT")"
mv "$tmp" "$NEWS_OUT"
trap - EXIT; rm -f "$tmp.xml"
