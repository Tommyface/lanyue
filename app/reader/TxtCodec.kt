package io.lanread.app.reader

/** TXT 文件编码嗅探：UTF-8 BOM / UTF-16 BOM / GB18030 兜底 */
object TxtCodec {

    fun decode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // BOM 检测
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // 尝试 UTF-8，失败则按 GB18030（覆盖 GBK/GB2312）
        return try {
            val s = String(bytes, Charsets.UTF_8)
            // 检验替换字符，判断是否真的合法 UTF-8
            if (s.contains('\uFFFD')) String(bytes, charset("GB18030")) else s
        } catch (e: Exception) {
            String(bytes, charset("GB18030"))
        }
    }
}
