#!/usr/bin/env python3
"""内蔵チャイムの合成。numpy だけで動く。

    python3 tools/gen_chime.py [-o 出力ディレクトリ]        # 全チャイムを WAV で出力
    python3 tools/gen_chime.py -m "t120 l8 o6 cdeg" -o x.wav  # MML を 1 つレンダー
    python3 tools/gen_chime.py -m "..." -i bell -o x.wav      # 音色を bell に

MML の文法 (よくある方言のサブセット):
    t<bpm>   テンポ (4 分音符/分)        l<n>  デフォルト音長 (4=4分, 8=8分, 16=16分)
    o<n>     オクターブ (o4 の a = 440Hz)  > <   オクターブ上げ/下げ
    v<0-15>  音量                          r<n>  休符
    c d e f g a b  音名。+ # で半音上げ、- で下げ。後ろに音長と . (付点) を付けられる
    &        タイ (次の同じ音を打ち直さずに伸ばす)   |  小節線 (無視)
    複数トラックを同時に鳴らすときは MML をリストで渡す。
"""
import argparse, os, re, sys
import numpy as np, wave

SR = 44100

INSTRUMENTS = {
    # (倍率, 音量, 減衰時定数) のリストと、打鍵ノイズ量
    "musicbox": dict(partials=[(1.00, 1.00, 0.75), (2.00, 0.30, 0.40), (2.76, 0.25, 0.30), (4.00, 0.12, 0.22), (5.40, 0.08, 0.15)], click=0.15),
    # RadioChime の倍音構成に寄せたベル
    "bell": dict(partials=[(1.00, 1.00, 0.90), (1.89, 0.45, 0.60), (2.52, 0.35, 0.45), (3.00, 0.10, 0.30), (4.20, 0.08, 0.20)], click=0.08),
}

NOTE_BASE = {"c": 0, "d": 2, "e": 4, "f": 5, "g": 7, "a": 9, "b": 11}
TOKEN = re.compile(r"\s*(?:(t|l|o|v)(\d+)|([a-g])([+#\-]*)(\d*)(\.*)(&?)|(r)(\d*)(\.*)|([<>|]))", re.I)

def mml_to_seq(mml):
    """MML → [(midi, start_sec, vel)] と終了時刻"""
    tempo, length, octave, vel = 120, 4, 5, 12
    t = 0.0
    seq = []
    pending_tie = None  # (midi, vel) タイで伸ばし中の音
    pos = 0
    def dur(n, dots):
        d = (60.0 / tempo) * 4 / (n if n else length)
        return d * (2 - 0.5 ** len(dots))
    while pos < len(mml):
        m = TOKEN.match(mml, pos)
        if not m or m.end() == pos:
            raise ValueError("MML parse error at %d: %r" % (pos, mml[pos:pos + 10]))
        pos = m.end()
        cmd, num, note, acc, nlen, dots, tie, rest, rlen, rdots, sym = m.groups()
        if cmd:
            n = int(num)
            if cmd.lower() == "t": tempo = n
            elif cmd.lower() == "l": length = n
            elif cmd.lower() == "o": octave = n
            elif cmd.lower() == "v": vel = n
        elif note:
            midi = 12 * (octave + 1) + NOTE_BASE[note.lower()] + acc.count("+") + acc.count("#") - acc.count("-")
            d = dur(int(nlen) if nlen else 0, dots)
            if pending_tie and pending_tie[0] == midi:
                pass  # 打ち直さない
            else:
                seq.append((midi, t, vel / 15.0))
            pending_tie = (midi, vel) if tie else None
            t += d
        elif rest:
            t += dur(int(rlen) if rlen else 0, rdots)
            pending_tie = None
        elif sym == ">": octave += 1
        elif sym == "<": octave -= 1
    return seq, t

def note(freq, dur, vel, partials, click):
    t = np.arange(int(SR * dur)) / SR
    y = np.zeros_like(t)
    for m, a, tau in partials:
        y += a * np.sin(2 * np.pi * freq * m * t) * np.exp(-t / tau)
    y += np.random.default_rng(int(freq)).normal(0, 1, len(t)) * np.exp(-t / 0.004) * click
    return y * np.minimum(t / 0.003, 1.0) * vel

def midi_hz(n): return 440.0 * 2 ** ((n - 69) / 12)

def render(mml, instrument="musicbox", tail=1.6, reverb=0.5):
    tracks = [mml] if isinstance(mml, str) else list(mml)
    seq, end = [], 0.0
    for tr in tracks:
        s, e = mml_to_seq(tr)
        seq += s; end = max(end, e)
    ins = INSTRUMENTS[instrument]
    out = np.zeros(int(SR * (end + tail + 0.3)))
    for m, s, v in seq:
        n = note(midi_hz(m), tail, v, ins["partials"], ins["click"])
        i = int(SR * s)
        out[i:i + len(n)] += n[:len(out) - i]
    for d, g in ((0.031, 0.25), (0.047, 0.18)):  # ごく軽い残響
        k = int(SR * d)
        rev = np.zeros_like(out)
        for i in range(k, len(out)):
            rev[i] = out[i - k] + g * rev[i - k]
        out += reverb * g * rev
    out /= np.max(np.abs(out)) * 1.05
    fade = int(SR * 0.3)
    out[-fade:] *= np.linspace(1, 0, fade)
    return out

def save(path, y):
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((y * 32767).astype(np.int16).tobytes())

CHIMES = {
    # ちゃんちゃん: G6 → C6
    "chime_two": dict(mml="t143 l8 o6 g c4"),
    # オルゴール アルペジオ
    "chime_arpeggio": dict(mml="t136 l8 o6 c e g > c"),
    # ピンポンパンポン
    "chime_pinpon": dict(mml="t133 l4 o6 e c+ <a> e", tail=1.4),
    # RadioChime 参考: C6 G5 E6 C6 のロール和音。ベル音色
    "chime_radio": dict(mml="t150 l16 o6 c <g8.> e8. c2", instrument="bell", tail=1.6, reverb=0.7),
    # JNRChime 参考: D メジャー、スウィング (長短 2:1)。16 分 = 0.19 秒
    "chime_jnr": dict(mml=(
        "t79 l16 o6 "
        "d8. d8 e16 f+16. f+8. e16 d8 d8. "
        "<b16 a4. b8. a8 b16> "
        "d8 d16 f+8 f+16 e8 e16 f+8 e16 d16. "
        "d16 f+32 a32 > d4"
    ), tail=1.8),
    # JNR 短縮版: 最初のフレーズ + 終止
    "chime_jnr_short": dict(mml=(
        "t79 l16 o6 "
        "d8. d8 e16 f+16. f+8. e16 d8 d8. "
        "<b16 a4."
    ), tail=1.8),
}

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("-m", "--mml", help="この MML だけをレンダーする")
    ap.add_argument("-i", "--instrument", default="musicbox", choices=INSTRUMENTS)
    ap.add_argument("-t", "--tail", type=float, default=1.6, help="最後の音の余韻 (秒)")
    ap.add_argument("-o", "--out", default=".", help="出力ファイル (-m 時) か出力ディレクトリ")
    a = ap.parse_args()
    if a.mml:
        out = a.out if a.out.endswith(".wav") else os.path.join(a.out, "chime.wav")
        save(out, render(a.mml, a.instrument, a.tail)); print(out)
    else:
        os.makedirs(a.out, exist_ok=True)
        for name, spec in CHIMES.items():
            save(os.path.join(a.out, name + ".wav"), render(**spec)); print(name)
