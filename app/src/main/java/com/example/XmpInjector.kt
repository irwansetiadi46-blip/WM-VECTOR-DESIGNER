package com.example

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

data class XmpData(
    val title: String = "",
    val description: String = "",
    val keywords: String = "",
    val creator: String = ""
)

object XmpInjector {

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun cleanXmlAndHtml(input: String): String {
        // Remove all XML tags first
        var clean = input.replace(Regex("<[^>]*>"), "")
        // Trim multiple spaces or line breaks
        clean = clean.replace(Regex("\\s+"), " ").trim()
        // Unescape HTML entities
        return clean.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    fun extractXMP(text: String): XmpData {
        val lis = Regex("<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .map { it.groupValues[1] }
            .map { cleanXmlAndHtml(it) }
            .toList()

        if (lis.size >= 3) {
            val title = lis[0]
            val desc = lis[1]
            val creator = lis[2]
            val keywords = lis.drop(3).joinToString(", ")
            return XmpData(title, desc, keywords, creator)
        }

        if (lis.size == 1 && lis[0].contains(",")) {
            return XmpData(keywords = lis[0])
        }

        // Fallback to standard tags
        val titleFallback = findFallbackTag(text, "dc:title")
        val descFallback = findFallbackTag(text, "dc:description")
        val creatorFallback = findFallbackTag(text, "dc:creator")
        
        val subjectMatch = Regex("<dc:subject[^>]*>(.*?)</dc:subject>", RegexOption.DOT_MATCHES_ALL).find(text)
        var keywordsFallback = ""
        if (subjectMatch != null) {
            val subjectContent = subjectMatch.groupValues[1]
            val subjectLis = Regex("<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL)
                .findAll(subjectContent)
                .map { cleanXmlAndHtml(it.groupValues[1]) }
                .toList()
            keywordsFallback = if (subjectLis.isNotEmpty()) {
                subjectLis.joinToString(", ")
            } else {
                cleanXmlAndHtml(subjectContent)
            }
        }

        if (keywordsFallback.isEmpty()) {
            val subjectFallback = findFallbackTag(text, "dc:subject")
            keywordsFallback = subjectFallback
        }

        return XmpData(
            title = titleFallback,
            description = descFallback,
            keywords = keywordsFallback,
            creator = creatorFallback
        )
    }

    private fun findFallbackTag(xml: String, tagName: String): String {
        val regex = Regex("<$tagName[^>]*>(.*?)</$tagName>", RegexOption.DOT_MATCHES_ALL)
        val match = regex.find(xml)
        return if (match != null) {
            cleanXmlAndHtml(match.groupValues[1])
        } else {
            ""
        }
    }

    fun extractXMPFromJpeg(bytes: ByteArray): String? {
        if (bytes.size < 2 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) {
            return null
        }
        val signature = "http://ns.adobe.com/xap/1.0/\u0000"
        val sigBytes = signature.toByteArray(StandardCharsets.UTF_8)

        var offset = 2
        while (offset < bytes.size) {
            if (offset + 1 >= bytes.size) break

            val b1 = bytes[offset]
            val b2 = bytes[offset + 1]

            if (b1 != 0xFF.toByte()) {
                offset++
                continue
            }

            val marker = b2.toInt() and 0xFF
            if (marker == 0x00 || marker == 0xFF) {
                offset += 2
                continue
            }

            if (marker == 0xD9 || marker == 0xDA) {
                break
            }

            if (offset + 3 >= bytes.size) break
            val lenHigh = bytes[offset + 2].toInt() and 0xFF
            val lenLow = bytes[offset + 3].toInt() and 0xFF
            val segmentLen = (lenHigh shl 8) or lenLow

            if (marker == 0xE1) {
                if (offset + 4 + sigBytes.size <= bytes.size) {
                    var sigMatch = true
                    for (j in sigBytes.indices) {
                        if (bytes[offset + 4 + j] != sigBytes[j]) {
                            sigMatch = false
                            break
                        }
                    }
                    if (sigMatch) {
                        val xmlStart = offset + 4 + sigBytes.size
                        val xmlLen = segmentLen - 2 - sigBytes.size
                        if (xmlStart + xmlLen <= bytes.size) {
                            return String(bytes, xmlStart, xmlLen, StandardCharsets.UTF_8)
                        }
                    }
                }
            }
            offset += 2 + segmentLen
        }
        return null
    }

    fun extractXMPFromPng(bytes: ByteArray): String? {
        val pngSignature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (bytes.size < 8) return null
        for (i in 0..7) {
            if (bytes[i] != pngSignature[i]) return null
        }

        val keyword = "XML:com.adobe.xmp\u0000"
        val kwBytes = keyword.toByteArray(StandardCharsets.UTF_8)

        var offset = 8
        while (offset < bytes.size) {
            if (offset + 8 > bytes.size) break

            val lenBuf = ByteBuffer.wrap(bytes, offset, 4)
            lenBuf.order(ByteOrder.BIG_ENDIAN)
            val chunkLen = lenBuf.int

            val typeBytes = ByteArray(4)
            System.arraycopy(bytes, offset + 4, typeBytes, 0, 4)
            val chunkType = String(typeBytes, StandardCharsets.US_ASCII)

            if (chunkType == "iTXt" && offset + 8 + chunkLen <= bytes.size) {
                val bodyOffset = offset + 8
                var kwMatch = true
                if (chunkLen >= kwBytes.size) {
                    for (i in kwBytes.indices) {
                        if (bytes[bodyOffset + i] != kwBytes[i]) {
                            kwMatch = false
                            break
                        }
                    }
                    if (kwMatch) {
                        var curr = bodyOffset + kwBytes.size
                        if (curr + 2 <= bodyOffset + chunkLen) {
                            curr += 2
                            while (curr < bodyOffset + chunkLen && bytes[curr] != 0.toByte()) {
                                curr++
                            }
                            curr++
                            while (curr < bodyOffset + chunkLen && bytes[curr] != 0.toByte()) {
                                curr++
                            }
                            curr++

                            val xmpLen = (bodyOffset + chunkLen) - curr
                            if (xmpLen > 0 && curr + xmpLen <= bytes.size) {
                                return String(bytes, curr, xmpLen, StandardCharsets.UTF_8)
                            }
                        }
                    }
                }
            }
            offset += 4 + 4 + chunkLen + 4
        }
        return null
    }

    fun extractXMPFromEps(bytes: ByteArray): String? {
        try {
            val str = String(bytes, StandardCharsets.UTF_8)
            val startIdx = str.indexOf("<x:xmpmeta")
            if (startIdx != -1) {
                val endIdx = str.indexOf("</x:xmpmeta>", startIdx)
                if (endIdx != -1) {
                    return str.substring(startIdx, endIdx + "</x:xmpmeta>".length)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun parseXMP(originalBytes: ByteArray, isPng: Boolean, isEps: Boolean = false): XmpData? {
        try {
            val xmlStr = (if (isEps) {
                extractXMPFromEps(originalBytes)
            } else if (isPng) {
                extractXMPFromPng(originalBytes)
            } else {
                extractXMPFromJpeg(originalBytes)
            }) ?: return null

            return extractXMP(xmlStr)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun generateXmpMeta(title: String, description: String, keywords: List<String>, creator: String): String {
        val titleEsc = escapeXml(title)
        val descEsc = escapeXml(description)
        val creatorEsc = escapeXml(creator)
        val keywordsHtml = keywords.joinToString("") { kw ->
            "<rdf:li>${escapeXml(kw.trim())}</rdf:li>"
        }

        return """
            |<x:xmpmeta xmlns:x="adobe:ns:meta/">
            |  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            |    <rdf:Description rdf:about="" xmlns:dc="http://purl.org/dc/elements/1.1/">
            |      <dc:title><rdf:Alt><rdf:li xml:lang="x-default">$titleEsc</rdf:li></rdf:Alt></dc:title>
            |      <dc:description><rdf:Alt><rdf:li xml:lang="x-default">$descEsc</rdf:li></rdf:Alt></dc:description>
            |      <dc:creator><rdf:Seq><rdf:li>$creatorEsc</rdf:li></rdf:Seq></dc:creator>
            |      <dc:subject><rdf:Bag>$keywordsHtml</rdf:Bag></dc:subject>
            |    </rdf:Description>
            |  </rdf:RDF>
            |</x:xmpmeta>
        """.trimMargin()
    }

    fun injectIntoJpeg(
        originalBytes: ByteArray,
        title: String,
        description: String,
        keywords: List<String>,
        creator: String
    ): ByteArray {
        if (originalBytes.size < 2 || originalBytes[0] != 0xFF.toByte() || originalBytes[1] != 0xD8.toByte()) {
            return originalBytes
        }

        val xmpXml = generateXmpMeta(title, description, keywords, creator)
        val xmpBytes = xmpXml.toByteArray(StandardCharsets.UTF_8)
        
        val signature = "http://ns.adobe.com/xap/1.0/\u0000"
        val signatureBytes = signature.toByteArray(StandardCharsets.UTF_8)
        
        val seg = ByteArray(4 + signatureBytes.size + xmpBytes.size)
        seg[0] = 0xFF.toByte()
        seg[1] = 0xE1.toByte()
        val len = signatureBytes.size + xmpBytes.size + 2
        seg[2] = ((len ushr 8) and 0xFF).toByte()
        seg[3] = (len and 0xFF).toByte()
        
        System.arraycopy(signatureBytes, 0, seg, 4, signatureBytes.size)
        System.arraycopy(xmpBytes, 0, seg, 4 + signatureBytes.size, xmpBytes.size)
        
        var pos = 2
        while (pos < originalBytes.size - 4) {
            if (originalBytes[pos] == 0xFF.toByte() && originalBytes[pos + 1] == 0xE1.toByte()) {
                val l = ((originalBytes[pos + 2].toInt() and 0xFF) shl 8) + (originalBytes[pos + 3].toInt() and 0xFF)
                var isXmp = true
                for (h in signatureBytes.indices) {
                    if (pos + 4 + h >= originalBytes.size || originalBytes[pos + 4 + h] != signatureBytes[h]) {
                        isXmp = false
                        break
                    }
                }
                if (isXmp) {
                    val newJpeg = ByteArray(originalBytes.size - (2 + l) + seg.size)
                    System.arraycopy(originalBytes, 0, newJpeg, 0, pos)
                    System.arraycopy(seg, 0, newJpeg, pos, seg.size)
                    System.arraycopy(originalBytes, pos + 2 + l, newJpeg, pos + seg.size, originalBytes.size - (pos + 2 + l))
                    return newJpeg
                }
                pos += 2 + l
            } else if (originalBytes[pos] == 0xFF.toByte() && (originalBytes[pos + 1] == 0xDA.toByte() || originalBytes[pos + 1] == 0xD9.toByte())) {
                break
            } else {
                pos++
            }
        }
        
        val out = ByteArray(originalBytes.size + seg.size)
        System.arraycopy(originalBytes, 0, out, 0, 2)
        System.arraycopy(seg, 0, out, 2, seg.size)
        System.arraycopy(originalBytes, 2, out, 2 + seg.size, originalBytes.size - 2)
        return out
    }

    fun injectIntoPng(
        originalBytes: ByteArray,
        title: String,
        description: String,
        keywords: List<String>,
        creator: String
    ): ByteArray {
        val xmpXml = generateXmpMeta(title, description, keywords, creator)
        val xmpBytes = xmpXml.toByteArray(StandardCharsets.UTF_8)

        val keywordBytes = "XML:com.adobe.xmp\u0000".toByteArray(StandardCharsets.UTF_8)
        val compBytes = byteArrayOf(0, 0)
        val langAndTransBytes = byteArrayOf(0, 0)

        val chunkDataOutput = ByteArrayOutputStream()
        chunkDataOutput.write(keywordBytes)
        chunkDataOutput.write(compBytes)
        chunkDataOutput.write(langAndTransBytes)
        chunkDataOutput.write(xmpBytes)

        val chunkData = chunkDataOutput.toByteArray()
        val chunkTypeBytes = "iTXt".toByteArray(StandardCharsets.UTF_8)

        val buffer = ByteBuffer.allocate(4 + chunkTypeBytes.size + chunkData.size + 4)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(chunkData.size)
        buffer.put(chunkTypeBytes)
        buffer.put(chunkData)

        val crc = CRC32()
        crc.update(chunkTypeBytes)
        crc.update(chunkData)
        buffer.putInt(crc.value.toInt())

        val itxtChunkBytes = buffer.array()

        val pngSignature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (originalBytes.size < 8) {
            throw IllegalArgumentException("Not a valid PNG file (Too short)")
        }
        for (i in 0..7) {
            if (originalBytes[i] != pngSignature[i]) {
                throw IllegalArgumentException("Not a valid PNG file (Signature mismatch)")
            }
        }

        val outputStream = ByteArrayOutputStream()
        outputStream.write(pngSignature)

        var offset = 8
        while (offset < originalBytes.size) {
            if (offset + 8 > originalBytes.size) {
                outputStream.write(originalBytes, offset, originalBytes.size - offset)
                break
            }

            val lenBuf = ByteBuffer.wrap(originalBytes, offset, 4)
            lenBuf.order(ByteOrder.BIG_ENDIAN)
            val chunkLen = lenBuf.int

            val typeBytes = ByteArray(4)
            System.arraycopy(originalBytes, offset + 4, typeBytes, 0, 4)
            val chunkType = String(typeBytes, StandardCharsets.US_ASCII)

            var isExistingXmp = false
            if (chunkType == "iTXt" && offset + 8 + "XML:com.adobe.xmp\u0000".length <= originalBytes.size) {
                val kwCompare = String(originalBytes, offset + 8, "XML:com.adobe.xmp\u0000".length, StandardCharsets.UTF_8)
                if (kwCompare == "XML:com.adobe.xmp\u0000") {
                    isExistingXmp = true
                }
            }

            if (isExistingXmp) {
                offset += 4 + 4 + chunkLen + 4
            } else {
                val totalChunkSize = 4 + 4 + chunkLen + 4
                if (offset + totalChunkSize <= originalBytes.size) {
                    outputStream.write(originalBytes, offset, totalChunkSize)
                    offset += totalChunkSize
                } else {
                    outputStream.write(originalBytes, offset, originalBytes.size - offset)
                    break
                }

                if (chunkType == "IHDR") {
                    outputStream.write(itxtChunkBytes)
                }
            }
        }

        return outputStream.toByteArray()
    }

    fun injectIntoEps(
        originalBytes: ByteArray,
        title: String,
        description: String,
        keywords: List<String>,
        creator: String
    ): ByteArray {
        try {
            // FIX Bug 5: Tangani binary EPS header (magic: 0xC5D0D3C6)
            val epsBytes = if (originalBytes.size >= 4 &&
                originalBytes[0] == 0xC5.toByte() &&
                originalBytes[1] == 0xD0.toByte() &&
                originalBytes[2] == 0xD3.toByte() &&
                originalBytes[3] == 0xC6.toByte()
            ) {
                val psOffset = ByteBuffer.wrap(originalBytes, 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                val psLength = ByteBuffer.wrap(originalBytes, 8, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                originalBytes.copyOfRange(psOffset, psOffset + psLength)
            } else {
                originalBytes
            }

            val fileStr = String(epsBytes, StandardCharsets.UTF_8)
            val xmpXml = generateXmpMeta(title, description, keywords, creator)

            // FIX Bug 3: Tambahkan BOM (\uFEFF) di xpacket begin
            // FIX Bug 4: Bangun string tanpa trimIndent agar tidak ada indent tak terduga
            val xpacketContent = buildString {
                append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
                append(xmpXml)
                append("\n<?xpacket end=\"w\"?>")
            }

            // FIX Bug 2: Gunakan %begin_xml_packet / %end_xml_packet (standar Adobe)
            val contentSize = xpacketContent.toByteArray(StandardCharsets.UTF_8).size
            val xpacketBlock = buildString {
                append("%begin_xml_packet: $contentSize\n")
                append(xpacketContent)
                append("\n%end_xml_packet\n\n")
            }

            // Jika sudah ada XMP → replace
            val xmpStart = fileStr.indexOf("<x:xmpmeta")
            if (xmpStart != -1) {
                val xmpEnd = fileStr.indexOf("</x:xmpmeta>", xmpStart)
                if (xmpEnd != -1) {
                    val afterXmp = xmpEnd + "</x:xmpmeta>".length

                    // Cari batas xpacket (mundur dan maju)
                    val packetStartIdx = fileStr.lastIndexOf("<?xpacket begin=", xmpStart)
                        .takeIf { it != -1 } ?: xmpStart
                    val packetEndRaw = fileStr.indexOf("<?xpacket end=", afterXmp)
                    val packetEndIdx = if (packetEndRaw != -1) {
                        fileStr.indexOf("?>", packetEndRaw) + 2
                    } else afterXmp

                    // Cari wrapper %begin_xml_packet di baris sebelumnya
                    val lineBeforeStart = fileStr.lastIndexOf('\n', packetStartIdx - 1)
                    val lineText = if (lineBeforeStart != -1)
                        fileStr.substring(lineBeforeStart + 1, packetStartIdx).trim() else ""
                    val replaceStart = if (lineText.startsWith("%begin_xml_packet") ||
                        lineText.startsWith("%XMP_Begin")) lineBeforeStart + 1
                    else packetStartIdx

                    // Cari wrapper %end_xml_packet di baris sesudahnya
                    val lineAfterEnd = fileStr.indexOf('\n', packetEndIdx)
                    val lineAfterText = if (lineAfterEnd != -1)
                        fileStr.substring(packetEndIdx, lineAfterEnd).trim() else ""
                    val replaceEnd = if (lineAfterText == "%end_xml_packet" ||
                        lineAfterText == "%XMP_End") lineAfterEnd + 1
                    else packetEndIdx

                    val newContent = fileStr.substring(0, replaceStart) +
                                     xpacketBlock +
                                     fileStr.substring(replaceEnd)
                    return newContent.toByteArray(StandardCharsets.UTF_8)
                }
            }

            // FIX Bug 1: Sisipkan setelah %%EndComments, bukan setelah baris pertama
            val insertionPoint = findEpsInsertionPoint(fileStr)
            val newContent = fileStr.substring(0, insertionPoint) +
                             xpacketBlock +
                             fileStr.substring(insertionPoint)
            return newContent.toByteArray(StandardCharsets.UTF_8)

        } catch (e: Exception) {
            e.printStackTrace()
            return originalBytes
        }
    }

    // FIX Bug 1: Helper untuk cari posisi sisip yang benar
    private fun findEpsInsertionPoint(fileStr: String): Int {
        // Prioritas 1: setelah %%EndComments
        val endComments = fileStr.indexOf("%%EndComments")
        if (endComments != -1) {
            val nextLine = fileStr.indexOf('\n', endComments)
            return if (nextLine != -1) nextLine + 1 else endComments + "%%EndComments".length
        }

        // Prioritas 2: setelah blok komentar DSC terakhir di header
        var lastDscEnd = -1
        var pos = 0
        while (pos < fileStr.length) {
            val lineEnd = fileStr.indexOf('\n', pos)
            val line = if (lineEnd != -1) fileStr.substring(pos, lineEnd) else fileStr.substring(pos)
            if (line.startsWith("%%") || line.startsWith("%!")) {
                lastDscEnd = if (lineEnd != -1) lineEnd + 1 else fileStr.length
            } else if (lastDscEnd != -1) {
                break
            }
            pos = if (lineEnd != -1) lineEnd + 1 else fileStr.length
        }
        if (lastDscEnd != -1) return lastDscEnd

        // Fallback: setelah baris pertama
        val firstLine = fileStr.indexOf('\n')
        return if (firstLine != -1) firstLine + 1 else 0
    }
        // Taruh kode ini di dalam object XmpInjector paling bawah, Mandor!
    fun injectMetadataSmart(
        context: android.content.Context,
        targetFilePath: String,
        title: String,
        description: String,
        keywords: List<String>,
        creator: String
    ): Boolean {
        return try {
            if (targetFilePath.endsWith(".eps", ignoreCase = true)) {
                // 1. JIKA FORMAT EPS: Lempar ke biner ExifTool (Anti-Gagal di Shutterstock)
                val binaryPath = com.example.util.ExiftoolInstaller.installIfNeeded(context)
                if (binaryPath != null) {
                    val command = listOf(
                        binaryPath,
                        "-XMP:Title=$title",
                        "-XMP:Description=$description",
                        "-XMP:Subject=${keywords.joinToString(", ")}",
                        "-overwrite_original",
                        targetFilePath
                    )
                    val process = ProcessBuilder(command).start()
                    val exitCode = process.waitFor()
                    exitCode == 0
                } else {
                    false
                }
            } else {
                // 2. JIKA FORMAT RASTER (JPEG/PNG): Gunakan file injection native lu yang sudah rapi
                val file = java.io.File(targetFilePath)
                val originalBytes = file.readBytes()
                
                val newBytes = if (targetFilePath.endsWith(".png", ignoreCase = true)) {
                    injectIntoPng(originalBytes, title, description, keywords, creator)
                } else {
                    injectIntoJpeg(originalBytes, title, description, keywords, creator)
                }
                
                file.writeBytes(newBytes)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

}
