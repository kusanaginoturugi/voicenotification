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
- [ ] もう 1 台（tailnet 上の別の Linux 機）にも VOICEVOX を置いて 3 台目の URL にする
- [x] 職場 Arch 機 gallsk にも VOICEVOX を置いた（2026-09-18、docker）
- [x] gallsk を `tailscale serve` で tailnet に公開（2026-09-18、tailnet 内から `/version` 応答を確認）
- [x] gallsk にもニュース要約一式を入れた（2026-09-18、既読は 2 台で分岐する前提で割り切り）
- [x] gallsk の 8090 を `tailscale serve` で公開（2026-09-18、tailnet 越しに 200 と `Last-Modified` を確認）
- [x] アプリの VOICEVOX URL とニュース URL に gallsk を 2 本目として追加（2026-09-18、実機で確認）
- [x] ニュースを LocalLLM で要約してから読む（2026-09-17、実機で VOICEVOX 読み上げまで確認）
- [x] ニュースを時刻指定（9、12、15、18 時）に変更。要約は 5 分前
- [x] 一度要約に使った記事を除外（PC 側で検証済み）
- [x] LocalLLM / PC 障害時のニュース要約フォールバックを実装・デプロイ・実機確認。Cloudflare Worker が Gemma 4 で固定の NHK RSS を要約し、読み辞書を最終適用する。端末トークンと15分キャッシュで個人利用し、Gemini API キーは Worker Secret のみ（2026-09-25）
- [x] 表示名を Mimiyori に変更（2026-09-30）
- [x] 時報の前に NHK 風の時報音（2026-09-30、実機で再生確認。正時とのズレは未計測）
- [x] LLM の崩れた読み注釈（`表記[[読み]]`）による二度読みを防止（2026-09-30、実機確認）
- [ ] 公開対応: Firebase Authentication と App Check / Play Integrity を Worker に追加し、ユーザー単位の利用上限・停止を実装する（#3）
- [x] 時刻指定版の APK を実機に入れた。ニュースのアラームが翌 9:00 に登録されたことを確認
- [ ] 翌日のニュース発火を確認
- [x] チャイムの音量設定（既定 50%）。実機でスライダー表示と試聴を確認
- [x] 実再生先による音量制御・端末TTSのファイル再生・音量カーブによる上限計算を実装。ビルドと単体テスト4件成功（2026-10-01）
- [ ] 本体スピーカーだけ15%に制限し、イヤホンでは通常音量になることを実機確認（全出力15%方式は音楽中に通知が聞こえず撤回。検証状況は `docs/SPEAKER_VOLUME.md`）
- [ ] Claude の実通知で送信者名とアプリ名の重複抑止を実機確認
- [x] 短い読み上げはダッキング、長い読み上げは音楽を一時停止する自動切替（80文字以上、既定）
- [ ] 時報・予定・ニュースの定期発火を実機で長時間確認
- [ ] JNR チャイムの旋律を耳で検証して MML を詰める
- [ ] release ビルドと署名

## 作業記録

### 2026-10-07 LINE通知調査・案内

- 作業計画: [x] 端末履歴で原因確認 → [x] 一律除外を撤去 → [x] README・マニュアル作成 → [x] テスト・端末反映。ユーザー指示により今回のドキュメントをコミット・pushする。
- 引き継ぎ: LINE側のメンション通知設定はユーザー操作が必要。以前からのIssue #7話速同期の未コミット変更は今回に含めない。

- 端末の通知履歴で8:02の対象通知が「あなたをメンションしました: @All」であることを確認。LINEのトーク通知オフでもメンション通知が届く仕様によるもの。サイレント通知が原因という初期推測は撤回。
- Mimiyoriで一律除外する設定を一度実装・端末へ導入したが、通知オンの相手のメンションまで除外するため、ユーザーの指示で撤去。今後はLINE側の「自分へのメンション通知」をオフにする方法を案内する。
- READMEに手順を追加し、利用マニュアル `docs/MANUAL.md` を新設。LINEの設定自体は変更していない。
- 完了: 撤去後の単体テスト8件とdebugビルド成功。Pixel 8aへの更新インストール成功。通知処理は撤去前の元の実装へ復帰。実着信の再確認は未実施。

### 2026-10-05

