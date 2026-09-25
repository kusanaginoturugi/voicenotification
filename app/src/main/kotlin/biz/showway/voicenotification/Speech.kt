package biz.showway.voicenotification

/** 読み上げる前に、声に出すと辛いものを取り除く */
object Speech {
    private val URL = Regex("""(?:https?://|www\.)\S+""")
    private val MAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
    /** 英数字と記号だけの長い塊。ID やハッシュのたぐい */
    private val TOKEN = Regex("""[A-Za-z0-9_\-+/=]{20,}""")
    private val SPACE = Regex("""[ \t　]+""")
    private val BLANK_LINES = Regex("""\n{2,}""")
    /**
     * LocalLLM がニュース本文に付ける読み指定。
     * 例: [[石破茂|イシバシゲル]] → イシバシゲル
     */
    private val RUBY = Regex("""\[\[[^\[\]|]{1,64}\|([ァ-ヺー・]{1,64})]]""")

    fun sanitize(text: String): String = text
        .replace(RUBY) { it.groupValues[1] }
        .replace(URL, "リンク")
        .replace(MAIL, "メールアドレス")
        .replace(TOKEN, "")
        .replace(BLANK_LINES, "\n")
        .replace(SPACE, " ")
        .trim()

    /** 「ゆらが動画を送信しました」のような、本文の代わりに入る定型文 */
    private val SENT = Regex("""^(?:.{1,24}が)?(.{1,16}?)を送信しました。?$""")

    /** 読み上げても意味がない、まとめ通知の定型文 */
    private val USELESS = listOf(
        Regex("""^(?:.{0,24}。)?\d+\s*件の新規メッセージ$"""),
        Regex("""^(?:.{0,24}。)?新しい通知が\s*\d+\s*件あります$"""),
        Regex("""^(?:.{0,24}。)?\d+\s*件の(?:通知|メッセージ)$"""),
    )

    /**
     * 通知の本文を読み上げ向けに直す。読む価値がなければ空文字を返す。
     * LINE は画像やスタンプを送ると本文の代わりに定型文を入れてくるので、
     * 送信者名の重複を取って言い回しを整える。
     */
    fun notice(body: String): String {
        val t = body.trim()
        if (USELESS.any { it.matches(t) }) return ""
        SENT.find(t)?.let { return "${it.groupValues[1]}を送ってきました" }
        return t
    }

    private val HONORIFICS = listOf("さん", "くん", "ちゃん", "様", "さま", "先生", "氏", "君")

    /** 二重敬称を避けて「さん」を付ける */
    fun withHonorific(name: String): String =
        if (name.isBlank() || HONORIFICS.any { name.endsWith(it) }) name else name + "さん"

    /** アプリ名の読み。英字のままだと読みが崩れるものを直す */
    private val READINGS = mapOf(
        "jp.naver.line.android" to "ライン",
        "com.google.android.gm" to "ジーメール",
        "com.google.android.apps.messaging" to "メッセージ",
        "com.google.android.apps.dynamite" to "チャット",
        "com.facebook.orca" to "メッセンジャー",
        "com.discord" to "ディスコード",
        "com.Slack" to "スラック",
        "com.twitter.android" to "エックス",
        "com.anthropic.claude" to "クロード",
        "com.openai.chatgpt" to "チャットジーピーティー",
        "com.google.android.calendar" to "カレンダー",
    )

    fun appReading(pkg: String, label: String): String = READINGS[pkg] ?: label

    /**
     * 通知の読み上げ文を組み立てる。テンプレートの記法は
     * `{app}` アプリ名、`{sender}` 送信者（さん付き）、`{name}` 送信者そのまま、`{body}` 本文
     */
    fun compose(template: String, app: String, sender: String, body: String): String =
        template
            .replace("{app}", app)
            .replace("{sender}", withHonorific(sender))
            .replace("{name}", sender)
            .replace("{body}", body)
            .let { sanitize(it) }

    /** max 文字を超えたら、直前の句読点で切って「以下略」を付ける */
    fun truncate(text: String, max: Int): String {
        if (max <= 0 || text.length <= max) return text
        val head = text.take(max)
        val cut = head.indexOfLast { it in "。！？、\n" }
        val body = if (cut >= max / 2) head.take(cut + 1) else head
        return body.trimEnd('、', '\n') + "、以下略。"
    }
}
