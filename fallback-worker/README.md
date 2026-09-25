# VoiceNews fallback Worker

自宅・職場 PC の LocalLLM が使えないときだけ、NHK の主要ニュースを Gemma 4 で要約する Cloudflare Worker。

端末は `GET /v1/news?count=7` に `Authorization: Bearer <端末トークン>` を付けて呼ぶ。任意 URL や任意プロンプトは受け付けず、NHK RSS と読み上げ用プロンプトは Worker に固定している。要約は 15 分 Cloudflare Cache に保持する。

## デプロイ

Cloudflare にログイン済みの端末で実行する。`DEVICE_TOKEN` は 32 バイト以上のランダム値にする。これは Android の非常用 API 設定に入れる値で、Gemini API キーではない。

```sh
cd fallback-worker
npm install
npx wrangler secret put GEMINI_API_KEY
npx wrangler secret put DEVICE_TOKEN
npx wrangler deploy
```

表示された `https://voicenews-fallback.<account>.workers.dev/v1/news` を Android の「非常用クラウド要約 API」に、`DEVICE_TOKEN` と同じ値を「非常用 API の端末トークン」に設定する。

通常のニュース URL 欄には、PC の `news.txt` と既定の NHK RSS を入れておく。アプリは PC の URL がすべて失敗したときに Worker を試し、Worker も失敗したら NHK RSS を読む。

## 秘密情報

`GEMINI_API_KEY` と `DEVICE_TOKEN` をリポジトリ、`wrangler.jsonc`、APK に入れない。ローカル確認には `.dev.vars` を使い、これは Git 管理しない。

この端末トークンは公開アプリ向けの認証ではない。公開時には Firebase Authentication と App Check / Play Integrity で正規ユーザー・正規端末を検証し、利用量制御を追加する。
