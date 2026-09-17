# VoiceNotification

Android の通知・時報・予定・ニュースを日本語で読み上げる常駐アプリ。

## できること

- 通知の読み上げ: 選んだアプリ（LINE など）の通知が来たら、アプリ名と本文を読む
- 時報: 毎時 0 分に時刻と、その後 1 時間以内の予定を読む
- 予定: 端末に同期済みのカレンダー（Google カレンダー含む）の予定を N 分前に読む
- ニュース: RSS（既定は NHK）を一定間隔で取ってきて見出しと概要を読む。音楽再生中のみ、の設定あり
- ニュースの前にチャイム。内蔵のオルゴール音 3 種か、端末内の任意の音声ファイル
- 読み上げ中は音楽の音量を下げる（設定で一時停止に変更可）

## 必要なもの

- JDK 17 以上（Gradle 9 / AGP 9 の要件）
- Android SDK（`local.properties` の `sdk.dir`、既定は `~/Android/Sdk`）
- 端末側: Android 12 (API 31) 以上、日本語 TTS（Google 音声サービス）

## ビルドと入れ方

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk   # 17 以上ならどれでも
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 初回セットアップ（端末側）

アプリを開いて「権限」の項目を上から潰す。

- 通知へのアクセス: 設定画面に飛ぶので VoiceNotification を ON
- カレンダー読み取り: ダイアログで許可
- 正確なアラーム: 設定画面で許可（時報を 0 分ぴったりに鳴らすため）
- 通知の表示: Android 13 以降のみ。常駐通知を出すため

その後「読み上げサービス」を ON にして、下の一覧から読み上げたいアプリにチェック。
「テスト」ボタンで声が出れば OK。

## 構成

```
app/src/main/kotlin/biz/showway/voicenotification/
  App.kt                 通知チャンネル作成
  Prefs.kt               設定（SharedPreferences）
  Speaker.kt             TTS とオーディオフォーカス
  NotificationReader.kt  NotificationListenerService。通知 → 読み上げ
  Scheduler.kt           AlarmManager で時報・予定スキャン・ニュースを発火。Boot 復帰も
  CalendarSource.kt      CalendarContract から予定を取る
  NewsSource.kt          RSS 取得とパース
  Chimes.kt              内蔵チャイムの定義と URI 解決
  VoiceService.kt        常駐フォアグラウンドサービス。アクションを実行して Speaker へ
  MainActivity.kt        設定画面（Compose）
```

## チャイムを作り直す

内蔵チャイムは `tools/gen_chime.py` で MML から合成している（numpy が必要。OGG 化に ffmpeg）。
まず 1 つだけ鳴らして試すのが早い。

```sh
tools/play_chime.sh "t120 l8 o6 c e g > c"
tools/play_chime.sh -i bell -t 1.2 "t150 l16 o6 c <g8.> e8. c2"   # 音色 bell、余韻 1.2 秒
```

WAV が欲しいときは `gen_chime.py -m "..." -o x.wav`。

MML の文法は `t` テンポ、`l` 既定音長、`o` オクターブ、`<` `>` でオクターブ移動、`v` 音量、`r` 休符、`+`/`-` で半音、`.` で付点、`&` でタイ。
詳しくはスクリプト冒頭の docstring を見る。

気に入ったら `CHIMES` の該当エントリの `mml` を書き換えて、全部を再生成する。

```sh
python3 tools/gen_chime.py -o /tmp/chime
for f in /tmp/chime/*.wav; do
  ffmpeg -y -i $f -c:a libvorbis -q:a 5 app/src/main/res/raw/$(basename $f .wav).ogg
done
```

`chime_radio` と `chime_jnr*` は手持ちの参考音源を FFT で解析して、音程と打鍵タイミングを写したもの。

## 既知の注意点

- LINE は「メッセージ通知の内容表示」が ON でないと本文が通知に載らないので読めない
- Doze 中はニュースなどの非正確アラームが遅れることがある。時報は正確なアラームを使う
- 通知アクセスを OFF → ON し直すと NotificationReader が再バインドされる。読まなくなったらまずこれ
