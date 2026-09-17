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
- [ ] Tailscale 経由でスマホから VOICEVOX に届くことを確認
- [ ] 会社 Windows 機にも VOICEVOX を置いて 2 台目の URL にする
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
- 自宅 PC の Tailscale は停止中だった。`sudo tailscale up --operator=$USER` が必要

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

### 未確認・既知の問題

- Doze 中の非 exact アラーム（ニュース・予定スキャン）の遅れは未計測
- LINE の通知内容表示が OFF だと本文は読めない
- JNR チャイムは自動採譜なので細部の音の抜け・違いがあり得る