- ユーザーの指示で、音量制御と通知の送信者名重複抑止の変更をまとめてコミット・pushする。README、作業計画、検証記録を更新
- 最終仕様は本体スピーカーに最大システム音量基準の上限を適用し、確認済み外部出力には追加減衰しない。低い端末音量の二重減衰を避ける。詳細は `docs/SPEAKER_VOLUME.md`
- 10/1にビルド・単体テスト4件・端末インストールを実施済み。Lintは既存のMainActivityの15件のエラーで未通過。今回の保存作業ではコード変更もテスト再実行も行っていない
- 引き継ぎ: 最終版の本体スピーカーの聴感確認は回答待ち。メディア音量MAX、発話中のイヤホン切断、有線/USBオーディオ、Claudeの実通知は未確認のまま残す。次回はこの実機確認から再開する

### 2026-10-01

- 読み上げ音量制限を確認。従来の `Speaker.playbackVolume()` は出力先ではなく、接続中の外部オーディオ機器の有無で判定していた。TTS から実際の出力先は取得できないため、Bluetooth イヤホンが接続状態のまま音が本体スピーカーへ切り替わるケースで上限が外れる可能性がある
- 対策として読み上げ・VOICEVOX・チャイム全てに同じ音量上限を適用。初回は既定 20%、最大 50% としたが、10/1 の実機再生では本体スピーカーで少し大きかったため、ユーザー希望により既定・最大とも 15% へ変更。以前に保存された 20% も読み込み時に 15% へ制限
- `ANDROID_HOME=/home/onoue/Android/Sdk GRADLE_USER_HOME=/tmp/voicenotification-gradle ./gradlew :app:assembleDebug` 成功。デバッグ APK を生成
- ADB サーバーは起動できたが接続端末は 0 台。エミュレーター定義もなく、スピーカーでの実音量とイヤホン取り外し時の切り替えは未確認
- 後に Pixel 8a が接続され、修正版をインストール。保存値 20% と Bluetooth A2DP 接続を確認し、テスト読み上げを起動。VOICEVOX はタイムアウトしキャッシュ音声が準備された。ユーザーから本体スピーカーで聞こえたが少し大きく、15% が適切とのフィードバックあり。15% を新しい上限に変更。時報確認前に ADB 接続が切れた
- 15% 版を Pixel 8a にインストール。既存設定は15%に制限された。テスト読み上げと `CHIME` アクションを起動し、時刻読み上げ用音声のキャッシュ再生ログを確認。ユーザーから本体スピーカーで15%は適切、チャイム→時刻の順に聞こえたと確認
- MessagingStyle 通知の sender がアプリ名と同じ場合、送信者欄を省いて通常テンプレートへ切り替える処理を追加（英字・読み変換後のアプリ名を比較）。LINE の人名 sender は従来どおり。debug ビルド成功し Pixel 8a へインストール。Claude の次の実通知で出力確認は未実施

- 全出力に15%を掛けた修正は、イヤホンで音楽を聴いていると通知が聞こえないため撤回。`MediaPlayer.routedDevices`（Android 16以降）/ `routedDevice`（それ以前）で実再生先を確認する方式に変更。端末TTSも `synthesizeToFile` → 共通MediaPlayerへ統一
- 再生開始直後・出力先不明・本体出力を含む場合は15%、確認済み外部出力なら追加減衰なし。取り外し予告/使用中デバイス削除を受けた発話は最後まで制限を維持。OSのルート通知は非同期であり、切替瞬間の無漏音を保証するものではない
- ビルド・Pixelへのインストール成功。BluetoothでVOICEVOXと端末TTSの双方が `routes=[8] multiplier=1.0` になることを確認。ユーザーも音楽中に聞こえたと回答。端末TTS検証で一時変更したVOICEVOX設定は復元済み。検証詳細は `docs/SPEAKER_VOLUME.md`
- Lintは既存のMainActivityの15件のエラーで失敗。全エラー行がHEADと同じことを照合した。今回の再生処理にLintエラーはない

- 本体への固定15%倍率は、実機のシステム音量5/25（-48.33dB）では二重減衰になり、ユーザーから聞こえないとの報告。`getStreamVolumeDb` と最大音量基準の上限計算へ修正。低い端末音量は増幅・追加減衰せず、高い音量だけ制限。音量変更通知と再生中100msの再確認で更新。計算の単体テスト4件成功。上限計算版を再インストールし、スピーカー低音量でゲイン1.0になるログを確認

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
- LINE の画像・動画・スタンプは、通知の本文が「◯◯が動画を送信しました」という定型文になる。`dataMimeType` や `dataUri` は入っていないので種類は文字列でしか判定できない（端末の `dumpsys notification --noredact` で確認）
  - `Speech.notice` で「◯◯が」を落として「動画を送ってきました」に言い換える
  - 「243件の新規メッセージ」「新しい通知が N 件あります」は読み飛ばす。タイトルが前に付く形もあるので `^(?:.{0,24}。)?` を許す
  - 実機で確認: 定型文 3 種を投げて、言い換え 2 件と読み飛ばし 2 件が意図どおり
