package com.innova.diag.platform.profile

import android.content.res.AssetManager
import com.innova.diag.domain.profile.pack.PackSource

/**
 * Reads brand packs and their schema out of the APK's assets.
 *
 * The only Android-aware part of pack loading: the registry itself is pure Kotlin and takes a
 * [PackSource], which is what lets every registry test run on a plain JVM with a map of strings.
 *
 * @param root asset directory holding the `brands` pack directory and the `schema` directory, whose
 *   only file is [SCHEMA_FILE].
 */
class AssetPackSource(
    private val assets: AssetManager,
    private val root: String = "profiles",
) : PackSource {

    override val location: String get() = "assets/$root/brands"

    override fun listPackFiles(): List<String> =
        assets.list("$root/brands")
            ?.filter { fileName -> fileName.endsWith(".json") }
            ?.sorted()
            ?: emptyList()

    override fun readPack(fileName: String): String =
        assets.open("$root/brands/$fileName").bufferedReader().use { reader -> reader.readText() }

    override fun readSchema(): String =
        assets.open("$root/schema/$SCHEMA_FILE").bufferedReader().use { reader -> reader.readText() }

    companion object {

        /** The schema version every pack must satisfy. */
        const val SCHEMA_FILE: String = "vehicle_pack_schema_v2.json"
    }
}
