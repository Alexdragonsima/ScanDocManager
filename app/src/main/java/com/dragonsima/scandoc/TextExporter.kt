package com.dragonsima.scandoc

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Экспортирует распознанный текст в TXT и DOCX.
 * DOCX формируется вручную: ZIP-архив с минимально валидной структурой.
 */
object TextExporter {

    private const val TAG = "TextExporter"
    private const val DOCUMENTS_FOLDER = "Documents"

    /**
     * Счётчик для гарантии уникальности имён файлов,
     * если два экспорта произойдут в одну секунду.
     */
    private val fileCounter = AtomicLong(0L)

    // ==================== ПУБЛИЧНОЕ API ====================

    /**
     * Сохраняет текст в .txt файл.
     */
    fun saveAsTxt(context: Context, text: String): File {
        val file = newDocumentFile(context, "txt")
        file.writeText(text, Charsets.UTF_8)
        if (BuildConfig.DEBUG) Log.d(TAG, "TXT сохранён: ${file.name}")
        return file
    }

    /**
     * Сохраняет текст в минимальный валидный .docx файл.
     *
     * DOCX — это ZIP со строго определённой структурой. Мы создаём
     * только обязательные части:
     *  - [Content_Types].xml
     *  - _rels/.rels
     *  - word/document.xml
     */
    fun saveAsDocx(context: Context, text: String): File {
        val file = newDocumentFile(context, "docx")

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            addZipEntry(zip, "[Content_Types].xml", CONTENT_TYPES_XML)
            addZipEntry(zip, "_rels/.rels", RELS_XML)
            addZipEntry(zip, "word/document.xml", buildDocumentXml(text))
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "DOCX сохранён: ${file.name}")
        return file
    }

    // ==================== ПРИВАТНОЕ ====================

    /**
     * Создаёт пустой файл в Documents/ с уникальным именем.
     * Папка создаётся, если её ещё нет.
     */
    private fun newDocumentFile(context: Context, extension: String): File {
        val documentsDir = File(context.filesDir, DOCUMENTS_FOLDER)
        if (!documentsDir.exists()) documentsDir.mkdirs()

        val timestamp = SimpleDateFormat(TIMESTAMP_FORMAT, Locale.getDefault()).format(Date())
        val unique = fileCounter.incrementAndGet()
        return File(documentsDir, "${timestamp}_$unique.$extension")
    }

    private fun addZipEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /**
     * Формирует word/document.xml с текстом.
     * Каждая строка текста = отдельный абзац (w:p).
     * Размер шрифта — 12pt (w:sz = 24 half-points).
     * Формат страницы — A4 (w:pgSz).
     */
    private fun buildDocumentXml(text: String): String = buildString {
        append(XML_DECLARATION)
        append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
        append("<w:body>")

        text.lines().forEach { line ->
            append("<w:p>")
            if (line.isNotBlank()) {
                append("<w:r><w:rPr><w:sz w:val=\"24\"/></w:rPr>")
                append("<w:t xml:space=\"preserve\">")
                append(escapeXml(line))
                append("</w:t></w:r>")
            }
            append("</w:p>")
        }

        // Настройки секции: A4
        append("<w:sectPr>")
        append("<w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
        append("<w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/>")
        append("</w:sectPr>")

        append("</w:body></w:document>")
    }

    /**
     * Экранирует специальные символы XML.
     * ВАЖНО: `&` должен быть заменён первым.
     */
    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    // ==================== КОНСТАНТЫ ====================

    private const val TIMESTAMP_FORMAT = "yyyyMMdd_HHmmss"

    private const val XML_DECLARATION =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"

    private val CONTENT_TYPES_XML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
        </Types>
    """.trimIndent()

    private val RELS_XML = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>
    """.trimIndent()
}