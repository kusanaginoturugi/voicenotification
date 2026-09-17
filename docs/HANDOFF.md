# 作業記録と引き継ぎ

## 目的

Android で以下を日本語 TTS で読み上げる常駐アプリ。

1. LINE などの通知の読み上げ
2. 時報と Google カレンダーの予定の読み上げ
3. YouTube Music などを聞いている最中に、30 分に 1 回程度ニュースを流す（前にチャイム）

## 計画と進捗

- [x] Kotlin + Compose で骨組み（2026-09-17）
- [x] 通知読み上げ（NotificationListenerService）
- [x] 時報・予定（AlarmManager + CalendarContract）
- [x] ニュース（RSS 取得 → TTS）
- [x] チャイム。内蔵 6 種 + 任意ファイル指定。MML から合成
- [x] Pixel 8a（Android 17）で TTS 動作確認
- [x] VOICEVOX 対応（複数 URL フェイルオーバー、端末 TTS フォールバック）
- [x] Tailscale 経由でスマホから VOICEVOX に届くことを確認（2026-09-17、ずんだもんで読み上げ成功）
- [ ] 会社 Windows 機にも VOICEVOX を置いて 2 台目の URL にする
- [x] ニュースを LocalLLM で要約してから読む（2026-09-17、実機で VOICEVOX 読み上げまで確認）
- [x] ニュースを時刻指定（9、12、15、18 時）に変更。要約は 5 分前
- [x] 一度要約に使った記事を除外（PC 側で検証済み）
- [x] 時刻指定版の APK を実機に入れた。ニュースのアラームが翌 9:00 に登録されたことを確認
- [ ] 翌日のニュース発火を確認
- [x] チャイムの音量設定（既定 50%）。実機でスライダー表示と試聴を確認
- [ ] 時報・予定・ニュースの定期発火を実機で長時間確認
- [ ] JNR チャイムの旋律を耳で検証して MML を詰める
- [ ] release ビルドと署名

## 作業記録

### 2026-09-17

- `~/src/tenkeydrive` から gradle wrapper とバージョンカタログを流用して新規作成
- lint の NewApi エラーを避けるため minSdk を 26 → 31 に変更
- `QUERY_ALL_PACKAGES` をやめて `<queries>` に置換
- Pixel 8a に adb でインストール。権限は adb から付与（`pm grant`、`appops set`、`cmd notification allow_listener`）
- テスト読み上げの音声を確認済み
- チャイム追加。Speaker をチャイム → TTS の直列キューに変更
- 参考音源 2 つ（RadioChime、JNRChime）を FFT で解析して音程とタイミングを写した
- 生成スクリプトを MML ベースに置き換え
- 自宅 PC に VOICEVOX 0.25.2 を Docker で起動（`127.0.0.1:50021`、`--restart unless-stopped`）
- `RemoteTts` を追加。audio_query → synthesis の 2 段 API。文単位に分割して合成と再生をパイプライン
- 自宅 PC の Tailscale は停止中だった。`sudo tailscale up` と `sudo tailscale set --operator=onoue` を実施
- `tailscale serve --bg --tcp 50021 tcp://127.0.0.1:50021` で tailnet 内に公開
- スマホの Tailscale ログインは Firefox が既定ブラウザだと反応しない。Chromium に変えて解決
- `File.createTempFile` の接頭辞が 2 文字以下で例外になり、ずっと端末 TTS に落ちていたバグを修正
- RSS をそのまま読むと長いので、PC 側で要約する構成にした
  - `tools/news_summary.sh` が RSS → llama-server（`gemma-4-12B-it-qat`、127.0.0.1:8080）→ `~/.local/share/voicenews/news.txt`
  - systemd ユーザーユニット `voicenews.timer`（20 分おき）と `voicenews-http.service`（127.0.0.1:8090）を有効化。linger は元から yes
  - `tailscale serve --path` のディレクトリ配信は operator でも root が要るので、python の http.server を TCP で中継する形にした
  - gemma-4 が思考トークンで `max_tokens` を使い切り content が空になったので `enable_thinking:false` を指定。要約は 2〜5 秒
