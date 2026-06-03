package com.vibecode.tvremote

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

data class SamsungTvApp(
    val appId: String,
    val name: String,
    val iconPath: String? = null,
    val appType: Int? = null,
    val isLock: Int? = null
)

fun parseSamsungTvApps(root: JsonObject): List<SamsungTvApp> {
    val dataElement = root.get("data") ?: return emptyList()
    val appsElement = when {
        dataElement.isJsonObject && dataElement.asJsonObject.has("data") -> dataElement.asJsonObject.get("data")
        dataElement.isJsonArray -> dataElement
        else -> null
    } ?: return emptyList()

    val appsArray = when {
        appsElement.isJsonArray -> appsElement.asJsonArray
        appsElement.isJsonObject -> JsonArray().apply { add(appsElement) }
        else -> return emptyList()
    }

    return appsArray.mapNotNull { element ->
        val appObject = element.asJsonObjectOrNull() ?: return@mapNotNull null
        val appId = appObject.stringValue("appId")
            ?: appObject.stringValue("app_id")
            ?: return@mapNotNull null
        val name = appObject.stringValue("name")
            ?: appObject.stringValue("appName")
            ?: appObject.stringValue("title")
            ?: appId

        SamsungTvApp(
            appId = appId,
            name = name,
            iconPath = appObject.stringValue("iconPath") ?: appObject.stringValue("icon"),
            appType = appObject.intValue("app_type") ?: appObject.intValue("type"),
            isLock = appObject.intValue("is_lock"),
        )
    }
}

private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
    return if (isJsonObject) asJsonObject else null
}

private fun JsonObject.stringValue(name: String): String? {
    val element = get(name) ?: return null
    return when {
        element.isJsonNull -> null
        element.isJsonPrimitive -> element.asString
        else -> element.toString()
    }
}

private fun JsonObject.intValue(name: String): Int? {
    val element = get(name) ?: return null
    return when {
        element.isJsonNull -> null
        element.isJsonPrimitive -> {
            val primitive = element.asJsonPrimitive
            when {
                primitive.isNumber -> primitive.asInt
                primitive.isBoolean -> if (primitive.asBoolean) 1 else 0
                primitive.isString -> primitive.asString.toIntOrNull()
                else -> null
            }
        }
        else -> null
    }
}
