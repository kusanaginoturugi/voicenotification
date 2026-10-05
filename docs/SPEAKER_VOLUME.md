# 本体スピーカーの音量制限

## 仕様

- 本体スピーカーの上限は端末最大音量での振幅に対する割合（既定15%、5〜15%）。端末全体のメディア音量は変更しない。Androidの `getStreamVolumeDb` で現在/最大のdB値を取得し、ゲインを `min(1, limit × 10^((最大dB − 現在dB)/20))` とする。すでに上限より小さければ追加減衰しない。ミュートは維持し、音量カーブを取得できない場合は固定の設定倍率で制限する。
- Bluetooth・有線・USBなどの確認済み外部出力では追加減衰しない。チャイム固有の音量とVOICEVOX合成音量は別に適用される。
- 接続中機器一覧で出力先を推測しない。VOICEVOX・端末TTS・チャイムを共通のMediaPlayerで再生し、その実再生先を調べる。
- 端末TTSはファイル合成が成功してから再生する。失敗時に直接 `speak()` へ戻して制限を回避する経路は設けない。完了・停止・再生エラー時に一時ファイルを削除する。
- 再生開始前はルートを取得できないため、開始時は制限する。外部出力を確認できた時点で通常音量へ戻す。
- Android 16以降は複数の実再生先を調べ、本体が含まれる場合は制限する。Android 12〜15は取得できる単一の実再生先を使う。出力先不明・未対応のデバイス種別も制限する。
- ルート変更とMediaRouterの音量変更通知で音量を更新。再生中のみ100ms間隔でも再確認する。イヤホン取り外し予告（AUDIO_BECOMING_NOISY）または使用中デバイス削除を受けた発話は、その後の古いルート情報による音量復帰を避けるため終了まで制限する。

Androidのルート通知は非同期。切替瞬間に一切音が漏れないという保証はできない。開始前の制限と取り外し通知への対応に加え、実端末で切替を検証する。

## 検証（2026-10-01、Pixel 8a / Android 17）

| 条件 | 結果 |
| --- | --- |
| Debug APKビルド・インストール | 成功 |
| Bluetooth、VOICEVOX | `routes=[] multiplier=0.15` → `routes=[8] multiplier=1.0`。ユーザーより少し小さめだが音楽中にも聞こえたとの回答 |
| Bluetooth、端末TTS | `local synthesis ready` → `routes=[8] multiplier=1.0`。ファイル合成と共通プレーヤーを実機確認 |
| 本体スピーカー、固定倍率版（撤回） | Bluetooth切断後のルートは正しく `[2]`。ただし端末音量5/25にさらに15%を掛けて聞こえなかったため、音量カーブに基づく上限処理へ変更 |
| 本体スピーカー、上限計算版 | `routes=[2] index=5/25 db=-48.33 maxDb=0 ceiling=0.15 multiplier=1.0`。二重減衰を解消。聴感はユーザー確認待ち |
| 上限計算の単体テスト | 4件成功。最大時15%、小音量では追加減衰なし、dB全域の合成ゲイン上限、ミュート/無効値を検証 |
| 読み上げ中のイヤホン切断 | 未確認（今回確認したのはBluetooth切断後に始まる発話） |
| 本体メディア音量MAX | 未確認。検証開始時の本体STREAM_MUSICは5/25。システム音量は変更していない |
| 有線・USBオーディオ | 未確認（USB接続はADB用） |
| Lint | MainActivityの既存15件のエラーで失敗。全エラー行がHEADと同じことを確認 |

端末TTS検証ではVOICEVOX URLを一時的に空にし、検証後に元のURL設定を復元してアプリを再起動した。ログに本文・トークンは出さず、再生先の種別とゲインを記録する。

参考: [MediaPlayerの再生先と変更通知](https://developer.android.com/reference/android/media/MediaPlayer)、[イヤホン取り外し予告](https://developer.android.com/reference/android/media/AudioManager#ACTION_AUDIO_BECOMING_NOISY)、[TTS合成完了](https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener#onDone(java.lang.String))。

最初の固定倍率版では本体音量が5/25（-48.33dB）で、追加の15%減衰により聞こえなかった。端末音量が低いまま聞こえるよう自動的にシステム音量を上げることはしない。上限計算版では通常の端末音量をそのまま使い、大音量のときだけ減衰させる。
