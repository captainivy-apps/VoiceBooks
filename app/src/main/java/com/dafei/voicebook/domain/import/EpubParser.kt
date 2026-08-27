package com.dafei.voicebook.domain.import

import com.dafei.voicebook.data.model.ParsedBook
import nl.siegmann.epublib.domain.Date
import nl.siegmann.epublib.domain.Resource
import nl.siegmann.epublib.epub.EpubReader
import org.jsoup.Jsoup
import java.io.File
import java.io.FileInputStream

class EpubParser : EbookParser {
    override fun parse(file: File, format: com.dafei.voicebook.data.model.BookFormat): ParsedBook {
        return parseEpub(file)
    }

    fun parse(file: File): ParsedBook = parseEpub(file)

    private fun parseEpub(file: File): ParsedBook {
        FileInputStream(file).use { input ->
            val book = try {
                EpubReader().readEpub(input)
            } catch (e: Exception) {
                throw ImportErrorMessages.EpubParseException(e)
            }
            val title = book.title ?: file.nameWithoutExtension
            val author = book.metadata.authors.firstOrNull()?.let {
                listOfNotNull(it.firstname, it.lastname).joinToString(" ")
            } ?: ""
            val metadata = book.metadata
            val summary = metadata.descriptions.firstOrNull()?.let { Jsoup.parse(it).text() } ?: ""
            val publisher = metadata.publishers.firstOrNull() ?: ""
            val publishedDate = metadata.dates
                .firstOrNull { it.event == Date.Event.PUBLICATION }?.value
                ?: metadata.dates.firstOrNull()?.value
                ?: ""
            val language = metadata.language.orEmpty()
            val isbn = metadata.identifiers
                .firstOrNull { it.scheme?.contains("ISBN", ignoreCase = true) == true }
                ?.value
                ?: ""
            val subjects = metadata.subjects.joinToString("，")

            val textBuilder = StringBuilder()
            book.spine.spineReferences.forEach { ref ->
                val resource = ref.resource ?: return@forEach
                extractText(resource)?.let { textBuilder.append(it).append('\n') }
            }

            val coverBytes = book.coverImage?.data

            return ParsedBook(
                title = title,
                author = author,
                summary = summary,
                textContent = textBuilder.toString().trim(),
                coverBytes = coverBytes,
                publisher = publisher,
                publishedDate = publishedDate,
                language = language,
                isbn = isbn,
                subjects = subjects,
            )
        }
    }

    private fun extractText(resource: Resource): String? {
        return try {
            val html = String(resource.data, Charsets.UTF_8)
            Jsoup.parse(html).text()
        } catch (_: Exception) {
            null
        }
    }
}