- 通知の読み上げが「LINE ゆら うんちんぐ」のように素っ気ないという指摘があり、言い回しをテンプレート化。既定は `{sender}から{app}です。{body}`
  - `Speech.withHonorific` で二重敬称を避ける（さん・くん・ちゃん・様・先生・氏・君で終われば付けない）
  - `Speech.appReading` でパッケージ名から読みを引く（LINE →「ライン」など 11 件）。無ければアプリのラベルをそのまま使う
  - `NotificationReader.extractText` は `(送信者, 本文)` を返すようにした。MessagingStyle なら送信者が取れる
- 長い URL を延々と読まれて辛いという指摘があり、`Speech.kt` を追加。URL →「リンク」、メールアドレス →「メールアドレス」、20 文字以上の英数字の塊は削除。`Speaker.speak` の入口で全経路に掛かる
- 通知だけ `Prefs.notificationMaxChars`（既定 120）で打ち切る。句読点で切って「、以下略。」
- マナーモード（`RINGER_MODE_SILENT` / `VIBRATE`）のときは自動の読み上げを止める設定を追加。既定 ON。画面のボタンからの読み上げと試聴は鳴る。読み上げはメディア音声なので、本来はマナーモードでも鳴ってしまうため明示的に判定している
- ライセンスの要点を README に記載。VOICEVOX は音声の公開・配布時のみクレジットが必要。ずんだもんは個人の非商用ならクレジット不要
- 短い文（60 文字まで）の合成結果を `cacheDir/tts-cache/<話者ID>_<ハッシュ>.wav` にキャッシュ。ハッシュは速度・音量・本文から作る。300 件を超えたら古い順に削除
  - 時報は「午後10時です。」と予定の読み上げを別の文に分けた。前半だけがキャッシュに乗る
  - キャッシュのファイルは再生後に消さない（`RemoteTts.isCached`）。一時ファイルは従来どおり消す
  - 実機で確認: 1 回目 `cached: 1_37d33a6db6dd39e6.wav`、2 回目 `cache hit: ...`
- アイコンを自作に差し替え。`drawable/ic_launcher_background.xml`（藍のグラデーション）と `ic_launcher_foreground.xml`（鈴と音の波、安全領域に収めるため 0.85 倍）。通知バー用は `ic_notification.xml`
- tenkeydrive から流用していた `mipmap-*dpi` の webp は削除。minSdk 31 なので `mipmap-anydpi-v26` のアダプティブアイコンだけで足りる
- アイコンの見え方は、同じパスを SVG にして `rsvg-convert` で描いて確認した
- 話者は `/speakers` から取得してドロップダウンで選ぶ。表示名は `Prefs.ttsSpeakerName` に保存するので、エンジンに繋がらないときも画面に出る
- VOICEVOX の音声はチャイムより元から小さい（実測で実効 -24.2 dB、チャイム 50% が -19.6 dB、チャイム 100% が -13.5 dB）。`audio_query` の `volumeScale` を設定にして既定 1.5 にした。1.5 でピーク 0.85、クリップなし。1.8 でピーク 1.0 に張り付く
- チャイムが大きすぎるとのことで音量設定を追加。`MediaPlayer.setVolume` にスライダーの値（0〜1、5% 刻み）をそのまま渡す線形。VOICEVOX の音声と端末 TTS には効かない
- zsh の `cp` は `cp -i` の alias。スクリプトや非対話実行では `command cp -f` を使う

### 2026-09-18

