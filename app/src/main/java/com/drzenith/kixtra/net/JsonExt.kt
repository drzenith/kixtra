package com.drzenith.kixtra.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Lenient accessors: Kick's internal API changes shape often, so never crash on a missing field.

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray

fun JsonObject?.o(key: String): JsonObject? = this?.get(key) as? JsonObject
fun JsonObject?.a(key: String): JsonArray? = this?.get(key) as? JsonArray

fun JsonObject?.s(key: String): String? {
    val p = this?.get(key) as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.takeIf { it.isNotBlank() && it != "null" }
}

fun JsonObject?.l(key: String): Long? {
    val p = this?.get(key) as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.toLongOrNull() ?: p.content.toDoubleOrNull()?.toLong()
}

fun JsonObject?.b(key: String): Boolean? {
    val p = this?.get(key) as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return when (p.content.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

fun JsonArray?.objects(): List<JsonObject> = this?.mapNotNull { it as? JsonObject } ?: emptyList()

/** Picks a usable image URL out of Kick's many thumbnail shapes ({url}, {src}, {srcset}, plain string). */
fun imageUrl(el: JsonElement?): String? {
    when (el) {
        null, is JsonNull -> return null
        is JsonPrimitive -> return el.content.takeIf { it.startsWith("http") }
        is JsonObject -> {
            el.s("url")?.let { if (it.startsWith("http")) return it }
            el.s("src")?.let { if (it.startsWith("http")) return it }
            (el.s("srcset") ?: el.s("srcSet") ?: el.s("responsive"))?.let { set ->
                return set.split(",").map { it.trim().substringBefore(" ") }
                    .firstOrNull { it.startsWith("http") }
            }
            return null
        }
        else -> return null
    }
}
