package com.heath.haunch.sim

object Seeds {
    fun mix(seed: Long, salt: Int): Long {
        var z = seed + salt.toLong() * 0x9E3779B97F4A7C15uL.toLong()
        z = (z xor (z ushr 30)) * -4658895280553007687L
        z = (z xor (z ushr 27)) * -7723592293110705685L
        return z xor (z ushr 31)
    }

    /** Tonight's arch. The date string is the whole input, so the same night is the same stone. */
    fun daily(date: String): Long {
        var hash = -3750763034362895579L // FNV-1a offset basis
        val text = "haunch|$date"
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= 1099511628211L
        }
        return hash
    }

    fun period(seed: Long, arch: Int): Double {
        val unit = (mix(seed, 10 + arch).ushr(33) % 801L).toInt()
        val jitter = (unit - 400) / 10000.0
        return Balance.PERIODS[arch] * (1.0 + jitter)
    }

    fun initialSide(seed: Long, arch: Int): Side {
        return if ((mix(seed, arch).ushr(63)) == 0L) Side.LEFT else Side.RIGHT
    }
}
