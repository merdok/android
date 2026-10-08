package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings

/** The seven severity words of a notice row. The five that ask for attention carry the one accent dot;
 *  Unavailable and Resolved carry none, because there is nothing to do. [wire] is the `data-severity`
 *  value the stylesheet and the JavaScript renderer (`HaNotice` in i18n.js) share. */
internal enum class NoticeSeverity(val wire: String) {
    ACTION_NEEDED("action_needed"),
    WARNING("warning"),
    WORKING("working"),
    UPDATE("update"),
    NOTE("note"),
    UNAVAILABLE("unavailable"),
    RESOLVED("resolved");

    val wordKey: String get() = "shell.notice.severity.$wire"
}

/** One `:8888` advisory row: severity word, title, quiet detail, one action at the end. Every argument
 *  but [severity] is HTML the caller has already escaped; the JavaScript twin is `HaNotice.row`, and
 *  NoticeTest pins that both draw the same markup. */
internal fun notice(
    severity: NoticeSeverity,
    strings: AppStrings,
    title: String,
    detail: String = "",
    action: String = "",
    attrs: String = "",
): String = buildString {
    append("""<div class="notice" data-severity="${severity.wire}"$attrs>""")
    append("""<span class="notice-pill">${esc(strings.get(severity.wordKey))}</span>""")
    append("""<span class="notice-text">""")
    if (title.isNotEmpty()) append("""<b class="notice-title">$title</b>""")
    if (title.isNotEmpty() && detail.isNotEmpty()) append(' ')
    if (detail.isNotEmpty()) append("""<span class="notice-detail">$detail</span>""")
    append("</span>")
    if (action.isNotEmpty()) append("""<span class="notice-action">$action</span>""")
    append("</div>")
}
