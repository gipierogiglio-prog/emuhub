package com.emuhub.app.data.r2

import android.util.Xml
import com.emuhub.app.util.EmuHubLogger
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.xmlpull.v1.XmlPullParser

/** Um objeto (arquivo) dentro do bucket R2. */
data class R2Object(
    val key: String,
    val sizeBytes: Long,
    val lastModified: Long,
)

/**
 * Cliente mínimo para a API S3 do Cloudflare R2 usando apenas OkHttp e
 * criptografia do JDK. A assinatura AWS Signature V4 é feita manualmente:
 * canonical request → string to sign → HMAC-SHA256 em cadeia → header Authorization.
 */
class R2Client(private val config: R2Config) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val host: String = URI(config.endpoint).host

    /**
     * Lista todos os objetos sob um prefixo (paginando ListObjectsV2 até o fim).
     */
    @Throws(IOException::class)
    fun listObjects(prefix: String): List<R2Object> {
        val results = mutableListOf<R2Object>()
        var continuationToken: String? = null

        do {
            val query = buildMap {
                put("list-type", "2")
                put("prefix", prefix)
                put("max-keys", "1000")
                continuationToken?.let { put("continuation-token", it) }
            }
            val request = signedRequest("GET", "/${config.bucket}", query)
            val page = http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("R2 list falhou: HTTP ${response.code} ${response.body?.string()?.take(300)}")
                }
                parseListResult(response.body?.string().orEmpty())
            }
            results += page.objects
            continuationToken = page.nextContinuationToken
        } while (continuationToken != null)

        return results
    }

    /**
     * Baixa um objeto para [dest], reportando progresso em bytes.
     * Retorna o total de bytes gravados.
     */
    @Throws(IOException::class)
    fun downloadObject(
        key: String,
        dest: File,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Long {
        val request = signedRequest("GET", "/${config.bucket}/$key", emptyMap())
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("R2 download falhou ($key): HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("R2 download sem corpo ($key)")
            val total = body.contentLength()
            dest.parentFile?.mkdirs()

            var written = 0L
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress(written, total)
                    }
                }
            }
            return written
        }
    }

    /**
     * Sobe um arquivo para o bucket via PUT assinado (payload SHA-256 no
     * SigV4). Retorna true se o R2 aceitou (2xx); false em erro de rede ou
     * HTTP — o caller decide manter o arquivo e re-tentar depois.
     */
    fun uploadObject(key: String, file: File, contentType: String = "text/plain"): Boolean {
        return try {
            val payloadHash = sha256HexFile(file)
            val body = file.asRequestBody(contentType.toMediaTypeOrNull())
            val request = signedRequest(
                "PUT", "/${config.bucket}/$key", emptyMap(), body, payloadHash, contentType)
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string()?.take(500) ?: "(sem body)"
                    EmuHubLogger.e(TAG, "Upload falhou ($key): HTTP ${response.code} $errBody")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            EmuHubLogger.e(TAG, "Upload exception ($key): ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Gera uma URL pré-assinada (query-string auth) válida por [expiresInSeconds].
     */
    fun getPresignedUrl(key: String, expiresInSeconds: Long = 3600): String {
        val now = Instant.now()
        val amzDate = AMZ_DATE_FORMAT.format(now.atOffset(ZoneOffset.UTC))
        val dateStamp = DATE_STAMP_FORMAT.format(now.atOffset(ZoneOffset.UTC))
        val scope = "$dateStamp/${config.region}/s3/aws4_request"
        val canonicalPath = uriEncodePath("/${config.bucket}/$key")

        val query = sortedMapOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-Credential" to "${config.accessKey}/$scope",
            "X-Amz-Date" to amzDate,
            "X-Amz-Expires" to expiresInSeconds.toString(),
            "X-Amz-SignedHeaders" to "host",
        )
        val canonicalQuery = canonicalQueryString(query)

        val canonicalRequest = listOf(
            "GET",
            canonicalPath,
            canonicalQuery,
            "host:$host\n",
            "host",
            "UNSIGNED-PAYLOAD",
        ).joinToString("\n")

        val stringToSign = listOf(ALGORITHM, amzDate, scope, sha256Hex(canonicalRequest)).joinToString("\n")
        val signature = hex(hmacSha256(signingKey(dateStamp), stringToSign))

        return "${config.endpoint}$canonicalPath?$canonicalQuery&X-Amz-Signature=$signature"
    }

    // ---------------------------------------------------------------------
    // Assinatura SigV4
    // ---------------------------------------------------------------------

    /** Monta um Request OkHttp já assinado com header Authorization SigV4. */
    private fun signedRequest(
        method: String,
        path: String,
        query: Map<String, String>,
        body: RequestBody? = null,
        payloadHash: String = EMPTY_SHA256,
        contentType: String? = null,
    ): Request {
        val now = Instant.now()
        val amzDate = AMZ_DATE_FORMAT.format(now.atOffset(ZoneOffset.UTC))
        val dateStamp = DATE_STAMP_FORMAT.format(now.atOffset(ZoneOffset.UTC))
        val scope = "$dateStamp/${config.region}/s3/aws4_request"

        val canonicalPath = uriEncodePath(path)
        val canonicalQuery = canonicalQueryString(query.toSortedMap())

        // Headers assinados devem estar em ordem alfabética, minúsculos, sem
        // espaços extras. content-type (quando presente) vem antes de host.
        val ct = contentType?.trim()?.lowercase()
        val canonicalHeaders = buildString {
            if (ct != null) append("content-type:$ct\n")
            append("host:$host\n")
            append("x-amz-content-sha256:$payloadHash\n")
            append("x-amz-date:$amzDate\n")
        }
        val signedHeaders = if (ct != null) {
            "content-type;host;x-amz-content-sha256;x-amz-date"
        } else {
            "host;x-amz-content-sha256;x-amz-date"
        }

        val canonicalRequest = listOf(
            method,
            canonicalPath,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")

        val stringToSign = listOf(ALGORITHM, amzDate, scope, sha256Hex(canonicalRequest)).joinToString("\n")
        val signature = hex(hmacSha256(signingKey(dateStamp), stringToSign))

        val authorization = "$ALGORITHM Credential=${config.accessKey}/$scope, " +
            "SignedHeaders=$signedHeaders, Signature=$signature"

        val url = buildString {
            append(config.endpoint)
            append(canonicalPath)
            if (canonicalQuery.isNotEmpty()) append('?').append(canonicalQuery)
        }

        return Request.Builder()
            .url(url)
            .method(method, body)
            .header("Host", host)
            .header("x-amz-content-sha256", payloadHash)
            .header("x-amz-date", amzDate)
            .header("Authorization", authorization)
            .apply {
                // O header enviado tem que ser byte a byte o valor assinado
                if (ct != null) header("Content-Type", ct)
            }
            .build()
    }

    /** Cadeia de HMACs que deriva a chave de assinatura do dia. */
    private fun signingKey(dateStamp: String): ByteArray {
        val kDate = hmacSha256("AWS4${config.secretKey}".toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmacSha256(kDate, config.region)
        val kService = hmacSha256(kRegion, "s3")
        return hmacSha256(kService, "aws4_request")
    }

    // ---------------------------------------------------------------------
    // Parsing do XML de ListObjectsV2
    // ---------------------------------------------------------------------

    private data class ListPage(val objects: List<R2Object>, val nextContinuationToken: String?)

    private fun parseListResult(xml: String): ListPage {
        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        val objects = mutableListOf<R2Object>()
        var nextToken: String? = null
        var truncated = false

        var inContents = false
        var key: String? = null
        var size = 0L
        var lastModified = 0L
        var currentTag: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (parser.name == "Contents") inContents = true
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    if (inContents) {
                        when (currentTag) {
                            "Key" -> key = text
                            "Size" -> size = text.trim().toLongOrNull() ?: 0L
                            "LastModified" -> lastModified = parseIso8601(text.trim())
                        }
                    } else {
                        when (currentTag) {
                            "NextContinuationToken" -> nextToken = text.trim()
                            "IsTruncated" -> truncated = text.trim().toBoolean()
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    currentTag = null
                    if (parser.name == "Contents") {
                        key?.let { objects += R2Object(it, size, lastModified) }
                        inContents = false
                        key = null
                        size = 0L
                        lastModified = 0L
                    }
                }
            }
            event = parser.next()
        }

        return ListPage(objects, if (truncated) nextToken else null)
    }

    companion object {
        private const val TAG = "R2Client"
        private const val ALGORITHM = "AWS4-HMAC-SHA256"

        /** SHA-256 do corpo vazio — requests GET não têm payload. */
        private const val EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        private val AMZ_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private val DATE_STAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd")

        private fun parseIso8601(text: String): Long =
            runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)

        private fun sha256Hex(text: String): String =
            hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

        private fun sha256HexFile(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
            }
            return hex(md.digest())
        }

        private fun hmacSha256(key: ByteArray, data: String): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data.toByteArray(Charsets.UTF_8))
            }

        private fun hex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        /**
         * Percent-encoding conforme RFC 3986 exigido pelo SigV4: só letras,
         * dígitos e "-._~" ficam sem escape; espaço vira %20 (nunca "+").
         */
        private fun uriEncode(value: String, encodeSlash: Boolean = true): String {
            val out = StringBuilder()
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val c = byte.toInt().toChar()
                when {
                    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-._~" -> out.append(c)
                    c == '/' && !encodeSlash -> out.append(c)
                    else -> out.append("%%%02X".format(byte.toInt() and 0xFF))
                }
            }
            return out.toString()
        }

        /** Codifica um caminho preservando as barras entre segmentos. */
        private fun uriEncodePath(path: String): String = uriEncode(path, encodeSlash = false)

        /** Query string canônica: chaves ordenadas, chave e valor escapados. */
        private fun canonicalQueryString(sorted: Map<String, String>): String =
            sorted.entries.joinToString("&") { (k, v) -> "${uriEncode(k)}=${uriEncode(v)}" }
    }
}