- 職場の Arch 機 gallsk（16 コア, RTX 3060, tailnet 100.110.75.108）にも VOICEVOX 0.25.2 を導入
- 初手で詰まった点: docker は入っていたが `docker.service` は disabled、`onoue` は `docker` グループ非所属で `/var/run/docker.sock` が `root:docker 660`
- この機械の sudo は `systemctl *`、`pacman -S *`、`pacman -Syu`、`nvidia-smi`、`yay` だけ NOPASSWD。`usermod` も `sudo docker` も対象外
- 一度 rootless podman + quadlet で動かしたが、自宅と職場でコンテナ基盤が分かれて二重管理になるため docker に寄せ直した
- `sudo usermod -aG docker onoue` は手動実行。再起動後に反映を確認。`sudo systemctl enable --now docker` は NOPASSWD で通る
- 起動は README と同じ `docker run -d --name voicevox --restart unless-stopped -p 127.0.0.1:50021:50021 voicevox/voicevox_engine:cpu-latest`
- 検証は `/version` だけでなく `audio_query` → `synthesis`（speaker=3）まで。24kHz mono 16bit の WAV が出た。話者は 43 人、id 3 は ずんだもん ノーマル
- GPU 版は見送り。llama.cpp が 3060 の 12GB のうち 8.4GB を掴んでいて空きが 3GB 程度しかない。自宅と同じ `cpu-latest` にした
- 起動時の復帰は `sudo systemctl restart docker` 後にコンテナが自動で上がり `/version` が返ることまで確認。実際の再起動では未検証
- podman は撤去済み（`podman system reset` と `~/.config/containers`、`~/.local/share/containers` の削除で 2.2GB 回収）。ただしパッケージ本体は `pacman -R` が NOPASSWD 外なので残っている
- `tailscale serve --bg --tcp 50021 tcp://127.0.0.1:50021` は本人が手動実行。エージェントの権限チェックが tailscale コマンドを弾くため
- MagicDNS 名は `gallsk.tailb46b1.ts.net`。短縮名 `gallsk` でも引ける。`curl http://gallsk:50021/version` が `"0.25.2"` を返すところまで確認
- 解除は `tailscale serve --tcp=50021 off`
- ニュース要約一式も gallsk に導入。`llama.cpp.service` は元から動いていて、スクリプト既定の `gemma-4-12b-it-qat-imatrix` もモデル一覧にあった
- `xmllint`、`curl`、`jq` は導入済み。`/usr/bin/python3` は 3.14 の実体で mise の shim ではない
- `voicenews.service` / `voicenews.timer` / `voicenews-http.service` を `~/.config/systemd/user/` に入れて enable。次回発火は 11:55
- `news_summary.sh` を手動実行して 4 秒で要約を生成。`127.0.0.1:8090/news.txt` が `Last-Modified` 付きで返ることまで確認
- 起動直後の順序は未検証。`Persistent=true` の取りこぼし実行が llama-server のモデル読み込み中に走ると要約が空振りしうる。`LLM_TIMEOUT=300` があるので自己回復する見込みだが未確認
- 8090 も `tailscale serve --bg --tcp 8090 tcp://127.0.0.1:8090` で公開。`http://gallsk.tailb46b1.ts.net:8090/news.txt` と `:50021/version` が tailnet 越しに 200 を返すことを確認
- 「会社 Windows 機にも VOICEVOX を置く」は取り下げ。仕事の Windows 11 は貸与端末で管理者権限がなく、エンジンを入れられない。3 台目は tailnet 上の別の Linux 機を当てる
- スマホ側も設定済み。VOICEVOX とニュースの両方に gallsk を 2 本目として追加し、実機で確認。自宅を 1 行目に残してあるので、自宅が生きている間は自宅が使われる
- 2 台構成の割り切り: VOICEVOX はステートレスなので単純な冗長化になるが、ニュースは `seen.txt` がホストごとに独立する。切り替わった直後は既に読んだ記事がもう一度読まれうる。どちらのサーバも常時稼働にはできないため、この重複は許容する判断

### 2026-09-25

- 音楽再生中のオーディオフォーカスを自動切替する設定を追加。既定は本文の整形後の長さが 80 文字以上なら `AUDIOFOCUS_GAIN_TRANSIENT` で一時停止を依頼し、短い時報・通知・チャイムは `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` のままにする
- 「すべての読み上げ中は音楽を一時停止」と「長い読み上げ中は音楽を一時停止」の 2 つの切替を用意。前者がオンなら常に一時停止、両方オフなら常にダッキング
- ダッキング中に長文がキューへ続いた場合も、長文の開始前に一時停止用フォーカスへ昇格する。キュー完了後は従来どおり 400ms 後にフォーカスを返す
- ビルド・実機での一時停止／再開確認は自宅で実施予定
- ニュース要約の LocalLLM に、誤読しやすい固有名詞だけを `[[表記|カタカナ読み]]` で返す指示を追加。`Speech.sanitize` はこの記法をカタカナ読みに置換してから VOICEVOX / 端末 TTS へ渡す。通知は LocalLLM を通さない。`sh -n tools/news_summary.sh` と `assembleDebug` は通過、実際の要約・発話は次回ニュースで確認する
- Cloudflare Worker `voicenews-fallback` をデプロイ。`gemma-4-26b-a4b-it` が要約本文と読み辞書をJSONで返し、Workerが `[[表記|カタカナ]]` に組み立ててAndroidへ返す。Gemini APIキーはWorker Secret、Androidは端末トークンだけを送る
- Workerの `/v1/news` をcurlで確認し、PC側ニュースURLを空にしたPixel 8aの「ニュース」ボタンからWorker要約が再生されることを確認。非常用URL欄に旧複数行設定が残っても先頭URLへ正規化する

