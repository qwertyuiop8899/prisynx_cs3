repositories {
    // The CloudStream app classes (including the Plugin base class used for the
    // settings button) are not shipped in the library AAR. The official
    // pre-release publishes them as classes.jar; pull it as compileOnly so the
    // app classes are never packaged inside the .cs3.
    ivy {
        url = uri("https://github.com/recloudstream/cloudstream/releases/download/pre-release")
        patternLayout { artifact("[artifact].[ext]") }
        metadataSources { artifact() }
        content { includeModule("cloudstream", "classes") }
    }
}

android {
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared/src/main/kotlin"))
}

version = 8

dependencies {
    compileOnly("cloudstream:classes:pre-release@jar")
}

cloudstream {
    description = "IlCorsaroViola: torrent P2P con seeders e formati 4K/1080p; con Real-Debrid, AllDebrid o TorBox i magnet diventano link diretti (con attesa configurabile del download)."
    authors = listOf("PriSynx")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    language = "it"
    iconUrl = "https://raw.githubusercontent.com/qwertyuiop8899/prisynx_cs3/main/assets/logos/IlCorsaroViola.png"
}
