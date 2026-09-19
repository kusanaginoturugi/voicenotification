package biz.showway.voicenotification

/** 読み上げる前に、声に出すと辛いものを取り除く */
object Speech {
    private val URL = Regex("""(?:https?://|www\.)\S+""")
    private val MAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.-]+""")
    /** 英数字と記号だけの長い塊。ID やハッシュのたぐい */
    private val TOKEN = Regex("""[A-Za-z0-9_\-+/=]{20,}""")
    private val SPACE = Regex("""[ \t　]+""")
    private val BLANK_LINES = Regex("""\n{2,}""")

    fun sanitize(text: String): String = text
        .replace(URL, "リンク")
        .replace(MAIL, "メールアドレス")
        .replace(TOKEN, "")
        .replace(BLANK_LINES, "\n")
        .replace(SPACE, " ")
        .trim()

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