### 2026-09-30

- ニュースで「小泉コイズミ」と二度読みする件を調査。gallsk の `docker logs voicevox` に残る audio_query の text から、LocalLLM が `小泉[[コイズミ]]` と `|` なしで返し、`RUBY` にマッチせず素通りしていたと判明（9/28 12時、9/29 15時台）。`Speech.sanitize` で `[[カタカナ]]` だけの注釈は捨てるようにした。表記の範囲は機械的に決められないので読みは諦める。実機で `小泉防衛大臣` になることを確認
- 表示名を Mimiyori に変更（同名の Play アプリがあるため）。`applicationId` は据え置き
- 時報に NHK 風の時報音（440Hz 0.1 秒 ×3、3 秒目に 880Hz）を追加。YouTube の録音を FFT で測って `gen_chime.py` の `render_jiho()` で純音合成。アラームは正時の `JIHO_LEAD_MS`（3 秒）前に発火し、時刻は 1 分先で決める。実機で時報音 → 時刻読み上げの順を確認。正時とのズレは未計測

## 引き継ぎ

### ビルド環境

- JDK 17 以上が必要。`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`
- SDK は `local.properties` の `sdk.dir`（git 管理外）
- 実機は Pixel 8a。`adb install -r app/build/outputs/apk/debug/app-debug.apk`

### 設計メモ

- TTS と MediaPlayer は `Speaker` オブジェクトのキューで直列化。オーディオフォーカスは最初のジョブで取り、キューが空になって 400ms 後に返す。長文ジョブが来たときは、ダッキング用フォーカスから一時停止用フォーカスへ切り替える
- アラームは `AlarmReceiver` が受けて次回を再登録してから `VoiceService` を叩く。サービス停止中でも exact alarm 経由なら FGS 起動が許される
- 予定の重複読み上げ防止は `Prefs.announcedEvents` に `eventId:begin` を保存
- ニュースは「音楽再生中のみ」が既定。`AudioManager.isMusicActive` で判定
- リモート TTS は `Speaker` の `Job.Remote`。合成が終わるまでキューの先頭で待ち、`file` が null なら端末 TTS に落とす
- 平文 HTTP を使うため `usesCleartextTraffic="true"`。Tailscale 内でしか使わない前提
- ニュース URL は改行区切りで複数。レスポンスが `<` で始まれば RSS / Atom としてパース、それ以外はプレーンテキストとしてそのまま読む
- プレーンテキストは `Last-Modified` が 2 時間より古ければ使わない（要約側の停止検知）
- adb からの操作: サービスは非公開だが `adb shell run-as biz.showway.voicenotification am start-foreground-service --user 0 -n biz.showway.voicenotification/.VoiceService -a <ACTION>` なら通る（`--user 0` がないと権限エラー）

### 未確認・既知の問題

- Doze 中の非 exact アラーム（ニュース・予定スキャン）の遅れは未計測
- LINE の通知内容表示が OFF だと本文は読めない
- JNR チャイムは自動採譜なので細部の音の抜け・違いがあり得る
- 要約は LLM なので、事実関係の取り違えや見出しにない補足が混ざる可能性がある
- llama.cpp.service が落ちていると要約が更新されず、古い要約は 2 時間でアプリが見捨てて RSS 読み上げに戻る
- 既読は要約を書いた時点で付く。その回のニュースをアプリが読まなかった（音楽再生中のみ設定で音楽が止まっていた等）場合も、次回は除外される
- 「音楽再生中のみ」が ON のままだと、時刻指定でも音楽を流していない回は読まない
- 時報音の 880Hz が正時からどれだけ遅れるか未計測。ずれるなら `Scheduler.JIHO_LEAD_MS` を調整する
- インストール直後の最初の `CHIME` 起動で、時報音も時刻も鳴らなかったことが 1 回あった。原因未特定
- Worker の `applyReadings` は長い表記から順に `split/join` するので、`小泉進次郎` と `小泉` が両方あると注釈が入れ子になって崩れる（未修正）
