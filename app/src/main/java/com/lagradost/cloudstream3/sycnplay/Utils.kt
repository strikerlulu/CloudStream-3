package com.lagradost.cloudstream3.sycnplay

import java.math.BigInteger
import java.nio.charset.Charset
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

object Utils {
    @JvmStatic
    fun md5(s: String): String {
        val digest: MessageDigest
        try {
            digest = MessageDigest.getInstance("MD5")
            digest.update(s.toByteArray(Charset.forName("US-ASCII")), 0, s.length)
            val magnitude = digest.digest()
            val bi = BigInteger(1, magnitude)
            return String.format("%0" + (magnitude.size shl 1) + "x", bi)
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        }
        return ""
    }
}