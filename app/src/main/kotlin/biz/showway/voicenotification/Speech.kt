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

    /** max 文字を超えたら、直前の句読点で切って「以下略」を付ける */
    fun truncate(text: String, max: Int): String {
        if (max <= 0 || text.length <= max) return text
        val head = text.take(max)
        val cut = head.indexOfLast { it in "。！？、\n" }
        val body = if (cut >= max / 2) head.take(cut + 1) else head
        return body.trimEnd('、', '\n') + "、以下略。"
    }
}
