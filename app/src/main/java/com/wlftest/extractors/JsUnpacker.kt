package com.wlftest.extractors

import com.wlftest.ui.LogCollector
import kotlin.math.pow

// https://github.com/cylonu87/JsUnpacker
// Copiado de WlfMovie, adaptado: android.util.Log -> LogCollector, Pattern -> Kotlin Regex
class JsUnpacker(packedJS: String?) {
    private var packedJS: String? = null

    fun detect(): Boolean {
        val js = packedJS?.replace(" ", "") ?: return false
        return Regex("""eval\(function\(p,a,c,k,e,[rd]""").containsMatchIn(js)
    }

    fun unpack(): String? {
        val js = packedJS ?: return null
        try {
            val regex = Regex(
                """\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""",
                setOf(RegexOption.DOT_MATCHES_ALL)
            )
            val match = regex.find(js) ?: return null
            if (match.groupValues.size != 5) return null

            val payload = match.groupValues[1].replace("\\'", "'")
            val radixStr = match.groupValues[2]
            val countStr = match.groupValues[3]
            val symtab = match.groupValues[4].split("|").toTypedArray()
            var radix = 36
            var count = 0
            try { radix = radixStr.toInt() } catch (_: Exception) {}
            try { count = countStr.toInt() } catch (_: Exception) {}
            if (symtab.size != count) {
                throw Exception("Unknown p.a.c.k.e.r. encoding")
            }
            val unbase = Unbase(radix)
            val wordRegex = Regex("""\b\w+\b""")
            val decoded = StringBuilder(payload)
            var replaceOffset = 0
            val matches = wordRegex.findAll(payload).toList()
            for (m in matches) {
                val word = m.value
                val x = try { unbase.unbase(word) } catch (_: Exception) { break }
                var value: String? = null
                if (x < symtab.size && x >= 0) value = symtab[x]
                if (value != null && value.isNotEmpty()) {
                    decoded.replace(m.range.first + replaceOffset, m.range.last + 1 + replaceOffset, value)
                    replaceOffset += value.length - word.length
                }
            }
            return decoded.toString()
        } catch (e: Exception) {
            LogCollector.log("WARN", "JsUnpacker error: ${e.message}")
        }
        return null
    }

    private inner class Unbase(private val radix: Int) {
        private val ALPHABET_62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val ALPHABET_95 =
            " !\"#\$%&\\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"
        private var alphabet: String? = null
        private var dictionary: HashMap<String, Int>? = null

        fun unbase(str: String): Int {
            var ret = 0
            if (alphabet == null) {
                ret = str.toInt(radix)
            } else {
                val tmp = StringBuilder(str).reverse().toString()
                for (i in tmp.indices) {
                    ret += (radix.toDouble().pow(i.toDouble()) * dictionary!![tmp.substring(i, i + 1)]!!).toInt()
                }
            }
            return ret
        }

        init {
            if (radix > 36) {
                when {
                    radix < 62 -> alphabet = ALPHABET_62.substring(0, radix)
                    radix in 63..94 -> alphabet = ALPHABET_95.substring(0, radix)
                    radix == 62 -> alphabet = ALPHABET_62
                    radix == 95 -> alphabet = ALPHABET_95
                }
                val dict = HashMap<String, Int>(95)
                val alph = alphabet!!
                for (i in 0 until alph.length) {
                    dict[alph.substring(i, i + 1)] = i
                }
                dictionary = dict
            }
        }
    }

    init { this.packedJS = packedJS }
}