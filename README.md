# VoiceNotification

Android の通知・時報・予定・ニュースを日本語で読み上げる常駐アプリ。

## できること

- 通知の読み上げ: 選んだアプリ（LINE など）の通知が来たら、アプリ名と本文を読む。URL やメールアドレス、長い ID は読み飛ばし、長すぎる通知は途中で打ち切る
- 時報: 毎時 0 分に時刻と、その後 1 時間以内の予定を読む
- 予定: 端末に同期済みのカレンダー（Google カレンダー含む）の予定を N 分前に読む
- ニュース: 決まった時刻（既定は 9、12、15、18 時）にニュースを読む。PC 側で LocalLLM が要約したテキストか、RSS（既定は NHK）の見出しと概要。音楽再生中のみ、の設定あり
- ニュースの前にチャイム。内蔵 6 種か、端末内の任意の音声ファイル。音量はチャイムだけ別に調整できる（既定 50%）
- 読み上げ中は音楽の音量を下げる（設定で一時停止に変更可）
- マナーモード中は自動の読み上げをしない（既定。画面のボタンからは鳴る）
- 音声合成を VOICEVOX に切り替えられる。URL を複数登録して生きてる方を使い、全滅なら端末の TTS

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

## VOICEVOX で読み上げる

端末の Google TTS より自然な声にしたいとき。自宅 PC などで VOICEVOX エンジンを動かして、Tailscale 経由でスマホから叩く。

PC 側:

```sh
docker run -d --name voicevox --restart unless-stopped \
  -p 127.0.0.1:50021:50021 voicevox/voicevox_engine:cpu-latest
sudo tailscale up --operator=$USER
tailscale serve --bg --tcp 50021 tcp://127.0.0.1:50021   # tailnet 内に 50021 を公開
curl -s localhost:50021/speakers | jq '.[] | {name, styles: [.styles[] | {id, name}]}'   # 話者 ID 一覧
```

スマホ側: Tailscale アプリを入れて同じ tailnet にログインし、アプリの「音声合成（VOICEVOX）」に
`http://<PC の MagicDNS 名>:50021` を書く。複数行書けば上から順に試す。「接続テスト」で疎通を見られる。

- 話者は「話者を選ぶ」を押すとエンジンから一覧を取ってきて、名前で選べる（43 人 127 スタイル）。既定は ずんだもん ノーマル
- 速度は 1.0 が等速
- 音量は VOICEVOX の `volumeScale`。素の出力はチャイムより小さいので既定は 1.5。1.8 を超えると歪むので上限で止めている
- 長文は文単位に分けて、次の文を合成しながら前の文を再生する
- 60 文字までの短い文は合成結果を端末にキャッシュする。時報のように毎回同じ文は 2 回目から合成なしで鳴り、PC が落ちていても鳴る。話者・速度・音量を変えると別のファイルになる。設定画面の「音声キャッシュを消す」で全部消せる
- 全 URL が落ちていれば自動で端末の TTS に戻る。接続タイムアウトは 1.5 秒なので待ちは短い
- エンジンは平文 HTTP なので Tailscale の外に出さないこと

## ニュースを LocalLLM で要約する

RSS をそのまま読むと長いので、PC 側で llama-server に要約させたテキストを読ませる。

```
voicenews.timer (8:55 11:55 14:55 17:55) → tools/news_summary.sh
  → RSS 取得 → 未使用の記事だけ選ぶ → llama-server で 3〜4 文に要約
  → ~/.local/share/voicenews/news.txt → voicenews-http (127.0.0.1:8090) → tailscale serve → スマホ
```

一度要約に使った記事の guid は `~/.local/state/voicenews/seen.txt` に記録して、次回からは除外する。
新しい記事が 1 件も無ければ「前回から新しいニュースはありません。」を書く。
既読をリセットしたいときは `seen.txt` を消す。

PC 側のセットアップ（curl、jq、xmllint、python3 と、OpenAI 互換 API の llama-server が必要）:

```sh
cp tools/systemd/voicenews.service tools/systemd/voicenews.timer tools/systemd/voicenews-http.service ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now voicenews.timer voicenews-http.service
tailscale serve --bg --tcp 8090 tcp://127.0.0.1:8090
```

