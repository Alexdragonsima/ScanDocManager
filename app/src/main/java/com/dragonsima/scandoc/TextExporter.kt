package com.dragonsima.scandoc

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Экспортирует распознанный текст в TXT и DOCX.
 */
object TextExporter {

    private const val TAG = "TextExporter"
    private const val DOCUMENTS_FOLDER = "Documents"

    /**
     * Сохраняет текст в .txt файл.
     */
    fun saveAsTxt(context: Context, text: String): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(context.filesDir, "$DOCUMENTS_FOLDER/$timestamp.txt")
        file.writeText(text, Charsets.UTF_8)
        Log.d(TAG, "TXT сохранён: ${file.absolutePath}")
        return file
    }

    /**
     * Сохраняет текст в минимальный валидный .docx файл.
     * DOCX — это ZIP-архив со строго определённой структурой.
     * Мы создаём только обязательные части: [Content_Types].xml,
     * _rels/.rels, word/document.xml.
     */
    fun saveAsDocx(context: Context, text: String): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(context.filesDir, "$DOCUMENTS_FOLDER/$timestamp.docx")

        ZipOutputStream(FileOutputStream(file)).use { zip ->
            // 1. [Content_Types].xml — обязательно
            addZipEntry(zip, "[Content_Types].xml", CONTENT_TYPES_XML)

            // 2. _rels/.rels — корневые связи
            addZipEntry(zip, "_rels/.rels", RELS_XML)

            // 3. word/document.xml — сам текст
            val documentXml = buildDocumentXml(text)
            addZipEntry(zip, "word/document.xml", documentXml)
        }

        Log.d(TAG, "DOCX сохранён: ${file.absolutePath}")
        return file
    }

    // ==================== Приватное ====================

    private fun addZipEntry(zip: ZipOutputStream, name: String, content: String) {
        val entry = ZipEntry(name)
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /**
     * Формирует word/document.xml с текстом.
     * Каждая строка текста = отдельный абзац (w:p).
     */
    private fun buildDocumentXml(text: String): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""")
        sb.append("<w:body>")

        text.lines().forEach { line ->
            sb.append("<w:p>")
            if (line.isNotBlank()) {
                sb.append("""<w:r><w:rPr><w:sz w:val="24"/></w:rPr>""") // 12pt
                sb.append("<w:t xml:space=\"preserve\">")
                sb.append(escapeXml(line))
                sb.append("</w:t></w:r>")
            }
            sb.append("</w:p>")
        }

        sb.append("</w:body></w:document>")
        return sb.toString()
    }

    /**
     * Экранирует специальные символы XML.
     */
    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    // ==================== Шаблоны XML ====================

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