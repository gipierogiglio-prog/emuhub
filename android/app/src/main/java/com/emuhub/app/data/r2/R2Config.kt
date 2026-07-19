package com.emuhub.app.data.r2

/**
 * Configuração do bucket Cloudflare R2 de onde o app busca jogos.
 * O acesso usa a API S3 do R2 com assinatura AWS SigV4 (ver [R2Client]).
 */
data class R2Config(
    val enabled: Boolean = false,
    val endpoint: String = "https://37ec01d5489bf05685115dd1c195c512.r2.cloudflarestorage.com",
    val bucket: String = "gpbox-jogos-emuladores",  // bucket existente, mantido
    val region: String = "auto",
    val accessKey: String = "34351de9bfa1e2b45be740ee5129c448",
    val secretKey: String = "d89a1c85d54e06f3b290f4f4743440c9f014ba933a3e170a8d40c3aa6af8d0da",
    val gamesPrefix: String = "PC/GPBOXPC/gpbox/roms/",
    val cacheDir: String = "gpbox_cache",
    /** Tamanho máximo do cache local de downloads, em MB. */
    val maxCacheMB: Long = 2048,
)