スマホ側: ニュースの URL 欄に 1 行 1 つで書く。上から順に試す。

```
http://<PC の MagicDNS 名>:8090/news.txt
https://www.nhk.or.jp/rss/news/cat0.xml
```

- 要約だけ試すなら `tools/news_summary.sh && cat ~/.local/share/voicenews/news.txt`
- 要約の時刻はアプリ側のニュース時刻に合わせて、`tools/systemd/voicenews.timer` の `OnCalendar` を 5 分前に揃える
- RSS、モデル、件数、プロンプトは環境変数 `RSS_URLS`（空白区切りで複数可）`LLM_URL` `LLM_MODEL` `NEWS_COUNT` `PROMPT` で差し替える。常用するなら `systemctl --user edit voicenews.service` で `Environment=` を足す
- 既定モデルは `gemma-4-12b-it-qat-imatrix`。日本語 imatrix 量子化で、思考モードがオフ。llama-server は同時に 1 モデルしか載せない設定なので、ブラウザ拡張と同じモデルにしてモデル入れ替えの待ちを避けている
- 思考モードが有効なモデルだと出力が空になるので、スクリプトで `enable_thinking:false` を渡している
- LLM は 300 秒でタイムアウト。長さ制限で切れたら最後の「。」までに揃える
- `news.txt` が 2 時間より古ければ要約が止まっているとみなして、アプリは次の URL（RSS）に回す
- PC が落ちていても RSS に落ちるので、ニュース自体は止まらない

## 構成

```
app/src/main/kotlin/biz/showway/voicenotification/
  App.kt                 通知チャンネル作成
  Prefs.kt               設定（SharedPreferences）
  Speaker.kt             TTS とオーディオフォーカス
  NotificationReader.kt  NotificationListenerService。通知 → 読み上げ
  Scheduler.kt           AlarmManager で時報・予定スキャン・ニュースを発火。Boot 復帰も
  CalendarSource.kt      CalendarContract から予定を取る
  NewsSource.kt          ニュース取得。RSS はパース、プレーンテキストはそのまま。複数 URL のフェイルオーバー
  Speech.kt              読み上げ前の整形。URL やメールアドレスの置換と長さの打ち切り
  Chimes.kt              内蔵チャイムの定義と URI 解決
  RemoteTts.kt           VOICEVOX API で合成して WAV を取る。複数 URL のフェイルオーバー
  VoiceService.kt        常駐フォアグラウンドサービス。アクションを実行して Speaker へ
  MainActivity.kt        設定画面（Compose）
```

## play_chime.sh の使い方

MML を書いてその場で鳴らすツール。numpy と mpv が必要。

```sh
tools/play_chime.sh "t79 l16 o6 d8. d8 e16"
tools/play_chime.sh -i bell "t150 l16 o6 c <g8.> e8. c2"
tools/play_chime.sh -t 0.8 "t143 l8 o6 g c4"
```

最後の引数が MML。それより前のオプションは `gen_chime.py` にそのまま渡る。

| オプション | 意味 | 既定 |
| --- | --- | --- |
| `-i musicbox` / `-i bell` | 音色。オルゴールかベル | `musicbox` |
| `-t 秒` | 最後の音の余韻 | `1.6` |

WAV ファイルとして残したいときは `python3 tools/gen_chime.py -m "MML" -o x.wav`。

### MML 早見表

| 書き方 | 意味 |
| --- | --- |
| `t120` | テンポ。4 分音符が 1 分に 120 |
| `l8` | 以降の既定音長。4 なら 4 分、8 なら 8 分、16 なら 16 分 |
| `o6` | オクターブ。`o4` の `a` が 440Hz。C6 は `o6 c` |
| `>` `<` | オクターブを 1 つ上げる / 下げる |
| `v12` | 音量。0〜15、既定 12 |
| `c d e f g a b` | 音名。`c8` のように音長を付けられる。付けなければ `l` の値 |
| `c+` `c#` `d-` | 半音上げ / 下げ |
| `c8.` | 付点。長さ 1.5 倍。`..` で 1.75 倍 |
| `c8&c8` | タイ。打ち直さずに伸ばす |
| `r8` | 休符 |
| `\|` | 小節線。無視される |