- NHK の RSS は `news.web.nhk` にリダイレクトされる。curl は `-L` が要る
- ニュースを 9、12、15、18 時の時刻指定に変更。アプリは `Prefs.newsHours`、PC のタイマーは `OnCalendar=08,11,14,17:55`
- 既読管理: guid（なければ link）を `~/.local/state/voicenews/seen.txt` に追記、2000 行で切り詰め。要約の書き出しに成功してから既読にする
- NHK の主要ニュース（cat0）は常に 7 件で、公開時刻は 4 時間ほどに散らばる。3 時間おきなら毎回数件が新規になる
- 要約テストが 3 分以上止まった。llama-server は `--models-max 1` で、Firefox 拡張が `gemma-4-12b-it-qat-imatrix` を連続で叩いている最中に別モデルを指定すると入れ替え待ちで進まない。既定モデルを imatrix 版に変更し、curl にタイムアウトを付けた
- `gemma-4-12B-it-qat` のプリセットは `n-predict = 256` で要約が切れうる。imatrix 版は制限なし、`reasoning = off`
- チャイムが大きすぎるとのことで音量設定を追加。`MediaPlayer.setVolume` にスライダーの値（0〜1、5% 刻み）をそのまま渡す線形。VOICEVOX の音声と端末 TTS には効かない
- zsh の `cp` は `cp -i` の alias。スクリプトや非対話実行では `command cp -f` を使う

## 引き継ぎ

### ビルド環境

- JDK 17 以上が必要。`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`
- SDK は `local.properties` の `sdk.dir`（git 管理外）
- 実機は Pixel 8a。`adb install -r app/build/outputs/apk/debug/app-debug.apk`

### 設計メモ

- TTS と MediaPlayer は `Speaker` オブジェクトのキューで直列化。オーディオフォーカスは最初のジョブで取り、キューが空になって 400ms 後に返す
- アラームは `AlarmReceiver` が受けて次回を再登録してから `VoiceService` を叩く。サービス停止中でも exact alarm 経由なら FGS 起動が許される
- 予定の重複読み上げ防止は `Prefs.announcedEvents` に `eventId:begin` を保存
- ニュースは「音楽再生中のみ」が既定。`AudioManager.isMusicActive` で判定
- リモート TTS は `Speaker` の `Job.Remote`。合成が終わるまでキューの先頭で待ち、`file` が null なら端末 TTS に落とす
- 平文 HTTP を使うため `usesCleartextTraffic="true"`。Tailscale 内でしか使わない前提
- ニュース URL は改行区切りで複数。レスポンスが `<` で始まれば RSS / Atom としてパース、それ以外はプレーンテキストとしてそのまま読む
- プレーンテキストは `Last-Modified` が 2 時間より古ければ使わない（要約側の停止検知）
- adb からの操作: サービスは非公開なので `am start-foreground-service` は通らない。画面を点けて `uiautomator dump` でボタン位置を取り `input tap` する

### 未確認・既知の問題

- Doze 中の非 exact アラーム（ニュース・予定スキャン）の遅れは未計測
- LINE の通知内容表示が OFF だと本文は読めない
- JNR チャイムは自動採譜なので細部の音の抜け・違いがあり得る
- 要約は LLM なので、事実関係の取り違えや見出しにない補足が混ざる可能性がある
- llama.cpp.service が落ちていると要約が更新されず、古い要約は 2 時間でアプリが見捨てて RSS 読み上げに戻る
- 既読は要約を書いた時点で付く。その回のニュースをアプリが読まなかった（音楽再生中のみ設定で音楽が止まっていた等）場合も、次回は除外される
- 「音楽再生中のみ」が ON のままだと、時刻指定でも音楽を流していない回は読まない
