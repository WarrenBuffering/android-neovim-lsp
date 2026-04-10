package dev.codex.kotlinls.index

import org.jetbrains.kotlin.com.intellij.psi.PsiComment
import org.jetbrains.kotlin.kdoc.psi.api.KDoc
import org.jetbrains.kotlin.kdoc.psi.impl.KDocSection
import org.jetbrains.kotlin.kdoc.psi.impl.KDocTag

object KDocMarkdownRenderer {
    fun render(comment: PsiComment?): String? = render(comment as? KDoc)

    fun render(kdoc: KDoc?): String? {
        if (kdoc == null) return null
        val defaultSection = kdoc.getDefaultSection()
        val sections = buildList {
            val description = sanitize(defaultSection.content)
            if (description.isNotBlank()) {
                add(description)
            }
            addListSection("Parameters", defaultSection.findTagsByName("param"))
            addListSection("Receiver", defaultSection.findTagsByName("receiver"))
            addListSection("Properties", defaultSection.findTagsByName("property"))
            addListSection("Constructor", defaultSection.findTagsByName("constructor"))
            addSingleSection("Returns", defaultSection.findTagByName("return"))
            addListSection("Throws", defaultSection.findTagsByName("throws") + defaultSection.findTagsByName("exception"))
            addListSection("See Also", defaultSection.findTagsByName("see"))
            addListSection("Samples", defaultSection.findTagsByName("sample"))
            addListSection("Authors", defaultSection.findTagsByName("author"))
            addSingleSection("Since", defaultSection.findTagByName("since"))
        }.filter { it.isNotBlank() }
        return sections.joinToString(separator = "\n\n").trim().ifBlank { null }
    }

    private fun MutableList<String>.addListSection(
        title: String,
        tags: List<KDocTag>,
    ) {
        if (tags.isEmpty()) return
        val rendered = tags.map(::renderTagLine).filter { it.isNotBlank() }
        if (rendered.isEmpty()) return
        add(
            buildString {
                append("### ")
                append(title)
                append('\n')
                append(rendered.joinToString("\n"))
            },
        )
    }

    private fun MutableList<String>.addSingleSection(
        title: String,
        tag: KDocTag?,
    ) {
        val content = sanitize(tag?.content)
        if (content.isBlank()) return
        add(
            buildString {
                append("### ")
                append(title)
                append('\n')
                append(content)
            },
        )
    }

    private fun renderTagLine(tag: KDocTag): String {
        val subject = tag.subjectName?.trim().orEmpty()
        val content = sanitize(tag.content)
        return when {
            subject.isNotBlank() && content.isNotBlank() -> "- `$subject`: $content"
            subject.isNotBlank() -> "- `$subject`"
            content.isNotBlank() -> "- $content"
            else -> ""
        }
    }

    private fun sanitize(text: String?): String {
        val normalized = text
            .orEmpty()
            .replace("\r\n", "\n")
            .lineSequence()
            .joinToString("\n") { it.trimEnd() }
            .trim()
        if (normalized.isBlank()) return ""
        return normalized
            .replace(KDOC_LINK_WITH_LABEL_REGEX) { match ->
                val label = match.groupValues[1].ifBlank { match.groupValues[2] }
                "`$label`"
            }
            .replace(KDOC_LINK_REGEX) { match -> "`${match.groupValues[1]}`" }
    }

    private val KDocSection.content: String
        get() = getContent()

    private val KDocTag.content: String
        get() = getContent()

    private val KDocTag.subjectName: String?
        get() = getSubjectName()

    private val KDOC_LINK_WITH_LABEL_REGEX = Regex("""\[(.+?)]\[(.+?)]""")
    private val KDOC_LINK_REGEX = Regex("""\[(.+?)](?!\()""")
}