例: `t79 l16 o6 d8. d8 e16 f+16. f+8.` は D6 を付点 8 分、D6 を 8 分、E6 を 16 分、F♯6 を付点 16 分、F♯6 を付点 8 分。

### 内蔵チャイムの MML

`tools/gen_chime.py` の `CHIMES` にある。今の値はこれ。

```
chime_two        t143 l8 o6 g c4
chime_arpeggio   t136 l8 o6 c e g > c
chime_pinpon     t133 l4 o6 e c+ <a> e
chime_radio      t150 l16 o6 c <g8.> e8. c2                    (bell)
chime_jnr        t79 l16 o6 d8. d8 e16 f+16. f+8. e16 d8 d8. <b16 a4. b8. a8 b16> d8 d16 f+8 f+16 e8 e16 f+8 e16 d16. d16 f+32 a32 > d4
chime_jnr_short  t79 l16 o6 d8. d8 e16 f+16. f+8. e16 d8 d8. <b16 a4.
```

`chime_radio` と `chime_jnr*` は手持ちの参考音源を FFT で解析して、音程と打鍵タイミングを写したもの。

## チャイムを作り直す

`play_chime.sh` で納得したら、`CHIMES` の該当エントリの `mml` を書き換えて全部を再生成し、OGG にしてビルドし直す。

```sh
python3 tools/gen_chime.py -o /tmp/chime
for f in /tmp/chime/*.wav; do
  ffmpeg -y -i $f -c:a libvorbis -q:a 5 app/src/main/res/raw/$(basename $f .wav).ogg
done
./gradlew assembleDebug
```

## ライセンスと利用上の注意

このリポジトリのコードは自分用。VOICEVOX は同梱していない（HTTP で叩くだけ）ので、
再配布にはあたらない。ただし合成した音声を扱うときは、次の 2 つの規約に従う。

- [VOICEVOX 利用規約](https://voicevox.hiroshiba.jp/term/): 商用・非商用問わず利用できる。
  **作成した音声を公開・配布するときは、VOICEVOX を使ったと分かるクレジット表記が必要**。
  自分の端末で聞くだけなら不要。ソフトウェアの無断再配布とリバースエンジニアリングは禁止
- 各音声ライブラリ（キャラクター）の規約。既定で使っている ずんだもん は
  [東北ずん子・ずんだもんプロジェクトのガイドライン](https://zunko.jp/guideline.html)。
  個人の非商用利用なら申請もクレジットも不要。政治・宗教関連や公序良俗に反する用途は禁止

つまり、この用途（自分の端末で聞くだけ）ならクレジット不要。
読み上げた音声を配信や動画に載せるなら「VOICEVOX:ずんだもん」のような表記を入れる。
話者を変えたときは、そのキャラクターの規約を確認すること。

## 読み上げの整形

`Speech.sanitize` が全ての読み上げに掛かる。

| 元の文字列 | 読み上げ |
| --- | --- |
| `https://meet.google.com/abc-defg-hij?authuser=0` | リンク |
| `taro@example.co.jp` | メールアドレス |
| `8H2K9SLDJF02KDLSJF83KDLS`（20 文字以上の英数字） | 読まない |

### 通知の言い回し

既定はこう読む。

```
ゆらさんからラインです。うんちんぐ
ジーメールです。請求書の件 ご確認ください        ← 送信者が分からない通知
```

設定画面で言い回しを変えられる。使える置き換えは 4 つ。

| 記法 | 中身 |
| --- | --- |
| `{app}` | アプリ名。LINE は「ライン」のように読みを直したものを使う |
| `{sender}` | 送信者。「さん」を付ける。既に敬称が付いていれば二重にしない |
| `{name}` | 送信者そのまま |
| `{body}` | 本文 |

「言い回しを試す」ボタンで、その場で聞いて確かめられる。

通知はさらに「最大文字数」で打ち切る（既定 120 文字）。
句読点の位置で切って「、以下略。」を付ける。0 にすると無制限。

## 既知の注意点

- LINE は「メッセージ通知の内容表示」が ON でないと本文が通知に載らないので読めない
- Doze 中はニュースなどの非正確アラームが遅れることがある。時報は正確なアラームを使う
- 通知アクセスを OFF → ON し直すと NotificationReader が再バインドされる。読まなくなったらまずこれ
